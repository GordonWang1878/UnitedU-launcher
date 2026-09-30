"""装卸应用:在各个页面开着时卸载 / 覆盖安装(含焦点所在的那一个)/ 新装,查焦点不丢、行不乱、layout.json 没写丢。

覆盖安装(`install -r`,系统发 PACKAGE_REMOVED + REPLACING 再 PACKAGE_ADDED)**不能**把应用从行里清掉;
真卸载才清(Ruling R68)。测试包用 `--user 0` 装,卸载走 `pm uninstall`(发 FULLY_REMOVED)。
"""
import sys, time, json, os
sys.path.insert(0, os.path.dirname(__file__))
from lib import *
from j_edit import open_settings, open_edit
from j_apps_inputs import to_pill

BASE = {"language": "en", "onboardingDone": True, "showTitles": False}

def rows():
    return (pull_json("layout.json") or {}).get("rows", [])

def all_apps():
    return [a for r in rows() for a in r["apps"]]

def install(pkg):
    return adb("install", "-r", "--user", "0", f"{APKS}/{pkg}.apk").stdout

def uninstall(pkg):
    return sh(f"pm uninstall {pkg}")

def fatal():
    out = sh("logcat -b crash -d") + sh("logcat -d | grep -E 'FATAL EXCEPTION|ANR in com.uniteduone'")
    return [l for l in out.splitlines() if l.strip() and not l.startswith("---------")]

def settle_and_check(tag, expect_label=None, expect_box=None):
    time.sleep(3.5)
    s = screen()
    check(f"{tag}:焦点恰好 1 个", s.count_focused() == 1, (s.count_focused(), s.texts()[:5]))
    check(f"{tag}:前台是 UnitedU", foreground() == PKG, foreground())
    fl = fatal()
    check(f"{tag}:没有崩溃", not fl, fl[:3])
    if expect_label is not None:
        check(f"{tag}:焦点还在「{expect_label}」", expect_label in s.label(), s.label())
    if expect_box is not None:
        check(f"{tag}:焦点框没动", s.focus() == expect_box, (s.focus(), expect_box))
    return s

def fresh():
    for p in ["test.dummy.app00", "test.dummy.app01", "test.dummy.app05", "test.dummy.app07"]:
        if p not in sh(f"pm list packages {p}"):
            install(p)
    sh("logcat -b all -c")
    restart(settings_patch=BASE, layout=LAYOUT)

