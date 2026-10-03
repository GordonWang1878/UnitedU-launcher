#!/bin/bash
# 往真机(A95L)装包:等首页在前台、清掉紧挨首页的索尼输入源任务、装、编译、核对 md5。不按任何键。
#
#   scripts/tv-install.sh [apk]      # 默认 app/build/outputs/apk/release/app-release.apk
#
# 为什么要等、要清(详见下面两条):
# - 装包会删掉首页任务。别的应用在前台时装,用户退出那个应用会落到任务栈里的下一个(常是 HDMI);
#   所以只在首页 MainActivity 连续在前台约 30 s 时装(连续 30 s 防止他只是经过首页去开别的应用)。
#   屏保在前台不算:它可能盖在暂停的别的应用上,装包把它杀掉,他回来看到的是首页而不是暂停的视频。
# - 首页在前台也不够:换包那约 2 s 里系统会恢复排在首页下面的任务;若那是索尼输入源应用 tvlin,
#   它调回上次的 HDMI、发 CEC 唤醒那一路的设备,设备几秒后把画面抢走(R151 的拉回挡不住)。
#   Gordon 2026-10-01 授权:这种情况先 `am stack remove` 清掉它的任务(= 在最近任务里划掉,不改设置)。
source "$(dirname "$0")/env.sh" >/dev/null 2>&1
APK=${1:-"$(dirname "$0")/../app/build/outputs/apk/release/app-release.apk"}
PKG=com.uniteduone.launcher
[ -f "$APK" ] || { echo "没有 $APK"; exit 1; }

# 真机序列号两种形状都可能:mDNS 自动连上的 `adb-…._adb-tls-connect._tcp`,或手动 `adb connect` 的 `IP:端口`
tv() { adb devices | awk -F'\t' '$1 !~ /^emulator-/ && $2=="device"{print $1; exit}'; }
home_in_front() {
  local f
  f=$(adb -s "$1" shell 'dumpsys window | grep mCurrentFocus' 2>/dev/null | tr -d '\r')
  echo "$f" | grep -q "$PKG/$PKG.MainActivity\|$PKG/.MainActivity" && return 0
  # 我们的屏保盖在首页上(首页紧挨在屏保下面)也算:装包杀掉屏保所在的进程,R151 记过「屏保在屏幕上」会把首页拉回。
  # 屏保盖在别的应用上时不算(装完他回来看到的是首页,不是暂停的视频)。
  echo "$f" | grep -q "$PKG/android.service.dreams.DreamActivity" || return 1
  adb -s "$1" shell 'am stack list' 2>/dev/null | tr -d '\r' | grep 'taskId=' | sed -n 2p | grep -q "$PKG/$PKG.MainActivity"
}

streak=0
while [ "$streak" -lt 3 ]; do
  T=$(tv)
  if [ -n "$T" ] && home_in_front "$T"; then streak=$((streak+1)); else streak=0; fi
  [ "$streak" -lt 3 ] && sleep 15
done

# 首页下面那一个根任务(屏保开着时首页是第 2 个,下面那个是第 3 个)
next_root=$(adb -s "$T" shell 'am stack list' 2>/dev/null | tr -d '\r' \
  | awk -v home="$PKG/$PKG.MainActivity" '/^RootTask/{id=$2; sub("id=","",id)} /taskId=/{if(seen){print id" "$0; exit} if(index($0,home)) seen=1}')
if echo "$next_root" | grep -q 'com.sony.dtv.tvlin'; then
  rid=${next_root%% *}
  echo "$(date +%T) 输入源应用紧挨首页(root task $rid),先清掉"
  adb -s "$T" shell "am stack remove $rid"
  sleep 1
  if adb -s "$T" shell 'am stack list' 2>/dev/null | tr -d '\r' | grep 'taskId=' | grep -A1 "$PKG/$PKG.MainActivity" | sed -n 2p | grep -q 'com.sony.dtv.tvlin'; then
    echo "没清掉,不装"; exit 2
  fi
fi

echo "$(date +%T) 首页在前台约 30 s,开始装"
adb -s "$T" install -r "$APK" 2>&1 | tail -1
# 刚装完没有运行画像,speed-profile 会被降成 verify;-m speed 也可能要跑两三次才读回编过(CLAUDE.md 性能测量一节)
for i in 1 2 3 4 5; do
  adb -s "$T" shell cmd package compile -m speed -f $PKG >/dev/null 2>&1
  st=$(adb -s "$T" shell "dumpsys package dexopt | grep -A3 '\[$PKG\]'" 2>/dev/null | grep -o 'status=[a-z-]*' | head -1)
  echo "编译第 $i 次:$st"
  case "$st" in *verify*|"") sleep 6;; *) break;; esac
done
dev=$(adb -s "$T" shell "md5sum \$(pm path $PKG | sed 's/package://' | head -1)" 2>/dev/null | awk '{print $1}')
loc=$(md5 -q "$APK")
[ "$dev" = "$loc" ] && echo "md5 一致 $dev" || { echo "md5 不一致:电视 $dev 本地 $loc"; exit 3; }
sleep 8
echo "装完前台:$(adb -s "$T" shell 'dumpsys window | grep mCurrentFocus' | tr -d '\r')"
