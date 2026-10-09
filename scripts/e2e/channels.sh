#!/usr/bin/env bash
# 双通道(稳定 / Beta)端到端:模拟器 + 本机清单服务,走真实的 取清单 → 下载 → 校验 → 会话安装 → 切通道 → 回退安装。不发版、不碰真机。
#
# 用法:
#   source scripts/env.sh
#   emulator -avd unitedu-tv-2 -port 5562 -no-snapshot -no-audio -gpu host &    # 等 sys.boot_completed = 1
#   DEV=emulator-5562 scripts/e2e/channels.sh [build]
# 不带 build 时直接用 $E2E_WORK/{A,B,C}.apk;带 build 就用 release 签名把三个包造出来(A=100/1.0.3、B=101/1.0.4-beta.1、C=102/1.0.3)。
# 环境变量:DEV(必填之外默认 emulator-5562)、E2E_WORK(包 / 清单 / 服务目录,默认 /tmp/unitedu-channels)、
#           SHOTS(截图目录,默认 docs/screenshots/channels)。
set -euo pipefail
cd "$(dirname "$0")/../.."
export DEV="${DEV:-emulator-5562}"
WORK="${E2E_WORK:-/tmp/unitedu-channels}"
export SHOTS="${SHOTS:-docs/screenshots/channels}"
PORT=8099
URLS=(-Punitedu.updateUrls=http://127.0.0.1:$PORT/latest.json -Punitedu.betaUrls=http://127.0.0.1:$PORT/beta.json
      -Punitedu.rollbackUrls=http://127.0.0.1:$PORT/rollback.json -PrequireReleaseKey=true)
mkdir -p "$WORK" "$SHOTS"

build_one() {  # <versionCode> <versionName> <输出名>
  if [[ "$2" != "1.0.3" ]]; then sed -i '' "s/versionName = \"1.0.3\"/versionName = \"$2\"/" app/build.gradle.kts; fi
  gradle --no-daemon assembleRelease -PversionCodeOverride="$1" "${URLS[@]}" >/dev/null || { git checkout app/build.gradle.kts; return 1; }
  git checkout app/build.gradle.kts
  cp app/build/outputs/apk/release/app-release.apk "$WORK/$3"
}
if [[ "${1:-}" == build ]]; then
  build_one 100 1.0.3 A.apk; build_one 101 1.0.4-beta.1 B.apk; build_one 102 1.0.3 C.apk
fi
for f in A B C; do [[ -f "$WORK/$f.apk" ]] || { echo "缺 $WORK/$f.apk(加参数 build)" >&2; exit 1; }; done

# 清单:沿用 release.sh gen_manifest 的字段(sha256 实算,apkUrl 指本机服务)
mk() {  # <versionCode> <versionName> <apk> <输出清单>
  python3 - "$@" "$WORK" <<'PY'
import hashlib, json, sys
code, name, apk, out, work = sys.argv[1:6]
sha = hashlib.sha256(open(f"{work}/{apk}", "rb").read()).hexdigest()
json.dump({"versionCode": int(code), "versionName": name, "notes": "e2e " + name,
           "apkUrl": f"http://127.0.0.1:8099/{apk}", "sha256": sha, "minSdk": 28},
          open(f"{work}/{out}", "w"), indent=2)
PY
}
mk 100 1.0.3 A.apk latest.json; mk 101 1.0.4-beta.1 B.apk beta.json; mk 102 1.0.3 C.apk rollback.json

# 本机服务:存在 THROTTLE 文件时 APK 每 64KB 睡 0.5s(场景 5 要在下载中途切通道)
cat > "$WORK/server.py" <<'PY'
import http.server, os, sys, time
os.chdir(sys.argv[1])
class H(http.server.SimpleHTTPRequestHandler):
    def copyfile(self, src, dst):
        slow = os.path.exists("THROTTLE") and self.path.endswith(".apk")
        while True:
            b = src.read(65536)
            if not b: break
            try: dst.write(b)
            except Exception: break
            if slow: time.sleep(0.5)
    def log_message(self, *a): sys.stderr.write("%s %s\n" % (self.command, self.path))
http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[2])), H).serve_forever()
PY
rm -f "$WORK/THROTTLE"
python3 "$WORK/server.py" "$WORK" $PORT >"$WORK/server.log" 2>&1 &
SRV=$!
trap 'kill $SRV 2>/dev/null || true' EXIT
adb -s "$DEV" reverse tcp:$PORT tcp:$PORT

