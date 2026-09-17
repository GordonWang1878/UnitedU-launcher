#!/usr/bin/env bash
set -euo pipefail

# scripts/release.sh <version> [--dry-run] [--notes "文本"]
#
# 构建 release APK、生成两份 latest.json(spec §7.2/§7.3)、发布到 GitHub Release,
# 并在配置了腾讯云 COS 时把同一份 APK 与一份 apkUrl 指向 COS 的 latest.json 也发过去。
# 由 Gordon 本机运行;发布是不可逆动作,脚本本身不做任何交互确认——运行前自己确认版本号对。
#
#   <version>   例如 1.0.0-beta;必须与本次构建出的 APK 里的 versionName 完全一致
#   --dry-run   只构建 + 在 dist/ 下生成产物并打印将要做的事,不建 tag、不 push、
#               不 gh release、不 coscli 上传——没有任何网络写入
#   --notes     发布说明;不给则取 dist/notes.txt,再没有就用一句默认文案。
#               这份原文原样进 GitHub Release 描述;写进 latest.json 的 notes 字段时
#               会截到 parseLatest()(UpdateChecker.kt)的 200 字上限,两处不是同一件事。
#
# 凭据只从 coscli 自己的 ~/.cos.yaml 读;本脚本、本仓库都不存任何密钥。

usage() {
  cat <<'EOF'
用法: scripts/release.sh <version> [--dry-run] [--notes "发布说明"]

  <version>   如 1.0.0-beta —— 要与本次构建出的 APK versionName 一致,不一致就中止
  --dry-run   只构建 + 生成 dist/ 下的产物,不发布(不建 tag、不 push、不 gh release、不 coscli)
  --notes     发布说明文本;省略则读 dist/notes.txt,再没有用默认文案
  -h, --help  显示本说明
EOF
}

DRY_RUN=0
VERSION=""
NOTES_ARG=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --notes)
      if [[ $# -lt 2 ]]; then
        echo "--notes 后面缺参数" >&2
        exit 1
      fi
      NOTES_ARG="$2"
      shift 2
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    -*)
      echo "未知参数:$1" >&2
      usage >&2
      exit 1
      ;;
    *)
      if [[ -n "$VERSION" ]]; then
        echo "只能给一个 <version> 参数(已经是 $VERSION,又给了 $1)" >&2
        exit 1
      fi
      VERSION="$1"
      shift
      ;;
  esac
done

if [[ -z "$VERSION" ]]; then
  echo "缺少 <version> 参数" >&2
  usage >&2
  exit 1
fi
# 版本号要能安全地拼进 git tag 与文件名:只认数字、字母、点、连字符。
if ! [[ "$VERSION" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*$ ]]; then
  echo "版本号里有不安全的字符:$VERSION(只能用字母数字、点、连字符)" >&2
  exit 1
fi

# 脚本可以从任意目录被调用,始终按自己的路径定位仓库根目录(这里就是本 worktree 根)。
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TAG="v${VERSION}"

# ---- 前置检查:工作区干净、tag 不存在 ----
#
# git status 是否干净:正式发布必须干净,否则发布的 APK 对应不上任何一次提交。
# --dry-run 只降级成警告继续跑——脚本刚写完、第一次拿来自测时工作区几乎必然有未提交的
# release.sh 本身,逼着先提交一次空脚本才能测很别扭;dry-run 不建 tag、不发布,脏树对它
# 没有实际危害,只是提醒「正式发布前记得先提交」。
if [[ -n "$(git status --porcelain)" ]]; then
  if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "警告:工作区不干净(dry-run 继续;正式发布前必须先提交)" >&2
  else
    echo "工作区不干净,先提交或清理再发布:" >&2
    git status --porcelain >&2
    exit 1
  fi
fi

# tag 是否已存在:两种模式都硬检查——dry-run 的目的通常是「测试即将发布的这个版本」,
# 版本已经发布过时继续跑只会让人误以为还没发。真要重跑同版本的 dry-run,先删本地 tag。
if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
  echo "tag ${TAG} 已存在" >&2
  exit 1
fi

# ---- 构建 ----
echo "==> source scripts/env.sh && gradle --no-daemon assembleRelease"
# shellcheck source=scripts/env.sh
source scripts/env.sh
gradle --no-daemon assembleRelease

