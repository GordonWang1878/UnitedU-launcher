#!/usr/bin/env bash
# 在 unitedu-tv-2 / -3 上搭测量环境:装当前构建、设 HOME、装 19 个占位应用、推测试布局(5 行 10/5/4/4/4 张)与设置、编译成 speed。
# 基线(before)于 2026-10-10 在 dexopt=speed-profile、宿主负载 ≈12 下录;Task 11 复测要在同样的编译状态与相近负载下跑。
# 只对模拟器用。S 默认 emulator-5570(unitedu-tv-2)。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../../.." && pwd)"
source "$ROOT/scripts/env.sh"
S="${S:-emulator-5570}"; P=com.uniteduone.launcher; F=/sdcard/Android/data/$P/files
adb -s "$S" install -r "$ROOT/app/build/outputs/apk/release/app-release.apk"
adb -s "$S" shell cmd package set-home-activity $P/.MainActivity
DEV="$S" python3 "$ROOT/scripts/e2e/fixtures.py"
adb -s "$S" install -r "${E2E_OUT:-/tmp/unitedu-e2e}/apks/test.dummy.app18.apk"
adb -s "$S" shell am start -n $P/.MainActivity >/dev/null; sleep 5
adb -s "$S" shell am force-stop $P
adb -s "$S" push "$HERE/layout.json" "$F/layout.json"
adb -s "$S" push "$HERE/settings.json" "$F/settings.json"
adb -s "$S" shell settings put global animator_duration_scale 1.0
# 2026-10-10 Task 11 实测:刚装完、包还是 stopped 时 `compile -m speed -f` 连跑 7 次都读回 verify(dex2oat 收到的是 --compiler-filter=verify);
# 先把应用拉起来跑几秒再编,第一次就读回 speed-profile。拉起时布局 / 设置已推好,之后照常 force-stop。
adb -s "$S" shell am start -n $P/.MainActivity >/dev/null; sleep 8
# 判据:读回 status=speed 或 status=speed-profile 都算编好(这台 ART 上 -m speed 常读回 speed-profile,见仓库 CLAUDE.md);status=verify 才是没编上。
compiled=0
for _ in 1 2 3 4 5; do
  adb -s "$S" shell cmd package compile -m speed -f $P >/dev/null; sleep 3
  if adb -s "$S" shell dumpsys package dexopt | grep -A3 "\[$P\]" | grep -Eq "status=(speed|speed-profile)\]"; then compiled=1; break; fi
done
adb -s "$S" shell dumpsys package dexopt | grep -A3 "\[$P\]"
adb -s "$S" shell am force-stop $P
if [ "$compiled" != 1 ]; then
  echo "WARNING: $P 仍是 verify(未编译),测量数据不可用;隔几秒重跑 setup.sh" >&2
  exit 1
fi