def run():
    journey("pkg-setup")
    fresh()

    journey("pkg-home-reinstall-focused")
    home_intent(); key("down", "up")
    s = screen(); box = s.focus()
    before = rows()
    install("test.dummy.app00")
    settle_and_check("首页焦点卡覆盖安装", expect_box=box)
    check("覆盖安装不改 layout.json", rows() == before, (before[0]["apps"][:3], rows()[0]["apps"][:3] if rows() else None))

    journey("pkg-home-menu-uninstall")
    fresh()
    home_intent(); key("down", "up")
    long_ok()
    check("长按菜单开着", screen().has(S("card_menu_uninstall")))
    uninstall("test.dummy.app00")
    s = settle_and_check("菜单开着时卸掉它对应的应用")
    check("菜单开着时卸掉:app00 从 layout.json 清掉", "test.dummy.app00" not in all_apps(), rows()[0]["apps"][:4])
    check("菜单开着时卸掉:其余行不动", [r["name"] for r in rows()] == [r["name"] for r in LAYOUT["rows"]], [r["name"] for r in rows()])
    if s.has(S("card_menu_open")):
        key("ok"); time.sleep(2)
        check("在已卸载应用的菜单上按「打开」不崩", not fatal() and foreground() == PKG, (fatal()[:2], foreground()))
    install("test.dummy.app00")

    journey("pkg-home-rename-uninstall")
    fresh()
    home_intent(); key("down", "up")
    long_ok(); move_to(S("card_menu_rename")); key("ok"); time.sleep(1.2)
    uninstall("test.dummy.app00")
    time.sleep(3)
    key("back"); key("back"); time.sleep(1)
    settle_and_check("改名页开着时卸掉那个应用")
    install("test.dummy.app00")

    journey("pkg-home-move-mode-uninstall")
    # 首页原地移动态(M4b):正搬着的那张卡的应用被卸载
    fresh()
    home_intent(); key("down", "up")
    long_ok(); move_to(S("card_menu_move")); key("ok"); time.sleep(1)
    key("right"); time.sleep(0.6)
    check("进了移动态(底部提示条)", any("Move" in t and "Drop" in t for t in screen().texts()), screen().texts()[-3:])
    uninstall("test.dummy.app00")
    s = settle_and_check("移动态中被搬的应用被卸载")
    key("ok"); time.sleep(1.5)
    s = settle_and_check("移动态中卸载后再按确定(放下)")
    check("移动态中卸载:app00 清掉、其余顺序不变", rows()[0]["apps"] == LAYOUT["rows"][0]["apps"][1:], rows()[0]["apps"])
    key("right"); key("down"); key("up")
    ok, s = focus_stable()
    check("之后方向键照常", ok, s.count_focused())
    install("test.dummy.app00")

    journey("pkg-home-move-mode-other-uninstall")
    # 移动态中,同一行别的应用被卸载(被搬的卡位置要跟着变)
    fresh()
    home_intent(); key("down", "up")
    long_ok(); move_to(S("card_menu_move")); key("ok"); time.sleep(1)
    key("right"); key("right"); time.sleep(0.6)      # app00 搬到第 3 格
    uninstall("test.dummy.app01")
    settle_and_check("移动态中同一行另一个应用被卸载")
    key("ok"); time.sleep(1.5)
    settle_and_check("放下")
    apps = rows()[0]["apps"]
    check("放下后:app01 没了、app00 还在、没有重复", "test.dummy.app01" not in apps and apps.count("test.dummy.app00") == 1
          and sorted(apps) == sorted(a for a in LAYOUT["rows"][0]["apps"] if a != "test.dummy.app01"), apps)
    install("test.dummy.app01")

    journey("pkg-edit-focused-card")
    fresh()
    open_edit()
    s = screen(); lab = s.label()
    install("test.dummy.app00")
    settle_and_check("编辑页焦点卡覆盖安装", expect_label=lab)
    check("编辑页覆盖安装:layout 不变", rows()[0]["apps"] == LAYOUT["rows"][0]["apps"], rows()[0]["apps"])
    uninstall("test.dummy.app00")
    settle_and_check("编辑页焦点卡被卸载")
    check("编辑页卸载:app00 清掉、其余顺序不变", rows()[0]["apps"] == LAYOUT["rows"][0]["apps"][1:], rows()[0]["apps"])
    key("right"); key("down"); key("up")
    ok, s = focus_stable()
    check("编辑页卸载后还能正常移动焦点", ok, s.count_focused())
    install("test.dummy.app00")
    home_intent()

    journey("pkg-edit-card-menu")
    fresh()
    open_edit(); key("ok"); time.sleep(1.2)
    check("编辑页卡片菜单开着", screen().has(S("edit_remove")))
    uninstall("test.dummy.app00")
    s = settle_and_check("编辑页卡片菜单开着时卸掉那张卡")
    # B-06:菜单认包名,那张卡没了菜单就收掉;按坐标认的旧写法会把菜单悄悄换成下一张卡(app01)
    check("卡片菜单随被卸载的那张卡一起收掉(不换成下一张卡)", not s.has(S("edit_remove")), [t for t in s.texts() if t][:6])
    if s.has(S("edit_remove")):
        move_to(S("edit_remove")); key("ok"); time.sleep(1.5)
        check("在已卸载应用的菜单上按「移出」不崩、不误删别的卡", not fatal() and rows()[0]["apps"] == LAYOUT["rows"][0]["apps"][1:], rows()[0]["apps"])
    install("test.dummy.app00")
    home_intent()

    journey("pkg-edit-add-app-list")
    fresh()
    open_edit(); key("down")
    for _ in range(8): key("right")
    key("ok"); time.sleep(1.2); move_to(S("edit_row_add_app")); key("ok"); time.sleep(2.5)
    s = screen(); lab = s.label()
    check("添加应用列表开着", s.has(S("edit_add_app_title")), s.texts()[:4])
    install("test.dummy.app18")
    settle_and_check("添加应用列表开着时新装一个应用")
    uninstall("test.dummy.app18")
    settle_and_check("添加应用列表开着时卸掉一个")
    key("back"); time.sleep(1.2)
    home_intent()

    journey("pkg-apps-page-focused")
    fresh()
    to_pill("apps"); key("ok"); time.sleep(2)
    key("right"); s = screen(); box = s.focus(); lab = s.label()
    install("test.dummy.app01")
    settle_and_check("应用页焦点卡覆盖安装", expect_box=box)
    long_ok()
    check("应用页菜单开着", screen().has(S("card_menu_open")))
    victim_label = lab
    uninstall("test.dummy.app01")
    settle_and_check("应用页菜单开着时卸掉那个应用")
    key("back"); time.sleep(1)
    settle_and_check("应用页菜单关掉后")
    install("test.dummy.app01")
    home_intent()

    journey("pkg-settings-and-pickers")
    fresh()
    open_settings()
    move_to(S("settings_group_layout")); key("ok"); time.sleep(1.2)
    s = screen(); lab = s.label()
    uninstall("test.dummy.app05")
    settle_and_check("设置外壳(带预览)开着时卸载", expect_label=lab.split(" | ")[0] if lab else None)
    install("test.dummy.app05")
    settle_and_check("设置外壳(带预览)开着时新装")
    home_intent()
    open_settings()
    move_to(S("settings_group_appearance")); key("ok"); time.sleep(1.2)
    move_to(S("menu_wallpaper")); key("ok"); time.sleep(2)
    uninstall("test.dummy.app07")
    settle_and_check("换壁纸页开着时卸载")
    install("test.dummy.app07")
    home_intent()

    journey("pkg-inputs-page")
    fresh()
    to_pill("inputs"); key("ok"); time.sleep(2)
    s = screen(); lab = s.label()
    install("test.dummy.app00")
    settle_and_check("输入源页开着时覆盖安装一个应用", expect_label=lab)
    home_intent()

    journey("pkg-final-layout")
    fresh()
    check("收尾:所有测试包都装回来,layout 与测试布局一致", rows() == LAYOUT["rows"], [r["apps"][:3] for r in rows()])


if __name__ == "__main__":
    run()
    summary()
