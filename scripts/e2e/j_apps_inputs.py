"""旅程:所有应用页(两层菜单、加到桌面、打开应用再返回)、输入源页(假调谐器:改名 / 隐藏 / 恢复 / 切换)、装卸应用时焦点不丢。"""
import sys, time, json, os
sys.path.insert(0, os.path.dirname(__file__))
from lib import *

PILLS = {"settings": (116, 56, 212, 152), "apps": (196, 56, 292, 152), "inputs": (276, 56, 372, 152)}

def to_pill(name):
    home_intent(); key("down")
    for _ in range(4): key("up")
    for _ in range(3):
        s = screen()
        if s.focus() == PILLS[name]: return True
        key("right" if s.focus() and s.focus()[0] < PILLS[name][0] else "left")
    return screen().focus() == PILLS[name]

def rows():
    return (pull_json("layout.json") or {}).get("rows", [])

def run():
    journey("apps-setup")
    restart(settings_patch={"language": "en", "onboardingDone": True}, layout=LAYOUT)
    check("走到顶栏「应用」", to_pill("apps"))
    key("ok"); time.sleep(2)
    s = screen()
    check("所有应用页", s.has("All Apps"), s.texts()[:5])
    check("所有应用页单个焦点", s.count_focused() == 1, s.count_focused())
    first = s.focus(); first_label = s.label()

    journey("apps-menu-add-to-row")
    key("right"); s = screen(); card = s.focus(); name = s.label()
    long_ok()
    s = screen()
    check("长按 → 菜单第一项 Open App", S("card_menu_open") in s.label(), s.label())
    check("菜单有 Add to Home…", s.has(S("apps_menu_add_to_home")))
    move_to(S("apps_menu_add_to_home")); key("ok"); time.sleep(1.2)
    s = screen()
    names = [r["name"] for r in rows()]
    check("第二层列出各行", all(s.has(n) for n in names), (names, s.texts()[:8]))
    target_row = names[-1]
    move_to(target_row); key("ok", gap=0.2)
    # 提示条只显示 3.5 s:紧跟着确定键读屏(宿主负载高时一次读屏就要 2 s 多,先睡再读会错过)
    s = screen()
    check("出现应用内提示条(Added to … / already in …)", any(("Added to" in t or "already in" in t) for t in s.texts()), [t for t in s.texts() if "dded" in t or "lready" in t])
    time.sleep(0.8)
    s = screen()
    check("加好后菜单关掉、焦点回那张卡", s.focus() == card, (s.focus(), card))
    shot("apps-toast")

    journey("apps-open-and-back")
    key("ok"); time.sleep(2.5)
    fg = foreground()
    check("确定键打开应用", fg != PKG, fg)
    key("back"); time.sleep(2)
    if foreground() != PKG: key("back"); time.sleep(1.5)
    s = screen()
    check("返回 → 所有应用页,焦点回那张卡", s.focus() == card and s.has("All Apps"), (s.focus(), card))

    journey("apps-package-events")
    adb("install", "-r", f"{APKS}/test.dummy.app18.apk"); time.sleep(3)
    ok, s = focus_stable()
    check("在应用页时装一个新应用 → 焦点不丢", ok, s.count_focused())
    check("焦点仍在那张卡", s.focus() == card, (s.focus(), card))
    sh("pm uninstall test.dummy.app18"); time.sleep(3)
    ok, s = focus_stable()
    check("卸掉一个 → 焦点不丢", ok, s.count_focused())
    key("back"); time.sleep(1.2)
    s = screen()
    check("返回首页,落回「应用」药丸", s.focus() == PILLS["apps"], s.focus())

    journey("home-package-events")
    home_intent(); key("down", "up")
    key("right"); s = screen(); c2 = s.focus(); lab = s.label()
    # 卸掉焦点那张卡对应的应用(第一行第二张 = test.dummy.app01)
    victim = rows()[0]["apps"][1]
    sh(f"pm uninstall {victim}"); time.sleep(3.5)
    ok, s = focus_stable()
    check(f"首页焦点所在的应用被卸载({victim})→ 焦点不丢", ok, (s.count_focused(), s.label()))
    adb("install", "-r", f"{APKS}/{victim}.apk"); time.sleep(3)
    ok, s = focus_stable()
    check("再装回来 → 焦点不丢", ok, s.count_focused())

    journey("inputs-page")
    restart(layout=LAYOUT)
    check("走到顶栏「输入源」", to_pill("inputs"))
    key("ok"); time.sleep(2)
    s = screen()
    check("输入源页有假调谐器那一颗", s.count_focused() == 1 and s.label() and s.label() != S("dialog_cancel"), s.label())
    orig = s.label()
    shot("inputs-page")
    long_ok()
    s = screen()
    check("长按 → 胶囊菜单(Rename / Hide)", s.has(S("input_menu_rename")), s.texts()[:6])
    move_to(S("input_menu_rename")); key("ok"); time.sleep(1.2)
    sh("input keyevent " + " ".join(["67"] * 25)); text_input("E2E Antenna"); key("enter"); time.sleep(1.5)
    s = screen()
    check("改名生效、焦点回这一颗", "E2E Antenna" in s.label(), s.label())
    long_ok(); move_to("Hide"); key("ok"); time.sleep(1.5)
    s = screen()
    capsules = [n["text"] for n in s.nodes if n["text"] and n["b"][0] >= 1100]
    check("隐藏后那一颗不见,焦点落「恢复隐藏」", "E2E Antenna" not in capsules and "Restore Hidden" in s.label(), (s.label(), capsules))
    key("ok"); time.sleep(1.5)
    s = screen()
    check("恢复后焦点回到那一颗", "E2E Antenna" in s.label(), s.label())
    # 清掉改名,换回原名(改名页清空 = 恢复系统名)
    long_ok(); move_to(S("input_menu_rename")); key("ok"); time.sleep(1.2)
    sh("input keyevent " + " ".join(["67"] * 25)); key("enter"); time.sleep(1.5)
    s = screen()
    check("清空改名 → 恢复系统名", orig in s.label(), (orig, s.label()))
    key("ok"); time.sleep(3)
    check("确定键切换到这个输入(假直播应用接住)", foreground() != PKG, foreground())
    home_intent(); time.sleep(1.5)
    ok, s = focus_stable()
    check("回桌面单个焦点", ok, s.count_focused())

if __name__ == "__main__":
    run()
    summary()
