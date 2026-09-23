#!/usr/bin/env bash
# 把 gtv 线(包名 com.uniteduone.launcher.gtv)名下的用户数据搬到 1.0 正式包 com.uniteduone.launcher。
# 2026-09-23 Gordon 裁定:1.0 = gtv 线,回到正式包名,数据搬过去。
#
# 只做:备份两边 → 装正式包 → 复制数据 → 核对。**不卸载 gtv 包、不改桌面角色、不改系统屏保**:
# 卸载会删 gtv 名下数据,须 Gordon 当次确认;桌面角色与系统屏保由 Gordon 在系统设置里切
# (见 memory a95l-default-home-stays-tvhome、CLAUDE.md「真机的屏保由 Gordon 在系统设置里开」)。
# 全程不发按键(memory no-keys-to-tv-while-owner-uses)。
#
# 用法:scripts/migrate-gtv-to-main.sh <adb 序列号> <正式包 APK 路径>
#   例:scripts/migrate-gtv-to-main.sh 192.168.1.22:37xxx app/build/outputs/apk/release/app-release.apk
set -euo pipefail
S="${1:?adb 序列号}"; APK="${2:?正式包 APK}"
GTV=com.uniteduone.launcher.gtv; MAIN=com.uniteduone.launcher
SRC=/sdcard/Android/data/$GTV/files; DST=/sdcard/Android/data/$MAIN/files
BK="$HOME/unitedu-backup/$(date +%Y%m%d-%H%M%S)"
adb() { command adb -s "$S" "$@"; }

echo "== 0. 核对 APK 包名"
AAPT=$(ls -d "$HOME"/Library/Android/sdk/build-tools/*/aapt2 | tail -1)
PKG=$("$AAPT" dump badging "$APK" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
[ "$PKG" = "$MAIN" ] || { echo "APK 包名是 $PKG,不是 $MAIN,停"; exit 1; }

echo "== 1. 备份两边到 $BK"
mkdir -p "$BK/gtv" "$BK/main"
adb pull "$SRC/." "$BK/gtv/" >/dev/null
adb pull "$DST/." "$BK/main/" >/dev/null 2>&1 || echo "   (正式包目录为空或不存在)"
( cd "$BK" && find . -type f | sort | xargs shasum -a 256 > MANIFEST.sha256 )
echo "   gtv 名下 $(find "$BK/gtv" -type f | wc -l | tr -d ' ') 个文件"

echo "== 2. 装正式包(覆盖安装,签名须与已装的一致)"
adb install -r "$APK" | tail -1

echo "== 3. 停两个包,清空正式包数据目录,复制"
adb shell am force-stop $MAIN; adb shell am force-stop $GTV
adb shell "mkdir -p $DST && rm -rf $DST/* && cp -r $SRC/. $DST/"

echo "== 4. 核对:逐文件 sha256 两边一致"
diff <(adb shell "cd $SRC && find . -type f | sort | xargs sha256sum") \
     <(adb shell "cd $DST && find . -type f | sort | xargs sha256sum") \
  && echo "   一致" || { echo "   不一致,停(备份在 $BK)"; exit 1; }

echo "== 完成。备份:$BK"
echo "   接下来由 Gordon:系统设置里把「默认桌面」与「屏幕保护程序」改选 UnitedU(正式包);确认无误后再决定是否卸载 $GTV"
