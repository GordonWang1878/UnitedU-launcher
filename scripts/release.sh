#!/usr/bin/env bash
set -euo pipefail

# scripts/release.sh <version> [--dry-run] [--notes "文本"]
#
# 构建 release APK、生成两份 latest.json(spec §7.2/§7.3)、发布到 GitHub Release,
# 并在配置了 Cloudflare R2 时把同一份 APK 与一份 apkUrl 指向 R2 的 latest.json 也发过去。
# 由 Gordon 本机运行;发布是不可逆动作,脚本本身不做任何交互确认——运行前自己确认版本号对。
#
#   <version>   稳定版 1.2.3,或 Beta 版 1.2.3-beta.4(其余格式中止);必须与本次构建出的 APK 里的 versionName 完全一致
#   --dry-run   只构建 + 在 dist/ 下生成产物并打印将要做的事,不建 tag、不 push、
#               不 gh release、不 wrangler 上传——没有任何网络写入
#   --notes     发布说明。来源只有两个:这个参数,或事先写好的 dist/notes-<version>.txt
#               (**每个版本一份**,参数优先)。两者都没有时正式发布直接中止,不会拿默认文案发出去;
#               --dry-run 才退回一句默认文案并警告。脚本从不把说明写回任何文件——旧版把解析结果
#               回写进共用的 dist/notes.txt,下一次发布没给说明就会悄悄沿用上一版的(M7 终审 I3)。
#               这份原文原样进 GitHub Release 描述;写进 latest.json 的 notes 字段时
#               会截到 parseLatest()(UpdateChecker.kt)的 200 字上限,两处不是同一件事。
#               **双语**(2026-10-03 Gordon:用户分国内与海外两拨):说明文件写中文一段、英文一段。
#               电视上「检查更新」只显示 latest.json 里那 200 字,双语长文会被截在半截;所以可以另写
#               dist/notes-<version>.app.txt(中英各一句短话),有它时 latest.json 用它,没有才退回完整说明。
#
# **两种发布模式按版本号判定**(release channels):
#   稳定版  X.Y.Z          GitHub `--latest`,R2 写 latest.json——与此前完全一样。
#   Beta 版 X.Y.Z-beta.N   GitHub `--prerelease`(不碰 releases/latest,稳定通道看不到它),R2 写 beta.json;
#                          同时把「最新稳定 tag 的源码 + versionCode = beta + 1」再构建一个**回退包**,
#                          写进 rollback.json:Beta 用户选「回到稳定版」时,系统只肯装 versionCode 更高的包,
#                          所以回退包必须比 Beta 的号大;versionName 仍是稳定版号(用户看到的是稳定版)。
#                          beta.json / rollback.json 与回退包挂在固定 tag channel-beta 上,每次覆盖。
# 每次发布前还做 versionCode 单调检查:新包的号必须大于 R2 上 latest/beta/rollback 三份清单里已发布的最大值,
# 否则没有任何通道的用户收得到它。
#
# 构建带 -PrequireReleaseKey=true(没有 ~/.unitedu/release.jks 就构建失败,不回落 debug 签名),
# 构建完再用 apksigner 核对 APK 的签名证书就是 release 证书(M7 终审 I4)。
# 凭据只有 wrangler 自己存的 OAuth 令牌;本脚本、本仓库都不存任何密钥(release 证书的摘要是公开信息)。

usage() {
  cat <<'EOF'
用法: scripts/release.sh <version> [--dry-run] [--notes "发布说明"]

  <version>   稳定版 1.2.3 或 Beta 版 1.2.3-beta.4 —— 要与本次构建出的 APK versionName 一致,不一致就中止
              (Beta 模式额外构建「回退包」并发 beta.json / rollback.json,不动 latest.json)
  --dry-run   只构建 + 生成 dist/ 下的产物,不发布(不建 tag、不 push、不 gh release、不 wrangler)
  --notes     发布说明文本;省略则读 dist/notes-<version>.txt。两者都没有:正式发布中止,
              --dry-run 用一句默认文案并警告
  -h, --help  显示本说明
EOF
}

