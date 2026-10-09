"""频道行(R164 / R165):编辑页加频道 → 授权弹窗 → 首页出现 → 左右 / 长按 / 启动 → 节目变少 / 重建 / 清空
→ 撤销授权 → 选频道页拒绝授权 → 满 5 行 → 删频道架子 → 卸载发布方。

夹具:带代码的发布方 test.channels(fixtures/channels/,fixtures.py 的 channels() 造;本旅程自己 `--user 0` 装,
卸载才发 PACKAGE_FULLY_REMOVED,见 CLAUDE.md 模拟器坑)。只驱动模拟器;并行时用 unitedu-tv-2 / -3(DEV=emulator-5562 / 5564)。
约 8 分钟。

等待一律按条件轮询,不靠固定 sleep:夹具命令等它自己的日志 `CHFIX cmd test.channels.<X>`(SHRINK / PUBLISH 都打
`published`,不能拿它认是哪条命令);界面变化(500 ms 去抖后重读)等读屏条件成立。
"""
import json, os, re, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import *
from j_edit import open_edit, go_chip

BASE = {"language": "en", "onboardingDone": True, "showTitles": False}
PUB = "test.channels"
PERM = "android.permission.READ_TV_LISTINGS"
TITLES = ["Ocean Deep", "City Lights", "Desert Run", "Night Train", "Snow Peak", "The Long Road", "Paper Moon", "Blue Album"]
REF = {"pkg": PUB, "key": "e2e-picks", "name": "E2E Picks"}

def wait_for(fn, timeout=8.0, step=0.5):
    """轮询 fn() 直到为真或超时;返回最后一次的值(真值即成立)。"""
    end = time.time() + timeout
    v = fn()
    while not v and time.time() < end:
        time.sleep(step)
        v = fn()
    return v

class Bail(Exception):
    """后面的旅程依赖的一步没成:不再往下走(免得在错的界面上乱按、把布局改坏),直接收尾。"""

def need(name, cond, detail=""):
    if not check(name, cond, detail):
        raise Bail(name)
    return cond

def stays(fn, dur=2.0, step=0.5):
    """fn() 在 dur 秒里每次读都为真(至少读两次,首末相隔 ≥ dur):给「没有 X」这类否定断言配一个稳定窗口,
    免得在数据还没到 / 500 ms 去抖还没触发时就读到旧画面而误过。"""
    end = time.time() + dur
    while True:
        if not fn():
            return False
        if time.time() >= end:
            return True
        time.sleep(step)

def cmd(action, timeout=15.0):
    """给夹具发一条命令,等它打出 `CHFIX cmd test.channels.<action>`(命令做完才打)。先清日志,免得认到上一条。"""
    sh("logcat -c")
    sh(f"am broadcast -f 32 -n {PUB}/.Cmd -a test.channels.{action}")
    done = wait_for(lambda: f"cmd test.channels.{action}" in sh("logcat -d -s CHFIX"), timeout)
    check(f"夹具命令 {action} 做完", done)
    return done

def rows():
    return (pull_json("layout.json") or {}).get("rows", [])

def channel_rows():
    return [r for r in rows() if "channel" in r]

def fatal():
    out = sh("logcat -b crash -d") + sh("logcat -d | grep -E 'FATAL EXCEPTION|ANR in com.uniteduone'")
    return [l for l in out.splitlines() if l.strip() and not l.startswith("---------")]

def header():
    return S("channel_row_title").replace("%1$s", "E2E Channels").replace("%2$s", "E2E Picks")

def revoke():
    """撤销授权(会杀掉本进程),并清掉「用户拒绝过 / 不再询问」两个标记:`pm revoke` 不清它们,重跑本旅程时
    上一轮「Don't allow」留下的标记会让下一次申请不弹窗、直接回 false(Android 11+ 两次拒绝 = 不再询问)。
    注意:UnitedU 自己记的 deniedBefore(SharedPreferences)清不掉,要靠一次 Allow 清——拒绝旅程前必须先走过 Allow。"""
    sh(f"pm revoke {PKG} {PERM}")
    sh(f"pm clear-permission-flags {PKG} {PERM} user-set user-fixed")

def wait_permission_dialog():
    """等系统授权窗到前台(最多 6 s):选页淡入 → LaunchedEffect → 授权窗起来,宿主负载高时 1.5 s 不一定够。"""
    for _ in range(12):
        if "permissioncontroller" in foreground():
            return True
        time.sleep(0.5)
    return False