# 起点:干净装 A,跳过引导,设英文
adb -s "$DEV" uninstall com.uniteduone.launcher >/dev/null 2>&1 || true
adb -s "$DEV" install "$WORK/A.apk" >/dev/null
adb -s "$DEV" shell cmd package set-home-activity com.uniteduone.launcher/.MainActivity
adb -s "$DEV" shell appops set com.uniteduone.launcher SYSTEM_ALERT_WINDOW allow    # R151:否则「下载并安装」只会去开悬浮窗设置页
adb -s "$DEV" shell appops set com.uniteduone.launcher REQUEST_INSTALL_PACKAGES allow    # 「安装未知应用」,否则下载完只会跳系统页
adb -s "$DEV" shell am start -n com.uniteduone.launcher/.MainActivity >/dev/null   # 首启建出 settings.json / layout.json
for _ in $(seq 1 30); do
  adb -s "$DEV" shell "grep -q onboardingDone /sdcard/Android/data/com.uniteduone.launcher/files/settings.json" 2>/dev/null && break; sleep 1
done; sleep 2

E2E_WORK="$WORK" python3 -I - <<'PY'
import os, re, subprocess, sys, time
sys.path.insert(0, os.path.join(os.getcwd(), "scripts", "e2e"))
from lib import *
W = os.environ["E2E_WORK"]; SH = os.environ["SHOTS"]
F = FILES

def vc():
    o = sh(f"dumpsys package {PKG} | grep -E 'versionCode|versionName'")
    return " ".join(o.split())
def st():
    return json.loads(sh(f"cat {F}/settings.json"))
def shot_jpg(n):
    p = shot(f"e2e-{n}")
    out = f"{SH}/e2e-{n}.jpg"
    subprocess.run(["sips", "-s", "format", "jpeg", p, "--out", out], capture_output=True)
    return out
def committed():
    return len(re.findall(r"self-update session .*committed", subprocess.run(["adb", "-s", DEV, "logcat", "-d", "-s", "UnitedU"], capture_output=True, text=True, errors="replace").stdout))
def logs(pat):
    o = subprocess.run(["adb", "-s", DEV, "logcat", "-d", "-s", "UnitedU"], capture_output=True, text=True, errors="replace").stdout
    return [l for l in o.splitlines() if re.search(pat, l)]
def restart(patch=None):
    sh(f"am force-stop {PKG}")
    if patch:
        s = sh(f"cat {F}/settings.json")
        for k, v in patch.items():
            s = re.sub(rf'"{k}": ("[^"]*"|[^,\n]+)', f'"{k}": {v}', s)
        subprocess.run(["adb", "-s", DEV, "shell", f"cat > {F}/settings.json"], input=s, text=True)
    for _ in range(2):
        sh(f"am start -n {PKG}/.MainActivity"); time.sleep(3)
    key("down")   # 无害方向键:退出触摸模式 / 唤醒
def lab():
    for _ in range(6):    # uiautomator 在界面动画 / 检查中常常读空,重读
        l = screen().label()
        if l: return l
        time.sleep(1)
    return ""
def goto(sub, direction, tries=6):
    """沿 direction 走到聚焦胶囊的文字含 sub 为止(逐次读屏确认,不盲按)。"""
    for _ in range(tries):
        if sub.lower() in lab().lower(): return True
        key(direction, gap=0.7)
    return sub.lower() in lab().lower()
def open_about():
    sh(f"am start -n {PKG}/.MainActivity"); time.sleep(1.5)
    key("down", gap=0.6); key("menu", gap=1.5)
    print("  menu focus:", lab(), flush=True)
    check("设置第一层 → 关于", goto("About", "down", 8), lab()); key("ok", gap=2)
    print("  about focus:", lab(), flush=True)
def wait_for(pred, t=60, step=1.0):
    end = time.time() + t
    s = screen()
    while time.time() < end:
        if pred(s): return s
        time.sleep(step); s = screen()
    return s
def pick_channel(which):   # About → 更新通道 → 稳定 / Beta;选了就回到关于页并自动检查
    check(f"进入「更新通道」", goto("Update channel", "down", 4))
    key("ok", gap=1.5)
    wait_for(lambda s: s.has("Settings · About") and s.has("Beta"), 10)
    check(f"通道页光标到 {which}", goto("Beta" if which == "beta" else "Stable", "down" if which == "beta" else "up", 3))
    key("ok", gap=1.5)
ACTION_LABELS = ("Check for Updates", "Download and Install", "Install Update", "Downloading", "Checking")
def first_capsule():
    """把焦点送到最上面那颗动作胶囊(检查 / 下载 / 安装);逐次读屏确认,读到才算。"""
    for _ in range(8):
        l = lab()
        if any(x in l for x in ACTION_LABELS): return l
        key("up", gap=1.0)
    return lab()

