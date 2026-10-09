"""旅程:设置 → 布局 → 编辑桌面(R165 货架)。
胶囊(添加应用 / 换图标 / 上移 / 下移 / 删除)、确定拿起 → 左右 / 下一层 → 放下、返回取消、长按与菜单键 = 卡片菜单
(换卡片图 / 移出这一行;长按松手不误拿起、不误点菜单)、删非空行确认默认在取消、新的一行 → 应用行(满 5 行变暗不响应)、
空行直接删、菜单键不退出编辑页、HOME、退出落回「编辑桌面」。"""
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
    """在当前这一条(胶囊或卡片)里一直往右,直到焦点不再移动。"""
    last = None
    for _ in range(14):
        f = screen().focus()
        if f == last: return f
        last = f
        key("right")
    return last

def lane_start():
    """回到当前这一条的最左一格(到头 Cancel,多按无害)。"""
    for _ in range(12): key("left", gap=0.3)

def chip_labels():
    """焦点在某层胶囊上:从最左一颗起往右,记下每颗的字。"""
    lane_start()
    labels, last = [], None
    for _ in range(7):
        s = screen(); f = s.focus()
        if f == last: break
        labels.append(s.label()); last = f
        key("right")
    return labels

def go_chip(k):
    """焦点在某层胶囊上:走到字为 S(k) 的那颗。"""
    lane_start()
    return move_to(S(k), "right", max_steps=6, exact=True)

def full_text():
    return S("edit_choice_full").replace("%1$d", "5")

