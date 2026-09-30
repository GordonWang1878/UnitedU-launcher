"""UnitedU 端到端测试的小工具库:遥控器按键、uiautomator 读屏、断言记录、截图。只驱动模拟器。"""
import json, os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
DEV = os.environ.get("DEV", "emulator-5560")                       # 模拟器序列号(adb devices 里那一列)
REPO = os.environ.get("REPO", os.path.dirname(os.path.dirname(HERE)))  # 仓库根:标签文字直接读它的 values-en/strings.xml
OUT = os.environ.get("E2E_OUT", "/tmp/unitedu-e2e")                # 截图、结果 JSON、测试 APK、上传素材都放这里
APKS = os.path.join(OUT, "apks")
FX = os.path.join(OUT, "fx")
LAYOUT = json.load(open(os.path.join(HERE, "fixtures", "layout.json"), encoding="utf-8"))   # 4 行测试布局(占位应用)
PKG = "com.uniteduone.launcher"
FILES = f"/sdcard/Android/data/{PKG}/files"
os.makedirs(OUT, exist_ok=True)

KEYS = dict(up=19, down=20, left=21, right=22, ok=23, back=4, menu=82, home=3, enter=66, del_=67)

def adb(*args, timeout=90):
    # errors="replace":设备上的文件名 / 日志可能不是合法 UTF-8(比如被劈开的代理对),解不了也不能让脚本崩
    return subprocess.run(["adb", "-s", DEV, *args], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)

def sh(cmd, timeout=90):
    return adb("shell", cmd, timeout=timeout).stdout

# ---------- 文案(直接读英文 strings.xml,标签与界面同一份) ----------
_S = {}
_LANG = ["en"]
_RES_DIR = {"en": "values-en", "zh-CN": "values", "zh-TW": "values-zh-rTW"}
def set_lang(lang):
    """S() 改读另一种语言的 strings.xml(三语溢出测试用);缺的 key 按 Android 的规矩回落到 values/。"""
    _LANG[0] = lang
    _S.clear()
def _load_strings():
    dirs = ["values"] + ([_RES_DIR[_LANG[0]]] if _RES_DIR[_LANG[0]] != "values" else [])
    for d in dirs:
        x = open(f"{REPO}/app/src/main/res/{d}/strings.xml", encoding="utf-8").read()
        for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', x, re.S):
            v = m.group(2).replace("\\'", "'").replace('\\"', '"').replace("&amp;", "&").replace("\\n", "\n")
            _S[m.group(1)] = v
def S(key):
    if not _S: _load_strings()
    return _S[key]

# ---------- 按键 ----------
def key(*names, gap=0.6):
    for n in names:
        sh(f"input keyevent {KEYS.get(n, n)}")
        time.sleep(gap)

def keys_fast(*names, gap=0.15):
    """一条 shell 里连发(间隔 gap 秒),模拟真人连按遥控器。"""
    parts = []
    for n in names:
        parts.append(f"input keyevent {KEYS.get(n, n)}")
        parts.append(f"sleep {gap}")
    sh("; ".join(parts))

def long_ok(settle=1.2):
    sh("settings put secure long_press_timeout 700; input keyevent --longpress 23; settings put secure long_press_timeout 400")
    time.sleep(settle)

def text_input(s):
    sh("input text " + s.replace(" ", "%s"))
    time.sleep(0.5)

# ---------- 读屏 ----------
class Screen:
    def __init__(self, nodes):
        self.nodes = nodes
    @property
    def focused(self):
        return [n for n in self.nodes if n["focused"]]
    def focus(self):
        f = self.focused
        return f[0]["b"] if len(f) == 1 else None
    def label(self):
        """聚焦节点框内所有文字(Compose 的文字在子节点上)。"""
        f = self.focus()
        if not f: return ""
        x0, y0, x1, y1 = f
        out = []
        for n in self.nodes:
            t = n["text"] or n["desc"]
            if not t: continue
            a0, b0, a1, b1 = n["b"]
            if a0 >= x0 - 2 and b0 >= y0 - 2 and a1 <= x1 + 2 and b1 <= y1 + 2:
                out.append(t)
        return " | ".join(out)
    def texts(self):
        return [n["text"] or n["desc"] for n in self.nodes if (n["text"] or n["desc"])]
    def has(self, s):
        return any(s in t for t in self.texts())
    def count_focused(self):
        return len(self.focused)

