"""旅程:设置 → 布局 → 编辑桌面;行菜单六项、卡片菜单(移动 / 换卡片图 / 移出)、删行确认、HOME、快速键。"""
import sys, time, json, os
sys.path.insert(0, os.path.dirname(__file__))
from lib import *

PILL_SETTINGS = (116, 56, 212, 152)

def open_settings():
    home_intent(); key("down")
    for _ in range(4): key("up")
    s = screen()
    if s.focus() != PILL_SETTINGS:
        key("left", "left")
    key("ok"); time.sleep(1.2)

def open_edit():
    open_settings()
    move_to(S("settings_group_layout")); key("ok"); time.sleep(1.2)
    move_to(S("edit_title")); key("ok"); time.sleep(2)

def rows():
    return (pull_json("layout.json") or {}).get("rows", [])

def row_end():
    """在当前行一直往右,直到焦点不再移动(落在行尾的「+」)。"""
    last = None
    for _ in range(14):
        f = screen().focus()
        if f == last: return f
        last = f
        key("right")
    return last

def run():
    journey("edit-setup")
    restart(settings_patch={"language": "en", "onboardingDone": True}, layout=LAYOUT)
    open_edit()
    s = screen()
    check("编辑页标题", s.has(S("edit_title")), s.texts()[:6])
    check("编辑页单个焦点", s.count_focused() == 1, s.count_focused())
    first = s.focus()

    journey("edit-row-menu")
    key("down")                 # 第 2 行(Live 行,3 个应用)
    plus = row_end()
    key("ok"); time.sleep(1.2)
    s = screen()
    for k in ["edit_row_add_app", "edit_row_rename", "edit_row_icon", "edit_row_up", "edit_row_down", "edit_row_new", "edit_row_delete"]:
        check(f"行菜单有「{S(k)}」", s.has(S(k)))
    check("行菜单单个焦点", s.count_focused() == 1, s.count_focused())
    shot("edit-row-menu")
    # 添加应用
    key("ok"); time.sleep(2.5)
    s = screen()
    check("添加应用页", s.has(S("edit_add_app_title")), s.texts()[:6])
    check("添加应用页单个焦点", s.count_focused() == 1, s.count_focused())
    before = len(rows()[1]["apps"])
    lab = s.label()
    key("ok"); time.sleep(1.5)
    after = len(rows()[1]["apps"])
    check("加了一个应用到第 2 行", after == before + 1, (before, after, lab))
    s = screen()
    check("加完焦点回到这一行的「+」", s.focus() is not None and abs(s.focus()[1] - plus[1]) < 10, (s.focus(), plus))
    # 改行名
    plus = row_end()
    key("ok"); time.sleep(1); move_to(S("edit_row_rename")); key("ok"); time.sleep(1.2)
    s = screen()
    check("改行名页", s.has(S("edit_row_rename_heading")), s.texts()[:6])
    sh("input keyevent " + " ".join(["67"] * 20)); text_input("E2E Row"); key("enter"); time.sleep(1.5)
    check("行名写盘", rows()[1]["name"] == "E2E Row", rows()[1]["name"])
    s = screen()
    check("改名后焦点回「+」", s.focus() is not None and abs(s.focus()[1] - plus[1]) < 10, (s.focus(), plus))
    # 行图标
    key("ok"); time.sleep(1); move_to(S("edit_row_icon")); key("ok"); time.sleep(1.2)
    s = screen()
    check("行图标页", s.has(S("edit_row_icon_heading")), s.texts()[:6])
    check("行图标英文名(Learning)", s.has(S("row_icon_education")))
    icon_before = rows()[1].get("icon")
    key("right"); key("ok"); time.sleep(1.2)
    check("行图标写盘变化", rows()[1].get("icon") != icon_before, (icon_before, rows()[1].get("icon")))
    s = screen()
    check("换图标后焦点回「+」", s.focus() is not None and abs(s.focus()[1] - plus[1]) < 10, (s.focus(), plus))
    # 下移 / 上移
    name0 = [r["name"] for r in rows()]
    key("ok"); time.sleep(1); move_to(S("edit_row_down")); key("ok"); time.sleep(1.5)
    name1 = [r["name"] for r in rows()]
    check("下移一行", name1[2] == "E2E Row", name1)
    key("ok"); time.sleep(1); move_to(S("edit_row_up")); key("ok"); time.sleep(1.5)
    check("上移回来", [r["name"] for r in rows()] == name0, [r["name"] for r in rows()])
    # 新建一行 → 空行直接删(不弹框)
    n0 = len(rows())
    key("ok"); time.sleep(1); move_to(S("edit_row_new")); key("ok"); time.sleep(1.5)
    check("新建一行", len(rows()) == n0 + 1, len(rows()))
    s = screen(); newplus = s.focus()
    key("ok"); time.sleep(1); move_to(S("edit_row_delete")); key("ok"); time.sleep(1.5)
    s = screen()
    check("空行直接删、不弹确认", len(rows()) == n0 and not s.has(S("edit_row_delete_ok") + "x"), len(rows()))
    check("删空行后单个焦点", s.count_focused() == 1, s.count_focused())
    # 删非空行:确认页默认在取消
    plus = s.focus()
    key("ok"); time.sleep(1); move_to(S("edit_row_delete")); key("ok"); time.sleep(1.5)
    s = screen()
    check("非空行弹确认页", s.has("Delete “"), s.texts()[:6])
    check("确认页默认焦点在 Cancel", S("dialog_cancel") in s.label(), s.label())
    shot("edit-delete-confirm")
    key("ok"); time.sleep(1.5)
    check("取消 → 行还在", len(rows()) == n0, len(rows()))
    s = screen()
    check("取消后焦点回「+」", s.focus() == plus, (s.focus(), plus))

    journey("edit-card-menu-cross-fade")
    key("left")
    s = screen(); card = s.focus(); card_name = s.label()
    key("ok"); time.sleep(1.2)
    s = screen()
    check("卡片菜单(编辑页):Move / Change Card Art / Remove", s.has(S("card_menu_move")) and s.has(S("edit_change_image")) and s.has(S("edit_remove")))
    move_to(S("edit_change_image"))
    key("ok"); time.sleep(0.25)
    shot("edit-to-cardart-250ms")
    time.sleep(1.5)
    s = screen()
    check("进了换卡片图页", s.has(S("picker_card_image_title")), s.texts()[:6])
    check("换卡片图页单个焦点", s.count_focused() == 1, s.count_focused())
    key("back"); time.sleep(1.5)
    s = screen()
    check("返回编辑页,焦点回到那张卡(按名字比)", s.label() == card_name and s.count_focused() == 1, (s.label(), card_name))
    check("返回后菜单没有留着", not s.has(S("edit_remove")))
    # 快速:打开选图页后 300 ms 内按返回(编辑页残影还在)
    key("ok"); time.sleep(1); move_to(S("edit_change_image")); keys_fast("ok", "back", gap=0.3); time.sleep(1.5)
    s = screen()
    check("快速进出选图页 → 回到编辑页那张卡", s.label() == card_name and s.has(S("edit_title")), (s.label(), card_name))
    # 移动(搬运)
    order0 = rows()[1]["apps"][:]
    key("ok"); time.sleep(1); move_to(S("card_menu_move")); key("ok"); time.sleep(0.8)
    key("left"); key("ok"); time.sleep(1.5)
    order1 = rows()[1]["apps"]
    check("搬运左移一格并写盘", order1 != order0 and sorted(order1) == sorted(order0), (order0, order1))
    # 移出
    s = screen(); card = s.focus()
    victim = None
    key("ok"); time.sleep(1); move_to(S("edit_remove")); key("ok"); time.sleep(1.5)
    check("移出后少一个", len(rows()[1]["apps"]) == len(order1) - 1, rows()[1]["apps"])
    ok, s = focus_stable()
    check("移出后单个焦点", ok, s.count_focused())

    journey("edit-home-intent")
    key("ok"); time.sleep(1)   # 打开卡片菜单
    home_intent(); time.sleep(1.2)
    s = screen()
    check("编辑页 + 菜单时按 HOME → 回首页", not s.has(S("edit_title")) and not s.has(S("edit_remove")), s.texts()[:5])
    check("HOME 后单个焦点", s.count_focused() == 1, s.count_focused())

    journey("edit-exit-to-layout")
    open_edit()
    key("back"); time.sleep(1.5)
    s = screen()
    check("退出编辑页 → 回到布局页的「Edit Home Screen」", S("edit_title") in s.label(), s.label())
    shot("edit-exit-layout")
    home_intent()
    # 复原布局
    restart(layout=LAYOUT)

if __name__ == "__main__":
    run()
    summary()