def run():
    journey("edit-setup")
    restart(settings_patch={"language": "en", "onboardingDone": True}, layout=LAYOUT)
    open_edit()
    s = screen()
    check("编辑页标题", s.has(S("edit_title")), s.texts()[:6])
    check("编辑页单个焦点", s.count_focused() == 1, s.count_focused())
    check("页头按键提示「拿起卡片」", s.has(S("edit_hint_pick")), s.texts()[:12])
    check("初始焦点在第 1 层第 1 张卡", s.label() not in ("", S("edit_row_add_app")), s.label())
    shot("edit-shelves-default")

    journey("edit-chips")
    key("up")
    s = screen()
    check("卡片按上 → 本层离得最近的胶囊(第一颗)", s.label() == S("edit_row_add_app"), s.label())
    labs = chip_labels()
    check("第 1 层胶囊:添加应用 / 换图标 / 下移 / 删除(没有上移)",
          labs == [S("edit_row_add_app"), S("edit_chip_icon"), S("edit_chip_down"), S("edit_chip_delete")], labs)
    # 添加应用
    go_chip("edit_row_add_app"); key("ok"); time.sleep(2.5)
    s = screen()
    check("添加应用页单个焦点", s.count_focused() == 1, s.count_focused())
    before = len(rows()[0]["apps"]); lab = s.label()
    key("ok"); time.sleep(1.5)
    check("加了一个应用到第 1 行", len(rows()[0]["apps"]) == before + 1, (before, len(rows()[0]["apps"])))
    time.sleep(1.0)
    s = screen()
    # 列表那一项的字可能多一个「New」角标:按「卡片名包含在列表项里」认
    check("加完焦点落在新卡上", s.count_focused() == 1 and s.label() != "" and s.label() in lab, (s.label(), lab))
    # 换图标
    key("up"); go_chip("edit_chip_icon"); key("ok"); time.sleep(1.2)
    s = screen()
    check("行图标页", s.has(S("edit_row_icon_heading")), s.texts()[:6])
    icon_before = rows()[0].get("icon")
    key("right"); key("ok"); time.sleep(1.2)
    check("行图标写盘变化", rows()[0].get("icon") != icon_before, (icon_before, rows()[0].get("icon")))
    s = screen()
    check("换完图标焦点回「换图标」胶囊", s.label() == S("edit_chip_icon"), s.label())
    check("写盘每行只有 icon + apps", all(set(r) == {"icon", "apps"} for r in rows()), rows()[:2])
    # 下移 / 上移
    before = rows(); mine = before[0]
    go_chip("edit_chip_down"); key("ok"); time.sleep(1.5)
    after = rows()
    check("下移一层", after[1] == mine and after[0] == before[1], (mine, after[:2]))
    s = screen()
    check("下移后焦点跟着这一层、仍在「下移」", s.label() == S("edit_chip_down"), s.label())
    go_chip("edit_chip_up"); key("ok"); time.sleep(1.5)
    check("上移回来", rows() == before, rows()[:2])
    s = screen()
    check("回到第一层没有「上移」→ 焦点落「下移」", s.label() == S("edit_chip_down"), s.label())

    journey("edit-pick-up")
    key("down"); time.sleep(0.4)          # 胶囊 → 本层卡片(离「下移」最近的那张)
    s = screen(); card = s.label()
    order0 = rows()[0]["apps"][:]
    key("ok"); time.sleep(0.8)
    s = screen()
    check("确定 = 拿起:页头提示换成「放下」", s.has(S("edit_hint_drop")), s.texts()[:12])
    shot("edit-carry")
    key("left"); key("ok"); time.sleep(1.5)
    order1 = rows()[0]["apps"]
    check("左移一格并写盘", order1 != order0 and sorted(order1) == sorted(order0), (order0, order1))
    s = screen()
    check("放下后焦点还在这张卡", s.label() == card and s.count_focused() == 1, (s.label(), card))
    key("ok"); time.sleep(0.6); key("right"); key("back"); time.sleep(1.2)
    check("返回取消:不写盘", rows()[0]["apps"] == order1, rows()[0]["apps"])
    s = screen()
    check("取消后焦点回出发那张卡、仍在编辑页", s.label() == card and s.has(S("edit_title")), (s.label(), card))
    n0, n1 = len(rows()[0]["apps"]), len(rows()[1]["apps"])
    key("ok"); time.sleep(0.6); key("down"); key("ok"); time.sleep(1.5)
    check("下搬到第 2 层并写盘", len(rows()[0]["apps"]) == n0 - 1 and len(rows()[1]["apps"]) == n1 + 1,
          (len(rows()[0]["apps"]), len(rows()[1]["apps"])))
    s = screen()
    check("搬下去之后焦点还在这张卡", s.label() == card, (s.label(), card))

    journey("edit-card-menu")
    order_before = rows()
    long_ok()
    s = screen()
    check("长按 = 卡片菜单:换卡片图 / 移出这一行", s.has(S("edit_change_image")) and s.has(S("edit_remove")), s.texts()[-6:])
    # 按整串相等判:菜单底下的编辑页节点仍在 uiautomator 树里,焦点层胶囊「Move Up / Move Down」含子串「Move」,s.has 会误报
    check("卡片菜单里没有「移动位置」(R165:确定就是拿起)", S("card_menu_move") not in s.texts(), s.texts()[-6:])
    check("长按松手不误拿起(没有「放下」提示、没写盘)", not s.has(S("edit_hint_drop")) and rows() == order_before, s.texts()[:12])
    check("长按松手不误点菜单第一项(没进换卡片图页)", not s.has(S("picker_card_image_title")), s.texts()[:6])
    check("卡片菜单单个焦点", s.count_focused() == 1, s.count_focused())
    shot("edit-card-menu")
    move_to(S("edit_change_image")); key("ok"); time.sleep(2)
    s = screen()
    check("进了换卡片图页", s.has(S("picker_card_image_title")), s.texts()[:6])
    key("back"); time.sleep(1.5)
    s = screen()
    check("返回编辑页、焦点回到那张卡", s.label() == card and s.count_focused() == 1, (s.label(), card))
    long_ok(); move_to(S("edit_change_image")); keys_fast("ok", "back", gap=0.3); time.sleep(1.5)
    s = screen()
    check("快速进出选图页 → 回到那张卡", s.label() == card and s.has(S("edit_title")), (s.label(), card))
    key("menu"); time.sleep(1.2)
    s = screen()
    check("菜单键 = 卡片菜单", s.has(S("edit_remove")), s.texts()[-6:])
    key("menu"); time.sleep(1.2)
    s = screen()
    check("再按菜单键收菜单、焦点回卡、编辑页还在",
          s.label() == card and s.has(S("edit_title")) and not s.has(S("edit_remove")), (s.label(), s.texts()[:4]))
    key("up"); chip = screen().label()
    key("menu"); time.sleep(1.0)
    s = screen()
    check("焦点在胶囊上时菜单键什么都不做(不退出)",
          chip != "" and s.label() == chip and s.has(S("edit_title")) and not s.has(S("edit_remove")), (s.label(), chip))
    key("down"); time.sleep(0.4)
    n = len(rows()[1]["apps"])
    long_ok(); move_to(S("edit_remove")); key("ok"); time.sleep(1.5)
    check("移出后少一个", len(rows()[1]["apps"]) == n - 1, rows()[1]["apps"])
    ok, s = focus_stable()
    check("移出后单个焦点", ok, s.count_focused())

    journey("edit-delete-confirm")
    key("up"); go_chip("edit_chip_delete"); key("ok"); time.sleep(1.5)
    s = screen()
    check("删非空行弹确认页", s.has(S("edit_row_delete_confirm_title")), s.texts()[:6])
    check("确认页默认焦点在 Cancel", S("dialog_cancel") in s.label(), s.label())
    shot("edit-delete-confirm")
    n_rows = len(rows())
    key("ok"); time.sleep(1.5)
    check("取消 → 行还在", len(rows()) == n_rows, len(rows()))
    s = screen()
    check("取消后焦点回「删除」胶囊", s.label() == S("edit_chip_delete"), s.label())

    journey("edit-new-row")
    s = None
    for _ in range(15):   # R164 起「新的一行」有两张卡:按最近可能落到「频道」卡,向左回「应用行」
        lab = screen().label()
        if S("edit_choice_app_row") in lab:
            s = screen(); break
        key("left" if S("shelf_new_channel_desc") in lab else "down")
    check("一路按下到「新的一行」→「应用行」", s is not None, screen().label())
    s = screen()   # 「Add」是「Add App」的子串,单判它恒真:改判这一组独有的「Choose」,且「Pick up」已不在
    check("页头提示换成「选择 / 添加」", s.has(S("edit_hint_choose")) and not s.has(S("edit_hint_pick")), s.texts()[:12])
    shot("edit-new-row")
    n_rows = len(rows())
    key("ok"); time.sleep(1.5)
    check("新建一行(插在最后、空的)", len(rows()) == n_rows + 1 and rows()[-1]["apps"] == [], rows()[-1:])
    s = screen()
    check("新建后焦点落在新架子的「添加应用」方块", s.label() == S("edit_row_add_app"), s.label())
    move_to(S("edit_choice_app_row"), "down", max_steps=4)
    s = screen()
    check("满 5 行:应用行写「已满」", s.has(full_text()), s.texts()[-6:])
    shot("edit-new-row-full")
    key("ok"); time.sleep(1.2)
    check("满 5 行:确定不响应", len(rows()) == 5, len(rows()))
    key("up"); key("up")                  # 新的一行 → 第 5 层方块 → 第 5 层胶囊
    go_chip("edit_chip_delete"); key("ok"); time.sleep(1.5)
    s = screen()
    check("空行直接删、不弹确认", len(rows()) == 4 and not s.has(S("edit_row_delete_confirm_title")), len(rows()))
    check("删后焦点落上一层第一颗胶囊", s.label() == S("edit_row_add_app") and s.count_focused() == 1, s.label())

    journey("edit-home-intent")
    key("down"); time.sleep(0.4)
    long_ok()
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
    restart(layout=LAYOUT)

if __name__ == "__main__":
    run()
    summary()