# tag 卡在「本地/远程有、release 没发出去」这个半成品状态时打印的恢复步骤。
# 三处会用到:①本地已有同名 tag;②origin 上已有同名 tag(本地没有);③tag 推上去之后
# git push 或 gh release create 失败——三种情况的收场动作是同一套,所以只写一份。
# 用到的 $TAG 是外层的全局变量,这个脚本没有别的地方会重新赋值给它。
print_tag_recovery_hint() {
  cat >&2 <<EOF
恢复步骤:
  1. 看一眼是不是已经发出去一半了:gh release view ${TAG}
  2. 有本地 tag 就删掉:git tag -d ${TAG}
  3. 有远程 tag 就删掉:git push origin :refs/tags/${TAG}
  4. 确认干净之后再重新跑一次 scripts/release.sh ${VERSION}
EOF
}

# >>> release-checks
# 这一段只定义常量和函数、不执行任何动作,可以被单独 source 进测试脚本(sed 按这两行标记截取)。

# release 证书(~/.unitedu/release.jks,alias unitedu)的 SHA-256 摘要。**公开信息**:任何人拿到
# 已发布的 APK 都能用 apksigner 读出来,不是密钥;脚本全程只读 APK 里的证书,不碰 keystore 与密码。
# 取值来源:2026-09-17 在 Gordon 本机用 release.jks 构建的 app-release.apk(Gradle 输出里没有
# debug 回落警告),apksigner verify --print-certs 读出的 Signer #1 摘要(DN: CN=UnitedU, O=UnitedU, C=CN)。
RELEASE_CERT_SHA256="bdec592357472edb3727289fae02722c8974562f94b3da93b3a29c4b5593199b"

# 决定这次的发布说明,写进全局 NOTES_RAW。读 VERSION / DRY_RUN / NOTES_GIVEN / NOTES_ARG,
# 路径相对当前目录(release.sh 里已 cd 到仓库根)。正式发布拿不到说明时返回 1。
resolve_notes() {
  local per_version="dist/notes-${VERSION}.txt"
  NOTES_RAW=""
  if [[ "$NOTES_GIVEN" -eq 1 ]]; then
    NOTES_RAW="$NOTES_ARG"
    echo "==> 发布说明:取自 --notes"
  elif [[ -f "$per_version" ]]; then
    NOTES_RAW="$(cat "$per_version")"
    if [[ -z "${NOTES_RAW//[[:space:]]/}" ]]; then
      echo "${per_version} 是空的,不算数" >&2
      NOTES_RAW=""
    else
      echo "==> 发布说明:取自 ${per_version}"
    fi
  fi
  if [[ -f "dist/notes.txt" ]]; then
    echo "注意:dist/notes.txt 不再被读取(那是旧版脚本回写的某一次说明);只认 --notes 或 ${per_version}" >&2
  fi
  if [[ -n "$NOTES_RAW" ]]; then
    return 0
  fi
  if [[ "$DRY_RUN" -eq 1 ]]; then
    NOTES_RAW="UnitedU ${VERSION} 发布。"
    echo "警告:没有发布说明,dry-run 先用默认文案「${NOTES_RAW}」;正式发布会在这一步中止" >&2
    return 0
  fi
  cat >&2 <<EOF
没有发布说明,正式发布不会拿默认文案发出去(GitHub Release 与 latest.json 里会只剩一句空话)。二选一:
  1. scripts/release.sh ${VERSION} --notes "这一版改了什么"
  2. 把说明写进 ${per_version}(每个版本一份;dist/ 不进仓库),再重跑 scripts/release.sh ${VERSION}
EOF
  return 1
}