def install_and_wait(code, t=150):
    """下载 / 校验 / 会话提交由应用自己走;系统弹出「要更新这个应用吗」确认页就点「Update」;若关于页停在「Install Update」就按它。等版本号变成 code。"""
    end = time.time() + t
    while time.time() < end and f"versionCode={code}" not in vc():
        fg = sh("dumpsys window | grep mCurrentFocus")
        if "packageinstaller" in fg:
            l = lab(); print("  confirm page focus:", l, flush=True)
            if "Update" in l or "Install" in l: key("ok", gap=1.5)
            else: key("left", gap=0.7)    # 确认页按钮顺序 Update | Cancel,焦点默认在 Cancel
        elif "launcher" in fg:
            s = screen()
            if s.has("Install Update"):
                first_capsule(); print("  press Install Update:", lab(), flush=True); key("ok", gap=1.5)
        time.sleep(2)
    time.sleep(8)

def evidence():
    for l in logs(r"update check|self-update|rollback|channel"): print("  LOG", l, flush=True)

def show(tag):
    print(f"  [{tag}] ", [t for t in screen().texts() if t.strip()][:12], flush=True)

import json
# ---- 起点布局与设置:换一项设置 + 记下 layout 的指纹,场景 3 用 ----
sh(f"am force-stop {PKG}")
s = sh(f"cat {F}/settings.json")
s = s.replace('"onboardingDone": false', '"onboardingDone": true').replace('"language": "system"', '"language": "en"')
s = re.sub(r'"cardsPerRow": \d+', '"cardsPerRow": 5', s); s = re.sub(r'"cardSaturation": \d+', '"cardSaturation": 60', s)
subprocess.run(["adb", "-s", DEV, "shell", f"cat > {F}/settings.json"], input=s, text=True)
md5 = lambda: sh(f"md5sum {F}/layout.json").split()[0]
subprocess.run(["adb", "-s", DEV, "logcat", "-c"])

journey("1 稳定通道:latest.json → A(100)")
restart(); open_about(); time.sleep(2)
first_capsule(); show("about"); key("ok", gap=1)
s = wait_for(lambda s: s.has("up to date"), 20)
check("稳定通道:已是最新", s.has("up to date"), s.texts())
check("版本 A=100", "versionCode=100" in vc(), vc())
print("  ", shot_jpg(1))

journey("2 切 Beta:beta.json → B(101) → 下载安装")
sh("logcat -c"); subprocess.run(["adb", "-s", DEV, "logcat", "-c"])
pick_channel("beta")
s = wait_for(lambda s: s.has("1.0.4-beta.1"), 30); show("after-pick")
check("发现新版本 1.0.4-beta.1", s.has("New version 1.0.4-beta.1"), s.texts())
check("设置写入 updateChannel=beta", '"updateChannel": "beta"' in sh(f"cat {F}/settings.json"))
print("  ", shot_jpg("2a"))
first_capsule(); print("  press:", lab(), flush=True); key("ok", gap=1)    # Download and Install
install_and_wait(101)
check("安装后版本 B=101", "versionCode=101" in vc(), vc())
check("日志 committed = 1(下载 + sha256/身份校验通过后才会提交会话)", committed() == 1, logs("committed"))
sh(f"am start -n {PKG}/.MainActivity"); time.sleep(3); key("down"); open_about(); time.sleep(2)
s = screen(); show("about-after-B")
check("关于页版本带 Beta 标", any("1.0.4-beta.1" in t and "Beta" in t for t in s.texts()), s.texts())
print("  ", shot_jpg("2b"))

evidence()
if os.environ.get("STOP_AFTER") == "2": sys.exit(0)
journey("3 切回稳定:rollback.json → C(102),布局与设置保留")
sh("logcat -c"); subprocess.run(["adb", "-s", DEV, "logcat", "-c"])
l1 = md5(); set_before = sh(f"grep -E 'cardsPerRow|cardSaturation' {F}/settings.json").split()
vcb = vc()
pick_channel("stable")
s = wait_for(lambda s: s.has("Back to stable"), 30); show("rollback")
check("显示「Back to stable 1.0.3 (layout and settings kept)」", s.has("Back to stable 1.0.3 (layout and settings kept)"), s.texts())
print("  ", shot_jpg("3a"))
first_capsule(); print("  press:", lab(), flush=True); key("ok", gap=1)
install_and_wait(102)
check("回退后版本 C=102 且版本名为稳定号", "versionCode=102" in vc() and "versionName=1.0.3" in vc(), vc())
check("日志 committed = 1", committed() == 1, logs("committed"))
time.sleep(6)
check("layout.json 未变(md5)", md5() == l1, (l1, md5()))
set_after = sh(f"grep -E 'cardsPerRow|cardSaturation' {F}/settings.json").split()
check("改过的设置仍在(cardsPerRow=5,cardSaturation=60)", set_after == set_before and "5," in set_after and "60," in set_after, set_after)
check("通道已是 stable", '"updateChannel": "stable"' in sh(f"cat {F}/settings.json"))
sh(f"am start -n {PKG}/.MainActivity"); time.sleep(3); key("down"); open_about(); time.sleep(2)
show("about-after-C"); print("  ", shot_jpg("3b"))
print("  3 前:", vcb, "后:", vc(), "layout md5", l1, "→", md5(), set_before, "→", set_after)

