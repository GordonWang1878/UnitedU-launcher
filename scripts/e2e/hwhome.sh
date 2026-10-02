#!/bin/bash
# 往模拟器发一下**硬件路径**的 HOME 键:用系统自带的 /system/bin/hid 经 uhid 造一个 USB 消费类遥控,发 AC Home。
# `adb shell input keyevent 3` 是注入事件,不经过无障碍按键过滤器(AOSP InputDispatcher::injectInputEvent 不调 filterInputEvent),
# `adb emu event send` / `sendevent` 在这两台 AVD 上到不了输入层——测主页键接管(R162)只能用这个。
# 用法:scripts/e2e/hwhome.sh [serial](缺省 emulator-5554)
set -euo pipefail
source "$(dirname "$0")/../env.sh"
S=${1:-emulator-5554}
adb -s "$S" push "$(dirname "$0")/hid-home.json" /data/local/tmp/hid-home.json >/dev/null
if ! adb -s "$S" shell 'test -x /system/bin/hid'; then
  echo "hwhome: /system/bin/hid missing on $S" >&2
  exit 1
fi
# hid 自己的报错留在 stderr(不吞),失败就让脚本非零退出——不然「没按到」会被当成「按了没反应」。
if ! adb -s "$S" shell hid /data/local/tmp/hid-home.json >/dev/null; then
  echo "hwhome: hid failed on $S" >&2
  exit 1
fi