APK_SRC="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK_SRC" ]]; then
  echo "构建产物没找到:$APK_SRC" >&2
  exit 1
fi

mkdir -p dist
APK_NAME="unitedu-${VERSION}.apk"
APK_DIST="dist/${APK_NAME}"
cp "$APK_SRC" "$APK_DIST"

# ---- 从构建出的 APK 本身读身份(spec 要求:不信 Gradle 文件,信 APK 实际装的是什么)----
#
# latest.json 的 versionCode 必须与这个 APK 的 versionCode 一致——App 侧的 checkUpdateApk
# 会拿 latest.json 声明的 versionCode 与下载包实际的 versionCode 比对,两边任何一处手误
# (比如忘了在 build.gradle.kts 里提前改号)都会让所有用户的「检查更新」永久卡死在校验失败。
AAPT2="${ANDROID_HOME}/build-tools/35.0.0/aapt2"
if [[ ! -x "$AAPT2" ]]; then
  echo "aapt2 不存在或不可执行:$AAPT2" >&2
  exit 1
fi
BADGING="$("$AAPT2" dump badging "$APK_DIST")"

PKG_NAME="$(printf '%s\n' "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)"
APK_VERSION_CODE="$(printf '%s\n' "$BADGING" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)"
APK_VERSION_NAME="$(printf '%s\n' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
MIN_SDK="$(printf '%s\n' "$BADGING" | sed -n "s/^sdkVersion:'\([0-9]*\)'.*/\1/p;s/^minSdkVersion:'\([0-9]*\)'.*/\1/p" | head -1)"

if [[ "$PKG_NAME" != "com.uniteduone.launcher" ]]; then
  echo "包名不对:'${PKG_NAME}'(期望 com.uniteduone.launcher)—— 是不是打错了 APK?" >&2
  exit 1
fi
if [[ -z "$APK_VERSION_CODE" ]]; then
  echo "aapt2 没读出 versionCode,badging 输出:" >&2
  printf '%s\n' "$BADGING" >&2
  exit 1
fi
if [[ "$APK_VERSION_NAME" != "$VERSION" ]]; then
  echo "versionName 与参数不一致:APK 里是 '${APK_VERSION_NAME}',参数给的是 '${VERSION}'" >&2
  echo "先在 app/build.gradle.kts 里把 versionName 改成 ${VERSION} 再重新发布" >&2
  exit 1
fi
if [[ -z "$MIN_SDK" ]]; then
  echo "aapt2 没读出 sdkVersion(minSdk),badging 输出:" >&2
  printf '%s\n' "$BADGING" >&2
  exit 1
fi

# ---- sha256(与 UpdateChecker.kt 的 sha256Hex 同算法,64 位小写 hex)----
SHA256="$(shasum -a 256 "$APK_DIST" | awk '{print $1}')"

# ---- 发布说明:参数 > dist/notes.txt > 默认文案 ----
if [[ -n "$NOTES_ARG" ]]; then
  NOTES_RAW="$NOTES_ARG"
elif [[ -f "dist/notes.txt" ]]; then
  NOTES_RAW="$(cat dist/notes.txt)"
else
  NOTES_RAW="UnitedU ${VERSION} 发布。"
fi
# 落一份到 dist/notes.txt:不管来源是参数还是默认文案,--notes-file 统一从这里读,
# 也方便发布前再手动改一遍措辞。dist/ 不进仓库(.gitignore),不是持久配置。
printf '%s' "$NOTES_RAW" >dist/notes.txt
NOTES_FILE="dist/notes.txt"

