"""旅程:首页长按菜单 → 改名(保存 / 取消 / 清空)、换卡片图(内置名字英文)、移出一行、应用内提示条、HOME 收浮层。"""
import sys, time, json
sys.path.insert(0, __import__("os").path.dirname(__file__))
from lib import *


def first_card():
    """回到首页第一行第一张卡(焦点在卡片上)。"""
    home_intent(); time.sleep(1)
    key("down", "up")
    s = screen()
    return s

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
    long_ok(); move_to(S("card_menu_icon")); key("ok"); time.sleep(2)
    s = screen()
    check("换卡片图页标题", s.has(S("picker_card_image_title")), s.texts()[:6])
    for name in ["WeTV", "Youku", "YouTube", "iQIYI"]:
        check(f"内置卡片图英文名「{name}」", s.has(name), [t for t in s.texts()][:20])
    check("没有小写 id(wetv / iqiyi)", not s.has("wetv") and not s.has("iqiyi"))
    shot("home-card-art-en")
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

if __name__ == "__main__":
    run()
    summary()