def _perm_hit(lab, want):
    lab = lab.strip().lower()
    if want == "allow":
        return lab == "allow"
    if want == "forever":
        return "ask again" in lab                     # 「Deny and don't ask again」
    return lab.startswith(("don", "deny")) and "ask again" not in lab    # 「Don't allow」(旧版「Deny」)

def answer_permission(want="allow"):
    """系统授权窗(permissioncontroller 的 GrantPermissionsActivity)里把焦点挪到目标按钮再按确定。
    want:"allow" / "deny"(Don't allow)/ "forever"(Deny and don't ask again;TV 版授权窗在拒绝过一次后才多出这颗,
    模拟器实测连按两次「Don't allow」不会变成不再询问,要点它;没有这颗时退回「Don't allow」)。"""
    if not wait_permission_dialog():
        return False
    key("down")      # 授权窗刚起来时可能没有焦点节点:先发一下方向键
    for target in ([want, "deny"] if want == "forever" else [want]):
        for direction in ["down"] * 4 + ["up"] * 8:
            lab = screen().label()
            if _perm_hit(lab, target):
                key("ok")
                wait_for(lambda: "permissioncontroller" not in foreground(), 6)
                time.sleep(1)
                return True
            key(direction)
    return False

def perm_flags():
    return sh(f"dumpsys package {PKG} | grep '{PERM}:'").strip()

def to_new_channel_card():
    """编辑页一路向下到「新的一行」,在「应用行」卡上就向右,直到焦点在「频道」卡(认它的说明文字,标题「Channel」与架子标签同名)。"""
    for _ in range(40):
        lab = screen().label()
        if S("shelf_new_channel_desc") in lab or S("edit_choice_full").replace("%1$d", "5") in lab and S("edit_choice_app_row") not in lab:
            return True
        if S("edit_choice_app_row") in lab:
            key("right"); continue
        key("down")
    return False

def to_last_shelf_first_chip():
    """最后一个内容层(本旅程里总是频道架子)的第一颗胶囊:先到「新的一行」,上一格,再一路向左。
    架子在屏幕外时 uiautomator 不报它的节点,要先把焦点走过去,它进了焦点线才读得到字。"""
    to_new_channel_card()
    key("up")
    for _ in range(4):
        key("left", gap=0.4)
    time.sleep(0.8)
    return screen()

def run():
    was_granted = "granted=true" in perm_flags()
    try:
        _journeys()
    except Bail as e:
        print(f"  中止:{e}(后面的旅程依赖这一步)", flush=True)
    finally:           # 中途出错 / 中止也不留状态:卸夹具、恢复授权与标记、推回测试布局
        sh(f"pm uninstall {PUB}")
        revoke()
        if was_granted:
            sh(f"pm grant {PKG} {PERM}")
        restart(BASE, layout=LAYOUT)

