"""按键模糊测试:adb monkey 只发方向 / 确定 / 返回 / 菜单,多个种子;每个种子后查崩溃 / ANR / 焦点 / 落盘。

每个种子前把设置、布局、标题、隐藏输入源推回基线(monkey 会改语言、卡片大小、删行里的应用、改名……)。
monkey 用 `-p` 限定本包:它按「打开应用」「电视设置」时,系统页 / 别的应用的 Activity 会被 monkey 拒掉(日志里的
`// Rejecting start of Intent`),按键始终落在我们的界面上。本应用只挂 LEANBACK_LAUNCHER / HOME,不挂 LAUNCHER,
所以要 `-c android.intent.category.LEANBACK_LAUNCHER`,否则 monkey 报「No activities found to run」直接退出。
环境变量:`MONKEY_SEEDS`(逗号分隔,默认 6 个)、`MONKEY_EVENTS`(每个种子的事件数,默认 600)。
"""
import sys, time, json, os, re
sys.path.insert(0, os.path.dirname(__file__))
from lib import *

SEEDS = [int(x) for x in os.environ.get("MONKEY_SEEDS", "11,23,37,51,73,97").split(",") if x.strip()]
EVENTS = int(os.environ.get("MONKEY_EVENTS", "600"))
BASE_SETTINGS = {"language": "en", "onboardingDone": True, "showTitles": False, "cardsPerRow": 6,
                 "idleAfterMs": 180000, "screensaverAfterMs": 0}


def crash_lines():
    """crash 缓冲区 + 主缓冲区里属于本包的 FATAL / ANR。"""
    crash = sh("logcat -b crash -d", timeout=60)
    main = sh("logcat -d -b main -b system | grep -E 'FATAL EXCEPTION|ANR in com.uniteduone|am_anr|am_crash'", timeout=60)
    lines = [l for l in (crash + "\n" + main).splitlines() if l.strip() and not l.startswith("---------")]
    ours = [l for l in lines if "uniteduone" in l or "FATAL EXCEPTION" in l]
    return ours, crash


def reset_state():
    sh(f"am force-stop {PKG}")
    sh(f"rm -f {FILES}/titles.json {FILES}/titles.json.prev {FILES}/hidden-inputs.json {FILES}/hidden-inputs.json.prev")
    sh(f"rm -f {FILES}/*.bad")
    restart(settings_patch=BASE_SETTINGS, layout=LAYOUT)


def monkey(seed, n):
    cmd = ("monkey -p com.uniteduone.launcher -c android.intent.category.LEANBACK_LAUNCHER "
           "--pct-touch 0 --pct-motion 0 --pct-trackball 0 --pct-appswitch 0 --pct-anyevent 0 --pct-syskeys 0 "
           "--pct-flip 0 --pct-pinchzoom 0 --pct-nav 60 --pct-majornav 40 "
           f"--throttle 150 -s {seed} -v {n}")
    return sh(cmd, timeout=n * 2 + 120)


def run():
    journey("monkey-setup")
    sh("settings put secure long_press_timeout 400")
    reset_state()
    ok, s = focus_stable()
    check("基线:冷启动后单个焦点", ok, s.count_focused())
    total = 0
    for seed in SEEDS:
        journey(f"monkey-seed-{seed}")
        reset_state()
        sh("logcat -b all -c")
        out = monkey(seed, EVENTS)
        m = re.search(r"Events injected: (\d+)", out)
        injected = int(m.group(1)) if m else 0
        total += injected
        open(f"{OUT}/monkey-{seed}.txt", "w").write(out)
        check(f"种子 {seed}:monkey 跑完 {EVENTS} 个事件", injected >= EVENTS, (injected, out[-300:]))
        check(f"种子 {seed}:monkey 没报崩溃 / ANR", "// CRASH" not in out and "// NOT RESPONDING" not in out,
              [l for l in out.splitlines() if "CRASH" in l or "NOT RESPONDING" in l][:4])
        time.sleep(2)
        ours, crash = crash_lines()
        if ours:
            open(f"{OUT}/monkey-{seed}-crash.txt", "w").write(crash + "\n" + "\n".join(ours))
        check(f"种子 {seed}:logcat 没有本包的 FATAL / ANR", not ours, ours[:6])
        fg = foreground()
        check(f"种子 {seed}:前台仍是 UnitedU", fg == PKG, fg)
        s = screen()
        shot(f"monkey-{seed}-end")
        n = s.count_focused()
        # 屏保 / 待机层上没有可聚焦节点(uiautomator 也不报透明节点):那种情况先按一下方向键唤醒再数
        if n != 1:
            key("down"); time.sleep(1.2)
            s = screen(); n = s.count_focused()
        check(f"种子 {seed}:结束时焦点恰好 1 个", n == 1, (n, s.texts()[:8]))
        home_intent(); time.sleep(1.5)
        ok, s = focus_stable()
        check(f"种子 {seed}:HOME 之后焦点恰好 1 个", ok, s.count_focused())
        bad = sh(f"ls {FILES} | grep -E '\\.bad$'").split()
        check(f"种子 {seed}:没有被判损坏的状态文件(.bad)", not bad, bad)
        lay = pull_json("layout.json")
        check(f"种子 {seed}:layout.json 仍可解析且有行", bool(lay and lay.get("rows")), str(lay)[:200])
        st = pull_json("settings.json")
        check(f"种子 {seed}:settings.json 仍可解析", st is not None)
    print(f"monkey 共注入 {total} 个事件", flush=True)
    reset_state()


if __name__ == "__main__":
    run()
    summary()