# 核对 APK 的签名证书就是 release 证书。需要 ANDROID_HOME(scripts/env.sh 设置)。
# 不一致就返回 1:发出去的包签名与已装的 beta 不同,用户点更新会被系统以 WRONG_SIGNER 拒绝,只能卸载重装。
check_signer() {
  local apk="$1"
  local apksigner="${ANDROID_HOME:-}/build-tools/35.0.0/apksigner"
  if [[ -z "${ANDROID_HOME:-}" || ! -x "$apksigner" ]]; then
    echo "apksigner 不存在或不可执行:${apksigner}(先 source scripts/env.sh)" >&2
    return 1
  fi
  local out digests count
  if ! out="$("$apksigner" verify --print-certs "$apk" 2>&1)"; then
    echo "apksigner verify 没通过(APK 没签名或签名已损坏):" >&2
    printf '%s\n' "$out" >&2
    return 1
  fi
  digests="$(printf '%s\n' "$out" | sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: \([0-9a-f]\{64\}\)$/\1/p')"
  count="$(grep -c . <<<"$digests" || true)"
  if [[ "$count" -ne 1 ]]; then
    echo "期望恰好一个签名者,apksigner 读出 ${count} 个:" >&2
    printf '%s\n' "$out" >&2
    return 1
  fi
  if [[ "$digests" != "$RELEASE_CERT_SHA256" ]]; then
    echo "签名证书不是 UnitedU 的 release 证书——已装 beta 的用户会因签名不一致装不上这个包(WRONG_SIGNER),只能卸载重装:" >&2
    echo "  APK 证书 SHA-256:     ${digests}" >&2
    echo "  release 证书 SHA-256: ${RELEASE_CERT_SHA256}" >&2
    sed -n 's/^Signer #1 certificate DN: /  APK 证书 DN:         /p' <<<"$out" >&2
    if grep -q 'CN=Android Debug' <<<"$out"; then
      echo "  这是 debug keystore 的证书:~/.unitedu/release.jks 或 release.properties 缺失时,不带 -PrequireReleaseKey=true 的构建会回落到 debug 签名。" >&2
    fi
    return 1
  fi
  echo "==> 签名证书 = release 证书(SHA-256 ${digests})"
}

# 已发布的三份清单里最大的 versionCode(取不到的当 0)。新包必须比它大,否则谁都收不到。
# 只读网络(GET),dry-run 也照常查。
MANIFEST_BASE="${R2_BASE_URL:-https://dl.uniteduone.com}"
published_max_code() {
  local max=0 c name
  for name in latest beta rollback; do
    c="$(curl -fsSL --max-time 10 "${MANIFEST_BASE%/}/unitedu/${name}.json" 2>/dev/null \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["versionCode"])' 2>/dev/null || echo 0)"
    [[ "$c" =~ ^[0-9]+$ ]] || c=0
    (( c > max )) && max=$c
  done
  echo "$max"
}
check_code_monotonic() {
  local code="$1" max
  max="$(published_max_code)"
  if (( code <= max )); then
    echo "versionCode ${code} 不比已发布的最大值 ${max} 大——用户收不到它。先在 app/build.gradle.kts 把 versionCode 改成 $((max + 1)) 或更大" >&2
    return 1
  fi
  echo "==> versionCode ${code} > 已发布最大值 ${max}"
}
# <<< release-checks

DRY_RUN=0
VERSION=""
NOTES_ARG=""
NOTES_GIVEN=0
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
      NOTES_GIVEN=1
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
# 显式给了 --notes 却是空白:多半是变量没展开,当场报出来,不要悄悄落到别的来源上。
if [[ "$NOTES_GIVEN" -eq 1 && -z "${NOTES_ARG//[[:space:]]/}" ]]; then
  echo "--notes 给的是空白文本" >&2
  exit 1
fi
# 版本号要能安全地拼进 git tag 与文件名:只认数字、字母、点、连字符。
if ! [[ "$VERSION" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*$ ]]; then
  echo "版本号里有不安全的字符:$VERSION(只能用字母数字、点、连字符)" >&2
  exit 1
fi

# 模式按版本号判定(见文件头)。上面的字符集检查已放过 Beta 形态,这里收紧到两种合法格式。
if [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  CHANNEL=stable
elif [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+-beta\.[0-9]+$ ]]; then
  CHANNEL=beta
else
  echo "版本号格式不认识:${VERSION}(稳定 1.2.3,Beta 1.2.3-beta.4)" >&2
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
  print_tag_recovery_hint
  exit 1
fi

# ---- 发布前预检查:gh 登录了没、origin 上有没有已经躺着一个同名 tag ----
#
# 必须在 git tag / git push / gh release create 这一串操作**之前**跑,而不是跑到中间才发现:
# 这三步不是一个事务——旧版本先 tag、再 push、再发 release,任何一步在半路失败都会留下
# 「tag 已经在 origin 上,release 还没发出去」的半成品状态,下次重跑会被上面「tag 已存在」
# 拦住,却没有任何提示该怎么收场。把两项检查挪到建 tag 之前,就能在还没有任何副作用的时候
# 发现问题。放在构建之前而不是构建之后:两项检查都很快,没必要等一次 assembleRelease 才失败。
#
# --dry-run 下只报告、不拦:dry-run 的一个用途就是在没登录 gh、甚至没联网的机器上
# 也能跑一遍看流程对不对,不应该因为这两项跟「真的要发布」相关的检查而跑不完。
PREFLIGHT_OK=1

