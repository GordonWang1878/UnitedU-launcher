"""旅程:首页长按菜单 → 改名(保存 / 取消 / 清空)、换卡片图(内置卡只给对应应用、名字英文,R159)、移出一行、应用内提示条、HOME 收浮层。"""
import sys, time, json
sys.path.insert(0, __import__("os").path.dirname(__file__))
from lib import *


def first_card():
    """回到首页第一行第一张卡(焦点在卡片上)。"""
    home_intent(); time.sleep(1)
    key("down", "up")
    s = screen()
    return s

def builtin_names(s):
    """选图页「内置」那一块的名字(R159)。首页还画在选图页底下、无障碍树里照样有它的卡片名(占位应用里就有一张叫 bilibili),
    所以不能整屏找字:只取「内置」与「我的」两个分组标题之间的那一段;没有「内置」一块 → 空表。"""
    t = s.texts()
    b, m = S("picker_section_builtin"), S("picker_section_mine")
    if b not in t: return []
    i = t.index(b)
    j = t.index(m, i) if m in t[i:] else len(t)
    return sorted(set(t[i + 1:j]))

def run():
    journey("home-setup")
    restart(settings_patch={"language": "en", "onboardingDone": True, "showTitles": False}, layout=LAYOUT)
    ok, s = focus_stable()
    check("冷启动后首页有且只有一个焦点", ok, s.count_focused())
    check("前台是 UnitedU", foreground() == PKG, foreground())
    card0 = s.focus()

    journey("home-card-menu")
    long_ok()
    s = screen()
    check("长按打开卡片菜单(第一项 Open App)", S("card_menu_open") in s.label(), s.label())
    labels = [t for t in s.texts()]
    for k in ["card_menu_uninstall", "card_menu_rename", "card_menu_icon", "card_menu_move", "card_menu_remove"]:
        check(f"菜单里有「{S(k)}」", s.has(S(k)))
    shot("home-menu")
    key("back"); time.sleep(0.8)
    s = screen()
    check("返回关菜单,焦点回到同一张卡", s.focus() == card0, (s.focus(), card0))

    journey("home-rename")
    long_ok()
    s = move_to(S("card_menu_rename"))
    check("走到 Rename Card", s is not None)
    key("ok"); time.sleep(1.2)
    s = screen()
    check("改名页标题", s.has(S("title_dialog_title")), s.texts()[:8])
    check("改名页焦点在输入框(单个焦点)", s.count_focused() == 1, s.count_focused())
    shot("home-rename")
    # 取消:返回键两次(第一次收输入法)
    key("back"); key("back"); time.sleep(0.8)
    s = screen()
    check("取消改名 → 焦点回到卡片", s.focus() == card0, (s.focus(), card0))
    titles = pull_json("titles.json") or {}
    check("取消不写标题", "test.dummy.app00" not in titles, titles)
    # 保存
    long_ok(); move_to(S("card_menu_rename")); key("ok"); time.sleep(1.2)
    sh("input keyevent --longpress 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67"); time.sleep(0.3)
    for _ in range(3): key("del_", gap=0.1)
    text_input("E2E Films")
    key("enter"); time.sleep(1.5)
    s = screen()
    check("保存后回到首页同一张卡", s.focus() == card0, (s.focus(), card0))
    titles = pull_json("titles.json") or {}
    check("titles.json 写入 E2E Films", titles.get("test.dummy.app00") == "E2E Films", titles)
    check("保存后出现应用内提示条 Title saved", s.has(S("toast_title_saved")), [t for t in s.texts() if "itle" in t])
    shot("home-toast-title-saved")
    time.sleep(4)
    s = screen()
    check("提示条 3.5 s 后消失", not s.has(S("toast_title_saved")))
    # 清空 → 恢复应用名
    long_ok(); move_to(S("card_menu_rename")); key("ok"); time.sleep(1.2)
    sh("input keyevent " + " ".join(["67"] * 30)); time.sleep(0.5)
    s = screen()
    shot("home-rename-empty-placeholder")
    key("enter"); time.sleep(1.5)
    titles = pull_json("titles.json") or {}
    check("清空保存 → 标题删除(恢复应用名)", "test.dummy.app00" not in titles, titles)
    s = screen()
    check("清空保存后焦点回卡片", s.focus() == card0, (s.focus(), card0))

    journey("home-card-art-names")
    # R159:内置卡只给对应的应用。第一张是占位应用 → 选图页没有「内置」一块、一张品牌卡都没有
    long_ok(); move_to(S("card_menu_icon")); key("ok"); time.sleep(2)
    s = screen()
    check("换卡片图页标题", s.has(S("picker_card_image_title")), s.texts()[:6])
    check("占位应用:没有「内置」一块(R159)", builtin_names(s) == [] and not s.has(S("picker_section_builtin")), s.texts()[:24])
    check("选图页单个焦点", s.count_focused() == 1, s.count_focused())
    key("back"); time.sleep(1)
    s = screen()
    check("返回 → 焦点回卡片", s.focus() == card0, (s.focus(), card0))

    journey("home-home-intent-closes-overlays")
    long_ok()
    home_intent(); time.sleep(1)
    s = screen()
    check("菜单开着按 HOME → 菜单收掉", not s.has(S("card_menu_uninstall")))
    check("HOME 后单个焦点", s.count_focused() == 1, s.count_focused())
    long_ok(); move_to(S("card_menu_rename")); key("ok"); time.sleep(1)
    home_intent(); time.sleep(1)
    s = screen()
    check("改名页开着按 HOME → 收掉", not s.has(S("title_dialog_title")))
    check("HOME 后单个焦点(改名)", s.count_focused() == 1, s.count_focused())

    journey("home-fast-keys-during-fade")
    first_card()
    card0 = screen().focus()
    long_ok(settle=0.2)
    keys_fast("back", "right", gap=0.15)
    time.sleep(1.2)
    s = screen()
    check("菜单淡出期间按右键 → 落在右边那张卡", s.count_focused() == 1 and s.focus() and s.focus()[0] > card0[0], (s.focus(), card0))
    keys_fast("left", gap=0.1); time.sleep(0.8)

    journey("home-card-art-youtube")
    # R159:第二行第三张是真的 YouTube(com.google.android.youtube.tv)→ 「内置」只有 YouTube 那张,名字是英文。
    # 放在最后:前面几段都从第一行第一张起步,这里走到第二行会改掉首页记住的列号。
    first_card()
    key("down"); key("right"); key("right"); time.sleep(0.8)
    yt = screen().focus()
    long_ok(); move_to(S("card_menu_icon")); key("ok"); time.sleep(2)
    s = screen()
    check("YouTube:「内置」只有 YouTube 卡(R159)", builtin_names(s) == ["YouTube"], (builtin_names(s), s.texts()[-12:]))
    check("没有小写 id(youtube)", not s.has("youtube") and not s.has("07-youtube"))
    shot("home-card-art-en")
    key("back"); time.sleep(1)
    check("返回 → 焦点回 YouTube 卡", screen().focus() == yt, (screen().focus(), yt))

    journey("home-empty-edit-button")
    # R161:一个应用都没有(引导第 2 步跳过 = 三行空)→ 提示 + 「立即前往」胶囊,焦点默认在按钮上,确定直接进编辑页
    empty = {"rows": [{"icon": i, "apps": []} for i in ("movie", "tv", "music")]}   # R163:行没有名字
    # 不用 restart():它拉起后会按一下「下、上」(唤醒 / 退出触摸模式),「上」正好把焦点从按钮送到顶栏
    sh(f"am force-stop {PKG}"); push_json("layout.json", empty); home_intent()
    if foreground() != PKG: home_intent()
    time.sleep(3)
    ok, s = focus_stable()
    check("空桌面:提示指向编辑桌面", s.has(S("home_empty_apps_hint")[:24]), s.texts()[:8])
    check("空桌面:焦点默认在「立即前往」胶囊", ok and s.label() == S("home_empty_go"), (s.count_focused(), s.label()))
    key("up"); time.sleep(0.6)
    s = screen()
    check("上 → 顶栏「设置」", s.count_focused() == 1 and "Settings" in s.label(), s.label())
    key("down"); time.sleep(0.6)
    s = screen()
    check("下 → 回到按钮", s.label() == S("home_empty_go"), s.label())
    key("ok"); time.sleep(2)
    s = screen()
    check("确定 → 进编辑页", s.has(S("edit_hint_pick")), s.texts()[:8])
    shot("home-empty-edit-page")
    key("back"); time.sleep(2)
    ok, s = focus_stable()
    check("返回 → 空桌面、焦点回按钮", ok and s.label() == S("home_empty_go"), (s.count_focused(), s.label()))
    key("up"); time.sleep(0.6); key("ok"); time.sleep(1.5)
    key("back"); time.sleep(1.5)
    ok, s = focus_stable()
    check("从「设置」打开外壳再返回 → 焦点回「设置」(冻结的顶栏目标优先)", ok and "Settings" in s.label(), s.label())
    shot("home-empty-hint")
    restart(layout=LAYOUT)

if __name__ == "__main__":
    run()
    summary()
