#!/usr/bin/env bash
# 在 unitedu-tv-2 / -3 上搭测量环境:装当前构建、设 HOME、装 19 个占位应用、推测试布局(5 行 10/5/4/4/4 张)与设置、编译成 speed。
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
for _ in 1 2 3 4 5; do
  adb -s "$S" shell cmd package compile -m speed -f $P >/dev/null; sleep 3
  adb -s "$S" shell dumpsys package dexopt | grep -A3 "\[$P\]" | grep -q "status=speed" && break
done
adb -s "$S" shell dumpsys package dexopt | grep -A3 "\[$P\]"