if gh auth status >/dev/null 2>&1; then
  echo "==> gh auth status:已登录"
else
  echo "gh 未登录(gh auth status 失败)—— 正式发布前需要先 gh auth login" >&2
  PREFLIGHT_OK=0
fi

REMOTE_TAG_OUT=""
if REMOTE_TAG_OUT="$(git ls-remote --tags origin "refs/tags/${TAG}" 2>&1)"; then
  if [[ -n "$REMOTE_TAG_OUT" ]]; then
    echo "origin 上已经有 ${TAG} 了(本地没有,大概率是上一次发布中途失败留下的):" >&2
    echo "$REMOTE_TAG_OUT" >&2
    PREFLIGHT_OK=0
  else
    echo "==> origin 上没有 ${TAG}"
  fi
else
  echo "git ls-remote origin 失败(连不上远程?):$REMOTE_TAG_OUT" >&2
  PREFLIGHT_OK=0
fi

if [[ "$PREFLIGHT_OK" -eq 0 ]]; then
  if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "警告:预检查没通过(dry-run 继续;正式发布前必须先解决上面的问题)" >&2
  else
    echo "预检查没通过,先处理上面的问题再发布。" >&2
    print_tag_recovery_hint
    exit 1
  fi
fi

# ---- 发布说明:--notes > dist/notes-<version>.txt;正式发布两者都没有就中止 ----
# 放在构建之前:这一步不依赖构建产物,缺说明时不必先白等一次 assembleRelease。
if ! resolve_notes; then
  exit 1
fi

# 写进 latest.json 的短说明(见文件头「双语」一条):有 dist/notes-<version>.app.txt 就用它。
NOTES_APP="$NOTES_RAW"
if [[ -f "dist/notes-${VERSION}.app.txt" ]] && [[ -n "$(tr -d "[:space:]" < "dist/notes-${VERSION}.app.txt")" ]]; then
  NOTES_APP="$(cat "dist/notes-${VERSION}.app.txt")"
  echo "==> latest.json 的短说明:取自 dist/notes-${VERSION}.app.txt"
fi

# ---- 构建 ----
# -PrequireReleaseKey=true:缺 release 密钥时构建直接失败,不回落 debug keystore(app/build.gradle.kts)。
echo "==> source scripts/env.sh && gradle --no-daemon assembleRelease -PrequireReleaseKey=true"
# shellcheck source=scripts/env.sh
source scripts/env.sh
gradle --no-daemon assembleRelease -PrequireReleaseKey=true

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

# ---- versionCode 单调检查:必须大于已发布的最大值 ----
# dry-run 只警告(它常用来在改号之前试跑);正式发布直接中止。
if ! check_code_monotonic "$APK_VERSION_CODE"; then
  if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "警告:dry-run 继续" >&2
  else
    exit 1
  fi
fi

# ---- 签名证书:必须是 release 证书(M7 终审 I4)----
# Gradle 在缺密钥时会回落 debug 签名(给没有密钥的贡献者用);上面的构建已带 -PrequireReleaseKey=true,
# 这里再从 APK 本身核一遍——与版本号同一个原则:不信构建配置,信 APK 实际带的是什么。
if ! check_signer "$APK_DIST"; then
  exit 1
fi

# ---- sha256(与 UpdateChecker.kt 的 sha256Hex 同算法,64 位小写 hex)----
SHA256="$(shasum -a 256 "$APK_DIST" | awk '{print $1}')"