evidence()
journey("4 beta.json 不存在时切 Beta:按稳定版判断,不报检查失败")
sh("logcat -c"); subprocess.run(["adb", "-s", DEV, "logcat", "-c"])
os.rename(f"{W}/beta.json", f"{W}/beta.json.off")
pick_channel("beta")
s = wait_for(lambda s: s.has("up to date") or s.has("Couldn") or s.has("New version"), 30); show("no-beta")
check("没有检查失败提示", not (s.has("Couldn") or s.has("Check failed")), s.texts())
check("按稳定版判断:已是最新", s.has("up to date"), s.texts())
print("  ", shot_jpg(4))
os.rename(f"{W}/beta.json.off", f"{W}/beta.json")

evidence()
journey("5 下载中途切回稳定:下载作废并按稳定重新检查")
# 回到 A(100)、稳定通道,才有 B 可下
sh(f"am force-stop {PKG}"); sh(f"pm uninstall {PKG}")
subprocess.run(["adb", "-s", DEV, "install", f"{W}/A.apk"], capture_output=True)
sh(f"cmd package set-home-activity {PKG}/.MainActivity"); sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow"); sh(f"appops set {PKG} REQUEST_INSTALL_PACKAGES allow")
sh(f"am start -n {PKG}/.MainActivity")
for _ in range(30):
    if "onboardingDone" in sh(f"cat {F}/settings.json"): break
    time.sleep(1)
time.sleep(2); sh(f"am force-stop {PKG}")
s = sh(f"cat {F}/settings.json"); s = re.sub(r'"updateChannel": "\w+"', '"updateChannel": "stable"', s)
s = s.replace('"onboardingDone": false', '"onboardingDone": true').replace('"language": "system"', '"language": "en"')
subprocess.run(["adb", "-s", DEV, "shell", f"cat > {F}/settings.json"], input=s, text=True)
open(f"{W}/THROTTLE", "w").close()
sh("logcat -c"); subprocess.run(["adb", "-s", DEV, "logcat", "-c"])
restart(); open_about(); time.sleep(2)
check("起点 A=100", "versionCode=100" in vc(), vc())
pick_channel("beta")
s = wait_for(lambda s: s.has("1.0.4-beta.1"), 30)
first_capsule(); key("ok", gap=1)
s = wait_for(lambda s: s.has("Downloading"), 30); show("mid-download")
check("正在下载", s.has("Downloading"), s.texts())
print("  ", shot_jpg("5a"))
n_get = len(open(f"{W}/server.log").read().split("GET /B.apk")) - 1
pick_channel("stable")                                                  # 下载中途切回稳定
s = wait_for(lambda s: s.has("up to date") or s.has("New version"), 30); show("after-switch")
check("切回稳定后:已是最新(下载作废,按稳定重检)", s.has("up to date"), s.texts())
print("  ", shot_jpg("5b"))
time.sleep(25)                                                           # 再等一阵,确认没有「下载完成 → 弹安装」的尾巴
s = screen(); show("later")
check("作废的下载没有冒出「Install Update」", not s.has("Install Update"), s.texts())
check("日志里没有 committed", committed() == 0, logs("committed"))
check("版本仍是 A=100", "versionCode=100" in vc(), vc())
if os.path.exists(f"{W}/THROTTLE"): os.remove(f"{W}/THROTTLE")

evidence()
open(f"{W}/logcat.txt", "w").write(subprocess.run(["adb", "-s", DEV, "logcat", "-d", "-s", "UnitedU"], capture_output=True, text=True, errors="replace").stdout)
bad = [r for r in RESULTS if not r["ok"]]
print(f"\n== {len(RESULTS) - len(bad)}/{len(RESULTS)} PASS ==")
sys.exit(1 if bad else 0)
PY
adb -s "$DEV" reverse --remove tcp:$PORT || true
