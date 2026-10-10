# source me(bash)。S = 模拟器序列号(unitedu-tv-2 → emulator-5570,unitedu-tv-3 → emulator-5572),T = 原始数据目录
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export S="${S:-emulator-5570}" T="${T:-/tmp/edit-jank}" P=com.uniteduone.launcher
mkdir -p "$T"
k() { for x in "$@"; do adb -s $S shell input keyevent $x; sleep 0.45; done; }
dumpui() { adb -s $S shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb -s $S shell cat /sdcard/u.xml > $T/u.xml; T=$T python3 - <<'PY'
import os, xml.etree.ElementTree as ET
r=ET.parse(os.path.join(os.environ['T'], 'u.xml')).getroot()
def txt(n): return [m.get('text') or m.get('content-desc') for m in n.iter() if (m.get('text') or m.get('content-desc'))]
for n in r.iter():
  if n.get('focused')=='true': print('FOCUS', n.get('bounds'), txt(n)[:4])
print('TEXTS', [t for t in txt(r)][:40])
PY
}
shot() { adb -s $S exec-out screencap -p > $T/$1.png; }
run() { local n=$1 s=$2; shift 2; adb -s $S shell dumpsys gfxinfo $P reset >/dev/null; adb -s $S shell rm -f /sdcard/$n.mp4 /data/misc/perfetto-traces/$n.pftrace
 (adb -s $S shell perfetto -o /data/misc/perfetto-traces/$n.pftrace -t ${s}s -b 64mb -a $P gfx view input sched freq dalvik am wm >/dev/null 2>&1 &)
 (adb -s $S shell screenrecord --time-limit $s --size 960x540 /sdcard/$n.mp4 &); sleep 1.5
 for x in "$@"; do adb -s $S shell input keyevent $x; sleep 0.6; done
 sleep 1.5; adb -s $S shell dumpsys gfxinfo $P framestats > $T/$n.gfx.txt
 while adb -s $S shell pgrep -x perfetto >/dev/null || adb -s $S shell pgrep screenrecord >/dev/null; do sleep 0.5; done
 adb -s $S pull /data/misc/perfetto-traces/$n.pftrace $T/$n.pftrace >/dev/null; adb -s $S pull /sdcard/$n.mp4 $T/$n.mp4 >/dev/null; }