# ---- 回退包(只在 Beta 模式)----
#
# 用户在 Beta 通道想回稳定版:系统不允许降级安装(versionCode 必须更高),所以发一个
# 「最新稳定 tag 的源码 + versionCode = beta + 1」的包。versionName 沿用稳定版号。
# 在临时 git worktree 里构建,不动当前工作区;tag 里的 build.gradle.kts 须支持 -PversionCodeOverride
# (v1.0.3 起有)。无论构建成败都清掉 worktree,不留残骸。
if [[ "$CHANNEL" == beta ]]; then
  # 只认 vX.Y.Z(不带 -beta),按版本排序取最大。
  STABLE_TAG="$(git tag --list 'v[0-9]*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1 || true)"
  [[ -n "$STABLE_TAG" ]] || { echo "找不到稳定版 tag" >&2; exit 1; }
  RB_CODE=$((APK_VERSION_CODE + 1))
  RB_TMP="$(mktemp -d)"
  RB_DIR="${RB_TMP}/rollback"
  # 中途被打断(Ctrl-C、kill)也要清掉临时 worktree 与目录,不在 git 里留残骸。
  rb_cleanup() { git worktree remove --force "$RB_DIR" 2>/dev/null || true; rm -rf "$RB_TMP"; }
  trap rb_cleanup EXIT INT TERM
  echo "==> 构建回退包:${STABLE_TAG} 源码,versionCode ${RB_CODE}"
  git worktree add --detach "$RB_DIR" "$STABLE_TAG"
  RB_OK=1
  ( cd "$RB_DIR" && source scripts/env.sh && gradle --no-daemon assembleRelease -PrequireReleaseKey=true -PversionCodeOverride="$RB_CODE" ) || RB_OK=0
  RB_NAME="unitedu-${STABLE_TAG#v}-rollback-${RB_CODE}.apk"
  if [[ "$RB_OK" -eq 1 ]]; then
    cp "$RB_DIR/app/build/outputs/apk/release/app-release.apk" "dist/$RB_NAME" || RB_OK=0
  fi
  rb_cleanup
  trap - EXIT INT TERM
  [[ "$RB_OK" -eq 1 ]] || { echo "回退包构建失败" >&2; exit 1; }
  check_signer "dist/$RB_NAME" || exit 1
  RB_SHA="$(shasum -a 256 "dist/$RB_NAME" | awk '{print $1}')"
  RB_BADGING="$("$AAPT2" dump badging "dist/$RB_NAME")"
  RB_VNAME="$(printf '%s\n' "$RB_BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
  RB_VCODE="$(printf '%s\n' "$RB_BADGING" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)"
  if [[ "$RB_VCODE" != "$RB_CODE" || "$RB_VNAME" != "${STABLE_TAG#v}" ]]; then
    echo "回退包身份不对:APK 里是 ${RB_VNAME}/${RB_VCODE},期望 ${STABLE_TAG#v}/${RB_CODE}" >&2
    exit 1
  fi
  RB_NOTES="回到稳定版 / Back to stable"
fi

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
  # 第 3~6 个参数可选:版本号 / 版本名 / sha256 / 说明,缺省用主包的(回退包清单会全部传入)。
  local apk_url="$1" out="$2"
  local code="${3:-$APK_VERSION_CODE}" name="${4:-$APK_VERSION_NAME}" sha="${5:-$SHA256}" notes="${6:-$NOTES_APP}"
  python3 - "$code" "$name" "$notes" "$apk_url" "$sha" "$MIN_SDK" "$out" <<'PY'
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
if [[ "$CHANNEL" == stable ]]; then
  MANIFEST_NAME="latest"
else
  MANIFEST_NAME="beta"
  GITHUB_RB_URL="https://github.com/GordonWang1878/UnitedU-launcher/releases/download/channel-beta/${RB_NAME}"
  gen_manifest "$GITHUB_RB_URL" "dist/rollback.json" "$RB_CODE" "$RB_VNAME" "$RB_SHA" "$RB_NOTES"
  echo "==> dist/rollback.json 已生成(versionCode ${RB_CODE},apkUrl = ${GITHUB_RB_URL})"
fi
gen_manifest "$GITHUB_APK_URL" "dist/${MANIFEST_NAME}.json"
echo "==> dist/${MANIFEST_NAME}.json 已生成(apkUrl = ${GITHUB_APK_URL})"