def _journeys():
    journey("频道行:准备")
    adb("install", "-r", "--user", "0", f"{APKS}/{PUB}.apk")
    cmd("DROP")
    revoke()                               # 撤销运行时权限会杀掉本进程(Android 行为),下面 restart 重新拉起
    sh(f"rm -f {FILES}/channel-init.json")
    restart(BASE, layout=LAYOUT)
    sh("logcat -c")

    journey("频道行:编辑页加频道 → 授权 → 选频道")
    open_edit()
    need("走到「新的一行 → 频道」", to_new_channel_card(), screen().label())
    key("ok")
    need("没授权时弹系统授权窗", wait_permission_dialog(), foreground())
    shot("ch-01-permission-dialog")
    need("按 Allow", answer_permission("allow"))
    check("授权到手", "granted=true" in sh(f"dumpsys package {PKG} | grep {PERM}"))
    check("INITIALIZE_PROGRAMS 之后列表里冒出夹具频道", wait_for(lambda: screen().has(header()), 15))
    check("夹具收到 INITIALIZE_PROGRAMS", "init android.media.tv.action.INITIALIZE_PROGRAMS" in sh("logcat -d -s CHFIX"))
    init = wait_for(lambda: (pull_json("channel-init.json") or {}).get("notified", {}).get(PUB) == 1 and pull_json("channel-init.json"), 5) \
        or pull_json("channel-init.json") or {}
    check("channel-init.json 记下 test.channels = 1", init.get("notified", {}).get(PUB) == 1, init)
    shot("ch-02-picker")
    check("焦点落到夹具频道", move_to(header(), "down", max_steps=10) is not None)
    key("ok")
    last = wait_for(lambda: [r for r in rows()[-1:] if "channel" in r], 6)
    last = (rows() or [{}])[-1]
    check("layout.json 最后一行是频道行(格式逐字)", last == {"icon": "tv", "apps": [], "channel": REF}, last)
    s = wait_for(lambda: (lambda s: s if s.count_focused() == 1 and not s.has(S("channel_picker_title")) else None)(screen()), 6) or screen()
    check("选页关了、焦点恰好 1 个", s.count_focused() == 1 and not s.has(S("channel_picker_title")), s.label())
    shot("ch-03-edit-shelf")

    journey("频道行:首页")
    key("back"); time.sleep(2)
    home_intent()
    s = move_to("Ocean Deep", "down", max_steps=8)
    check("首页下到频道行,焦点在 weight 最高的第一张", s is not None, s and s.label())
    s = screen()
    check("行头「应用名 · 频道名」", s.has(header()))
    meta = S("channel_meta_season_episode").replace("%1$s", "1").replace("%2$s", "1")
    check("焦点行卡下第二行写季集", s.has(meta), meta)
    shot("ch-04-home-focused")
    for _ in TITLES[1:]:
        key("right")
    check("8 张按 weight 排,最后一张是 Blue Album", "Blue Album" in screen().label(), screen().label())
    key("right")
    check("行尾右键停住", "Blue Album" in screen().label())

    journey("频道行:海报超时")      # Review Focus 3
    check("Snow Peak 的 https 海报读不到被记下(之后一分钟不再试)",
          wait_for(lambda: "10.255.255.1" in sh("logcat -d -s UnitedU"), 12))   # 黑洞地址,连接超时 5 s
    check("没有崩溃、焦点恰好 1 个", not fatal() and screen().count_focused() == 1, fatal()[:3])

    journey("频道行:长按不做事、确定启动节目")
    move_to("Ocean Deep", "left", max_steps=8)
    sh("logcat -c")
    long_ok()
    check("长按后前台仍是 UnitedU", foreground() == PKG, foreground())
    check("长按没弹菜单", not screen().has(S("card_menu_open")))
    check("长按松手也没启动节目", "play p=" not in sh("logcat -d -s CHFIX"))
    key("ok")
    check("确定 = intent_uri 启动节目",
          wait_for(lambda: foreground() == PUB and "play p=0" in sh("logcat -d -s CHFIX"), 8), foreground())
    key("back")
    check("返回后焦点还在 Ocean Deep",
          wait_for(lambda: foreground() == PKG and "Ocean Deep" in screen().label(), 8), screen().label())

    journey("频道行:焦点在末张时节目从 8 删到 3")   # Review Focus 4
    move_to("Blue Album", "right", max_steps=8)
    cmd("SHRINK")
    s = wait_for(lambda: (lambda s: s if "Desert Run" in s.label() else None)(screen()), 8) or screen()
    check("焦点恰好 1 个", s.count_focused() == 1, s.count_focused())
    check("焦点落同一行末张 Desert Run", "Desert Run" in s.label(), s.label())
    check("前台仍是 UnitedU、没崩", foreground() == PKG and not fatal())

    journey("频道行:应用重建频道(_id 变,key 不变)")   # Review Focus 1
    cmd("REPUBLISH")
    log = sh("logcat -d -s CHFIX")
    published = re.findall(r"published ch=(\d+)", log)
    dropped = re.findall(r"dropped e2e-picks ch=(\d+)", log)
    check("夹具确实换了 _id", bool(published and dropped) and published[-1] != dropped[-1], (dropped, published))
    # 刷新前屏上是 SHRINK 后的 3 张;Night Train 是第 4 张,只有重建后的 8 张里才有——认它才算读到了刷新后的画面
    s = wait_for(lambda: (lambda s: s if s.has("Night Train") else None)(screen()), 10) or screen()
    check("重建后的节目画出来了(Night Train 回来)", s.has("Night Train"), s.texts()[-12:])
    check("行还在(按 internal_provider_id 找回)、焦点恰好 1 个,且稳定 2 s",
          stays(lambda: (lambda s: s.has(header()) and s.has("Night Train") and s.count_focused() == 1)(screen()), 2.0), screen().label())

    journey("频道行:上面的频道行消失 / 出现,下面应用行的焦点不动")   # owner 裁定:目标按 layoutRow 认行
    lay = json.loads(json.dumps(LAYOUT))
    lay["rows"].insert(1, {"icon": "tv", "apps": [], "channel": REF})   # 行 0 应用、行 1 频道、行 2 起应用
    restart(BASE, layout=lay)
    s = move_to("虎牙直播", "down", max_steps=8)                       # 第 2 行(频道行下面那一行)第 1 张(test.dummy.app05)
    key("right")
    check("焦点在频道行下面那一行的第 2 张(斗鱼)", "斗鱼" in screen().label(), screen().label())
    cmd("CLEAR")
    s = wait_for(lambda: (lambda s: s if not s.has(header()) else None)(screen()), 8) or screen()
    check("频道清空、行不画了,焦点仍在同一张(斗鱼)、恰好 1 个", "斗鱼" in s.label() and s.count_focused() == 1 and not s.has(header()), s.label())
    cmd("PUBLISH")
    s = wait_for(lambda: (lambda s: s if s.has(header()) else None)(screen()), 8) or screen()
    check("节目回来、频道行重新出现在上面,焦点仍在斗鱼", "斗鱼" in s.label() and s.count_focused() == 1 and s.has(header()), s.label())
    tail = json.loads(json.dumps(LAYOUT))
    tail["rows"].append({"icon": "tv", "apps": [], "channel": REF})
    restart(BASE, layout=tail)          # 回到「频道行在最后」的布局:后面「重新授权」那步按「最后一个内容层是频道架子」走

    journey("频道行:清空 → 首页不画,布局里保留")
    cmd("CLEAR")
    s = wait_for(lambda: (lambda s: s if not s.has(header()) else None)(screen()), 8) or screen()
    check("首页没有这一行了", not s.has(header()))
    check("焦点恰好 1 个", s.count_focused() == 1)
    check("layout.json 里频道行还在", len(channel_rows()) == 1)
    open_edit()
    # 频道架子是第 5 层,进页时在屏幕外(uiautomator 不报屏外节点):先走到它的胶囊上,让它进焦点线再读字
    s = to_last_shelf_first_chip()
    check("编辑页频道架子写「暂无内容」", s.has(S("shelf_channel_empty")), s.texts()[-12:])
    key("back"); time.sleep(1.5)

    journey("频道行:撤销授权")      # Review Focus 2
    cmd("PUBLISH")
    revoke()                           # 杀进程
    restart(BASE)
    # 先等首页真的画好(第一行应用卡在、焦点恰好 1 个),再要求「没有频道行」在 2 s 里一直成立(跨过 500 ms 去抖)
    s = wait_for(lambda: (lambda s: s if s.has("爱奇艺") and s.count_focused() == 1 else None)(screen()), 10) or screen()
    check("冷启动后首页画好了(应用行在、焦点恰好 1 个)", s.has("爱奇艺") and s.count_focused() == 1, s.texts()[:8])
    check("冷启动后首页不画频道行(稳定 2 s)", stays(lambda: (lambda s: s.has("爱奇艺") and not s.has(header()))(screen()), 2.0))
    check("焦点恰好 1 个、没崩", screen().count_focused() == 1 and not fatal())
    open_edit()
    s = to_last_shelf_first_chip()     # 同上:先把屏幕外的频道架子带进焦点线
    check("编辑页频道架子写「需要重新授权」", s.has(S("shelf_channel_needs_permission")), s.texts()[-12:])
    shot("ch-05-needs-permission")
    # Task 13 复审:「重新授权」排在这一层胶囊的最后(出现 / 消失不让别的胶囊错位),一路向右走到它
    s = move_to(S("shelf_chip_reauthorize"), "right", max_steps=5, exact=True)
    check("频道架子上有「重新授权」胶囊(排在最后)", s is not None, screen().label())
    key("ok")
    need("再次弹授权窗并允许(下面的拒绝旅程靠这次 Allow 清掉 UnitedU 的 deniedBefore)", answer_permission("allow"))
    s = wait_for(lambda: (lambda s: s if not s.has(S("shelf_channel_needs_permission")) and s.count_focused() == 1 else None)(screen()), 8) or screen()
    check("授权回来后架子不再写「需要重新授权」", not s.has(S("shelf_channel_needs_permission")))
    check("焦点恰好 1 个,落这一层第一颗胶囊「上移」(「重新授权」那颗没了,不夹到「删除」)",
          s.count_focused() == 1 and s.label() == S("edit_chip_up"), s.label())
    key("back"); time.sleep(1.5)

    journey("频道行:选频道页拒绝授权(只有永久拒绝才自动跳系统设置)")   # owner 裁定 2026-10-09
    # 依赖上一段的 Allow:它清掉了 UnitedU 自己记的 deniedBefore(revoke() 清不掉),否则「返回关窗」会被当成永久拒绝
    revoke()
    restart(BASE)
    open_edit()

    def denied_here(what):
        s = wait_for(lambda: (lambda s: s if foreground() == PKG and s.has(S("channel_picker_denied")) else None)(screen()), 6) or screen()
        check(f"{what}:留在选频道页(前台是 UnitedU)、有说明、焦点在「去系统设置开启」",
              foreground() == PKG and s.has(S("channel_picker_denied")) and S("channel_picker_open_settings") in s.label(),
              (foreground(), s.label()))

    def reopen_picker():
        key("back")        # 关选页 → 落回「频道」卡
        check("返回落回「频道」卡", wait_for(lambda: S("shelf_new_channel_desc") in screen().label(), 5), screen().label())
        key("ok")

    need("走到「新的一行 → 频道」", to_new_channel_card(), screen().label())
    key("ok")
    need("授权窗弹出", wait_permission_dialog(), foreground())
    key("back")             # 从没拒绝过时按返回关窗:前后 rationale 都是 false,但不算永久拒绝
    denied_here("返回关窗")
    reopen_picker()
    check("第一次按 Don't allow", answer_permission("deny"))
    denied_here("第一次拒绝")
    shot("ch-06-picker-denied")
    reopen_picker()
    check("第二次拒绝:按「Deny and don't ask again」(弹了窗;此后系统不再询问)", answer_permission("forever"))
    check("系统记下不再询问(USER_FIXED)", "USER_FIXED" in perm_flags(), perm_flags())
    denied_here("第二次拒绝")
    reopen_picker()
    fg = wait_for(lambda: (lambda f: f if "settings" in f else None)(foreground()), 8) or foreground()
    check("永久拒绝(系统没弹窗)→ 自动跳本应用的系统设置页(com.android.tv.settings)", "settings" in fg, fg)
    key("back")
    denied_here("从系统设置返回")
    key("back"); time.sleep(1.2)           # → 「频道」卡
    key("back"); time.sleep(1.5)           # 退出编辑页
    revoke()                               # 清掉「不再询问」标记(会杀进程,下一步 restart 拉起)
    sh(f"pm grant {PKG} {PERM}")

    journey("频道行:满 5 行")
    cmd("MANY")
    lay = json.loads(json.dumps(LAYOUT))
    lay["rows"] += [{"icon": "tv", "apps": [], "channel": {"pkg": PUB, "key": f"e2e-many-{k}", "name": f"Many {k}"}} for k in range(5)]
    restart(BASE, layout=lay)
    open_edit()
    need("走到「新的一行 → 频道」", to_new_channel_card(), screen().label())
    full = S("edit_choice_full").replace("%1$d", "5")
    full_focused = lambda s: full in s.label() and S("edit_choice_app_row") not in s.label()
    need("「频道」卡写「已满 5 行」", full_focused(screen()), screen().label())
    shot("ch-07-new-row-full")
    key("ok")
    check("确定不响应:2.5 s 里焦点一直在「已满」卡上、选频道页没出现",
          stays(lambda: (lambda s: full_focused(s) and not s.has(S("channel_picker_title")))(screen()), 2.5), screen().label())

    journey("频道行:删频道架子(不弹确认)")
    key("up")                                   # 「频道」卡 → 最后一层(频道架子 Many 4)的胶囊
    go_chip("edit_chip_delete"); key("ok")
    check("频道架子直接删(删的是最后一层 Many 4)", wait_for(lambda: [r["channel"]["key"] for r in channel_rows()] == [f"e2e-many-{k}" for k in range(4)], 6),
          [r["channel"]["key"] for r in channel_rows()])
    s = wait_for(lambda: (lambda s: s if s.count_focused() == 1 else None)(screen()), 5) or screen()
    check("不弹确认", not s.has(S("edit_row_delete_confirm_title")))
    check("删后焦点落上一层(Many 3)第一颗胶囊「上移」、恰好 1 个",
          s.count_focused() == 1 and s.label() == S("edit_chip_up"), s.label())
    key("back"); time.sleep(1.5)

    journey("频道行:卸载发布方 → 布局里的频道行删掉")
    sh(f"pm uninstall {PUB}")
    check("layout.json 里没有频道行了", wait_for(lambda: channel_rows() == [], 10), channel_rows())
    s = wait_for(lambda: (lambda s: s if s.count_focused() == 1 else None)(screen()), 5) or screen()
    check("焦点恰好 1 个、前台是 UnitedU", s.count_focused() == 1 and foreground() == PKG)

if __name__ == "__main__":
    run()
    sys.exit(1 if summary() else 0)