# ---- 生成 latest.json(spec §7.2)----
#
# 用 python3 的 json 模块生成,不手写字符串拼接:中文发布说明里随便一个引号或换行,
# 手拼的 JSON 就坏给用户看不出来。ensure_ascii(默认开)把非 ASCII 字符转成 \uXXXX——
# UpdateChecker.kt 的 unescapeJson 完整支持这种转义(含代理对),两边天然对得上
# (见该文件里那段专门讲 json.dumps 的注释)。
#
# notes 在这里额外截到 parseLatest() 的 200 字上限(NOTES_MAX,UpdateChecker.kt):
# 按 UTF-16 code unit 数截断,截断点若劈开一个代理对(emoji)就把落单的高位代理一并丢掉——
# 与 Kotlin 那边的 capNotes 逐字节同构,不能只按 Python 的字符数(codepoint)截,
# 否则超出 BMP 的字符会让两边数出不同的长度。
gen_manifest() {
  local apk_url="$1" out="$2"
  python3 - "$APK_VERSION_CODE" "$APK_VERSION_NAME" "$NOTES_RAW" "$apk_url" "$SHA256" "$MIN_SDK" "$out" <<'PY'
import json
import sys

version_code, version_name, notes_raw, apk_url, sha256, min_sdk, out = sys.argv[1:8]

NOTES_MAX = 200
u16 = notes_raw.encode("utf-16-le")
if len(u16) // 2 > NOTES_MAX:
    cut = u16[: NOTES_MAX * 2]
    last_unit = int.from_bytes(cut[-2:], "little")
    if 0xD800 <= last_unit <= 0xDBFF:  # 截断点劈开了一个代理对,连同高位一起丢掉
        cut = cut[:-2]
    notes = cut.decode("utf-16-le")
else:
    notes = notes_raw

manifest = {
    "versionCode": int(version_code),
    "versionName": version_name,
    "notes": notes,
    "apkUrl": apk_url,
    "sha256": sha256,
    "minSdk": int(min_sdk),
}

with open(out, "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=True, indent=2)
    f.write("\n")
PY
}

GITHUB_APK_URL="https://github.com/GordonWang1878/UnitedU-launcher/releases/download/${TAG}/${APK_NAME}"
gen_manifest "$GITHUB_APK_URL" "dist/latest.json"
echo "==> dist/latest.json 已生成(apkUrl = ${GITHUB_APK_URL})"

# ---- GitHub Release ----
#
# **必须发成普通(latest)release,不能带 --prerelease——哪怕 versionName 里有 "-beta"。**
# App 内检查更新的 GitHub 兜底通道固定读 releases/latest/download/latest.json
# (BuildConfig.UPDATE_URLS 的默认值,见 app/build.gradle.kts);GitHub 的 "latest" 释义
# 是「最新一个不是 prerelease 也不是 draft 的 release」,一旦带上 --prerelease,
# 这个 URL 永远看不到它,等于发了等于没发。versionName 里的 "-beta" 只是给人看的版本号,
# 不是 GitHub 的 prerelease 标记,两件事故意分开。
if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "==> dry-run:跳过 git tag / git push / gh release create"
else
  git tag "$TAG"
  git push origin "$TAG"
  gh release create "$TAG" "$APK_DIST" "dist/latest.json" \
    --title "UnitedU ${VERSION}" \
    --notes-file "$NOTES_FILE" \
    --latest
  echo "==> GitHub Release ${TAG} 已发布"
fi

# ---- 腾讯云 COS(可选)----
#
# 只有 coscli 在 PATH 上、且 COS_BUCKET / COS_REGION 两个环境变量都给了才做;
# 三者缺一律打印一句跳过、照常以 0 退出——本机没装 coscli 时这是预期路径,不是失败。
# 凭据不经过这两个环境变量:coscli 自己认 ~/.cos.yaml,本脚本从不读取、不打印任何密钥。
if command -v coscli >/dev/null 2>&1 && [[ -n "${COS_BUCKET:-}" ]] && [[ -n "${COS_REGION:-}" ]]; then
  COS_APK_URL="https://${COS_BUCKET}.cos.${COS_REGION}.myqcloud.com/unitedu/${APK_NAME}"
  gen_manifest "$COS_APK_URL" "dist/latest-cos.json"
  echo "==> dist/latest-cos.json 已生成(apkUrl = ${COS_APK_URL})"

  if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "==> dry-run:跳过 coscli 上传"
  else
    coscli cp "$APK_DIST" "cos://${COS_BUCKET}/unitedu/${APK_NAME}"
    coscli cp "dist/latest-cos.json" "cos://${COS_BUCKET}/unitedu/latest.json"
    echo "==> COS 上传完成:cos://${COS_BUCKET}/unitedu/"
  fi
else
  echo "COS 未配置,已跳过"
fi

echo "==> 完成。dist/ 下的产物:"
ls -la dist/