# ---- GitHub Release ----
#
# 稳定版发成普通(latest)release(--latest):App 内检查更新的 GitHub 兜底通道读
# releases/latest/download/latest.json,GitHub 的 "latest" 是「最新一个不是 prerelease 也不是 draft 的 release」。
# Beta 版反过来必须带 --prerelease,不碰 releases/latest——否则稳定通道的用户会被推 Beta。
# Beta 通道的兜底读固定 tag channel-beta 下的 beta.json / rollback.json(每次覆盖)。
if [[ "$DRY_RUN" -eq 1 ]]; then
  if [[ "$CHANNEL" == stable ]]; then
    echo "==> dry-run:将执行 gh release create ${TAG} --latest(附 latest.json);跳过 git tag / git push / gh release create"
  else
    echo "==> dry-run:将执行 gh release create ${TAG} --prerelease,并 gh release upload channel-beta ${RB_NAME} → rollback.json → beta.json 三次分开上传、--clobber(不碰 releases/latest);跳过 git tag / git push / gh release create"
  fi
else
  git tag "$TAG"
  # push 与 release create 各自用 if 包一层(set -e 下,作为 if 的条件失败不会直接退出脚本):
  # 失败时要先打印恢复提示再退出,而不是让 set -e 直接终止、把「tag 已经推上去了」这件事
  # 悄悄留给下一次重跑去发现。
  if ! git push origin "$TAG"; then
    echo "git push origin ${TAG} 失败,tag 可能没有(完全)推上去。" >&2
    print_tag_recovery_hint
    exit 1
  fi
  # 说明直接走 --notes(resolve_notes 已保证非空),不经任何中间文件。
  if [[ "$CHANNEL" == stable ]]; then
    RELEASE_FLAG=(--latest)
    RELEASE_FILES=("$APK_DIST" "dist/latest.json")
  else
    RELEASE_FLAG=(--prerelease)
    RELEASE_FILES=("$APK_DIST")
  fi
  if ! gh release create "$TAG" "${RELEASE_FILES[@]}" \
    --title "UnitedU ${VERSION}" \
    --notes "$NOTES_RAW" \
    "${RELEASE_FLAG[@]}"; then
    echo "gh release create 失败,但 tag 已经推到 origin 了——不是发布成功。" >&2
    print_tag_recovery_hint
    exit 1
  fi
  echo "==> GitHub Release ${TAG} 已发布(${CHANNEL})"
  if [[ "$CHANNEL" == beta ]]; then
    # 固定 tag channel-beta:放 beta.json / rollback.json / 回退包,每次覆盖。第一次不存在就建。
    # 此时 tag 已推、Beta release 已建:下面任何一步失败都要说清楚留下了什么状态、怎么手工补完。
    channel_beta_fail() {
      cat >&2 <<HINT
channel-beta 更新失败:${TAG} 的 Beta release 已发出,但 channel-beta 上的清单可能不完整,R2 也还没写。
手工补完(顺序不能换:回退包 → rollback.json → beta.json):
  gh release view channel-beta >/dev/null 2>&1 || gh release create channel-beta --prerelease --title "Beta channel" --notes "Beta channel manifests"
  gh release upload channel-beta "dist/${RB_NAME}" --clobber
  gh release upload channel-beta "dist/rollback.json" --clobber
  gh release upload channel-beta "dist/beta.json" --clobber
然后按下面 R2 一段的 wrangler r2 object put 手工补传(同样先 APK、回退包、rollback.json,最后 beta.json)。
不要重跑 scripts/release.sh ${VERSION}:tag ${TAG} 已存在会被拦下。
HINT
      exit 1
    }
    if ! gh release view channel-beta >/dev/null 2>&1; then
      gh release create channel-beta --prerelease --title "Beta channel" \
        --notes "Beta 通道清单(自动维护)/ Beta channel manifests (auto-maintained)" || channel_beta_fail
    fi
    # 分三次、按序传:一次传三个文件不保证顺序,beta.json 可能先于回退包露出(与 R2 同序)。
    gh release upload channel-beta "dist/$RB_NAME" --clobber || channel_beta_fail
    gh release upload channel-beta "dist/rollback.json" --clobber || channel_beta_fail
    gh release upload channel-beta "dist/beta.json" --clobber || channel_beta_fail
    echo "==> channel-beta 已更新(beta.json / rollback.json / ${RB_NAME})"
  fi
fi