def screen(retries=3):
    for _ in range(retries):
        # 先删旧文件:dump 失败(界面一直不空闲,比如视频在放)时不会覆盖它,cat 读到的就是上一屏
        sh("rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
        x = sh("cat /sdcard/ui.xml")
        i = x.find("<?xml")
        if i < 0:
            time.sleep(0.5); continue
        try:
            root = ET.fromstring(x[i:])
        except ET.ParseError:
            time.sleep(0.5); continue
        nodes = []
        for n in root.iter("node"):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
            if not m: continue
            nodes.append(dict(text=n.get("text", ""), desc=n.get("content-desc", ""), focused=n.get("focused") == "true",
                              b=tuple(int(v) for v in m.groups()), pkg=n.get("package", "")))
        return Screen(nodes)
    return Screen([])

def foreground():
    out = sh("dumpsys window | grep mCurrentFocus")
    m = re.search(r"u0 ([^ /]+)/", out)
    return m.group(1) if m else out.strip()

def shot(name):
    p = f"{OUT}/{name}.png"
    with open(p, "wb") as f:
        f.write(subprocess.run(["adb", "-s", DEV, "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)
    subprocess.run(["sips", "-Z", "960", p], capture_output=True)
    return p

# ---------- 断言 ----------
RESULTS = []
JOURNEY = ["-"]
def journey(name):
    JOURNEY[0] = name
    print(f"\n=== {name} ===", flush=True)

def check(name, cond, detail=""):
    ok = bool(cond)
    RESULTS.append(dict(j=JOURNEY[0], name=name, ok=ok, detail=str(detail)[:300]))
    print(("  PASS " if ok else "  FAIL ") + name + (f"  [{detail}]" if detail and not ok else ""), flush=True)
    if not ok:
        shot(f"FAIL-{len(RESULTS):03d}")
    return ok

def summary():
    fails = [r for r in RESULTS if not r["ok"]]
    print(f"\n{len(RESULTS) - len(fails)}/{len(RESULTS)} passed", flush=True)
    for r in fails:
        print(f"  FAIL [{r['j']}] {r['name']}  {r['detail']}")
    json.dump(RESULTS, open(f"{OUT}/results-{int(time.time())}.json", "w"), ensure_ascii=False, indent=1)
    return fails

# ---------- 导航 ----------
def move_to(sub, direction="down", max_steps=12, exact=False):
    """沿一个方向走,直到聚焦节点的文字含 sub。返回 Screen 或 None。"""
    last = None
    for _ in range(max_steps + 1):
        s = screen()
        lab = s.label()
        if (lab == sub) if exact else (sub.lower() in lab.lower()):
            return s
        f = s.focus()
        if f is not None and f == last:
            pass
        last = f
        key(direction)
    return None

def focus_stable():
    """聚焦节点数 = 1(焦点没丢、没重复)。"""
    s = screen()
    return s.count_focused() == 1, s

# ---------- 数据 ----------
def pull_json(name):
    out = sh(f"cat {FILES}/{name}")
    try:
        return json.loads(out)
    except Exception:
        return None

def push_json(name, data):
    p = f"{OUT}/_{name}"
    json.dump(data, open(p, "w"), ensure_ascii=False, indent=2)
    adb("push", p, f"{FILES}/{name}")

def home_intent():
    sh(f"am start -a android.intent.action.MAIN -c android.intent.category.HOME -n {PKG}/.MainActivity")
    time.sleep(1.5)

def restart(settings_patch=None, layout=None):
    """冷启动:force-stop → 可选改 settings / layout → 用 HOME intent 拉起(之后的 HOME 走同一个实例的 onNewIntent)。"""
    sh(f"am force-stop {PKG}")
    if settings_patch is not None:
        st = pull_json("settings.json") or {}
        st.update(settings_patch)
        push_json("settings.json", st)
    if layout is not None:
        push_json("layout.json", layout)
    home_intent()
    if foreground() != PKG:
        home_intent()
    time.sleep(1.5)
    key("down", "up")