# ---- Cloudflare R2(可选;国内直连走的通道)----
#
# 只有 wrangler 在 PATH 上、且 R2_BUCKET / R2_BASE_URL 两个环境变量都给了才做;
# 三者缺一律打印一句跳过、照常以 0 退出——本机没装 wrangler 时这是预期路径,不是失败。
#   R2_BUCKET    桶名(现为 unitedu)
#   R2_BASE_URL  桶绑定的自有域名,不带结尾斜杠(现为 https://dl.uniteduone.com)。**必须是自定义域名**:
#                R2 自带的 *.r2.dev 子域在大陆被墙,写进 latest.json 的 apkUrl 国内没人下得了。
# 凭据不经过这两个环境变量:wrangler 自己认 `wrangler login` 存下的 OAuth 令牌
# (~/Library/Preferences/.wrangler/config/),本脚本从不读取、不打印任何密钥。
# 为什么不是腾讯云 COS(1.0.1 用过一版):COS 默认域名禁止分发 .apk——匿名 GET 回 403
# DownloadForbidden(2026-10-03 实测,按对象名后缀拦),绑自定义域名又要 ICP 备案;
# Cloudflare 绑自有域名不用备案,uniteduone.com 的 DNS 本来就托管在 Cloudflare。
if command -v wrangler >/dev/null 2>&1 && [[ -n "${R2_BUCKET:-}" ]] && [[ -n "${R2_BASE_URL:-}" ]]; then
  R2_APK_URL="${R2_BASE_URL%/}/unitedu/${APK_NAME}"
  gen_manifest "$R2_APK_URL" "dist/${MANIFEST_NAME}-r2.json"
  echo "==> dist/${MANIFEST_NAME}-r2.json 已生成(apkUrl = ${R2_APK_URL})"
  if [[ "$CHANNEL" == beta ]]; then
    R2_RB_URL="${R2_BASE_URL%/}/unitedu/${RB_NAME}"
    gen_manifest "$R2_RB_URL" "dist/rollback-r2.json" "$RB_CODE" "$RB_VNAME" "$RB_SHA" "$RB_NOTES"
    echo "==> dist/rollback-r2.json 已生成(apkUrl = ${R2_RB_URL})"
  fi

  if [[ "$DRY_RUN" -eq 1 ]]; then
    if [[ "$CHANNEL" == stable ]]; then
      echo "==> dry-run:跳过 wrangler r2 object put(将写 ${APK_NAME} 与 latest.json)"
    else
      echo "==> dry-run:跳过 wrangler r2 object put(将写 ${APK_NAME}、${RB_NAME}、beta.json、rollback.json;不写 latest.json)"
    fi
  else
    # 自定义域名前面是 Cloudflare 的边缘缓存:APK 文件名带版本号,缓存一小时无妨;latest.json 每次
    # 发版都换内容、文件名不变,必须 no-cache,否则电视查到的可能还是上一版的清单。
    # -y:跳过 wrangler 新加的 Basin Catalog 交互确认,脚本里不能停下来等人。
    wrangler r2 object put "${R2_BUCKET}/unitedu/${APK_NAME}" --remote -y --file "$APK_DIST" \
      --content-type application/vnd.android.package-archive \
      --cache-control "public, max-age=3600"
    if [[ "$CHANNEL" == beta ]]; then
      wrangler r2 object put "${R2_BUCKET}/unitedu/${RB_NAME}" --remote -y --file "dist/${RB_NAME}" \
        --content-type application/vnd.android.package-archive \
        --cache-control "public, max-age=3600"
      # 先传 rollback 再传 beta:beta.json 一出现,用户就可能点「回到稳定版」,回退清单要已就位。
      wrangler r2 object put "${R2_BUCKET}/unitedu/rollback.json" --remote -y --file "dist/rollback-r2.json" \
        --content-type application/json \
        --cache-control "no-cache"
    fi
    wrangler r2 object put "${R2_BUCKET}/unitedu/${MANIFEST_NAME}.json" --remote -y --file "dist/${MANIFEST_NAME}-r2.json" \
      --content-type application/json \
      --cache-control "no-cache"
    echo "==> R2 上传完成:${R2_BASE_URL%/}/unitedu/"
  fi
else
  echo "R2 未配置,已跳过"
fi

echo "==> 完成。dist/ 下的产物:"
ls -la dist/
