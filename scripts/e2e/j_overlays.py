"""每个浮层按 HOME / 按返回,系统性逐个过:浮层收干净、焦点恰好 1 个、落点合理。

每一行 = (名字, 打开它的函数, 「开着」的判据, 返回后的期望落点)。打开函数返回「返回后应落回的东西」(焦点框或标签),
没有特别期望时返回 None(只查单个焦点 + 浮层已关)。
HOME 一律期望:回到首页(看不到设置 / 编辑页 / 浮层的字),焦点恰好 1 个。引导例外(HOME 不跳过引导,见文末)。
"""
import sys, time, json, os
sys.path.insert(0, os.path.dirname(__file__))
from lib import *
from j_edit import open_settings, open_edit, row_end, PILL_SETTINGS
from j_apps_inputs import to_pill, PILLS

BASE = {"language": "en", "onboardingDone": True, "showTitles": False}


def has_exact(s, t):
    """整段文字等于 t 的节点(has() 是子串匹配:第一层的说明文字里就含「Language」「Wallpaper」这些词)。"""
    return any((n["text"] or n["desc"]) == t for n in s.nodes)


def apps_page_open(s):
    """认应用页页头下的那行提示(apps_page_hint)。页名「All Apps」不行:首页顶栏「应用」胶囊的 content-desc 就是它
    (R133–R145 时焦点名字还以 text 画在胶囊右边;R146 起名字在按钮正下方、只是画面,不进无障碍树)。"""
    return any(n["text"] == S("apps_page_hint") for n in s.nodes)


def open_group(g):
    open_settings()
    move_to(S(g)); key("ok"); time.sleep(1.2)

def open_row(g, r):
    open_group(g)
    s = move_to(S(r)); key("ok"); time.sleep(1.5)
    return s.label() if s else None

def first_card():
    """第一行第一张卡。HOME 之后焦点可能在顶栏某颗胶囊上(关掉应用页 / 输入源页回到打开它的那颗),
    「下、上」会又回到胶囊,长按确定就成了点开设置——先上到顶栏,再下一行、左到头。"""
    home_intent()
    for _ in range(4): key("up", gap=0.4)
    key("down")
    keys_fast(*(["left"] * 9), gap=0.2); time.sleep(0.8)
    return screen().focus()

# ---------------- 打开函数:返回返回键之后应落回的东西 ----------------
def o_settings_root():
    open_settings(); return ("box", PILL_SETTINGS)

def o_group(g):
    def f():
        open_group(g); return ("label", S(g))
    return f

def o_lang_options():
    open_row("settings_group_general", "settings_language"); return ("label", S("settings_language"))

def o_default_home():
    open_row("settings_group_general", "menu_set_default_home"); return ("label", S("menu_set_default_home"))

def o_standby():
    open_row("settings_group_general", "settings_standby"); return ("label", S("settings_standby"))

def o_standby_options():
    open_row("settings_group_general", "settings_standby")
    move_to(S("settings_idle_after")); key("ok"); time.sleep(1.2)
    return ("label", S("settings_idle_after"))

def o_about():
    open_settings(); move_to(S("menu_about")); key("ok"); time.sleep(1.5)
    return ("label", S("menu_about"))

def o_restore_confirm():
    o_about(); move_to(S("settings_action_restore_defaults")); key("ok"); time.sleep(1.5)
    return ("label", S("settings_action_restore_defaults"))

def o_apps():
    to_pill("apps"); key("ok"); time.sleep(2); return ("box", PILLS["apps"])

def o_apps_menu1():
    o_apps(); key("right"); card = screen().focus(); long_ok(); return ("box", card)

def o_apps_menu2():
    r = o_apps_menu1(); move_to(S("apps_menu_add_to_home")); key("ok"); time.sleep(1.2); return r

def o_inputs():
    to_pill("inputs"); key("ok"); time.sleep(2); return ("box", PILLS["inputs"])

def o_inputs_menu():
    o_inputs(); lab = screen().label(); long_ok(); return ("label", lab)

def o_inputs_rename():
    r = o_inputs_menu(); move_to(S("input_menu_rename")); key("ok"); time.sleep(1.2); return r

def o_home_menu():
    c = first_card(); long_ok(); return ("box", c)

def o_home_rename():
    r = o_home_menu(); move_to(S("card_menu_rename")); key("ok"); time.sleep(1.2); return r

def o_home_cardart():
    r = o_home_menu(); move_to(S("card_menu_icon")); key("ok"); time.sleep(2); return r

def o_edit():
    open_edit(); return ("label", S("edit_title"))

def o_edit_card_menu():
    open_edit(); c = screen().focus(); key("ok"); time.sleep(1.2); return ("box", c)

def _edit_row_menu():
    # 第 2 行(3 个应用,不横向滚动):第 1 行 8 张会横滚,焦点框停在同一处,row_end() 会以为到头了
    open_edit(); key("down"); plus = row_end(); key("ok"); time.sleep(1.2); return ("box", plus)

def o_edit_row_menu():
    return _edit_row_menu()

def o_edit_add_app():
    r = _edit_row_menu(); move_to(S("edit_row_add_app")); key("ok"); time.sleep(2.5); return r

def o_edit_rename_row():
    r = _edit_row_menu(); move_to(S("edit_row_rename")); key("ok"); time.sleep(1.2); return r

def o_edit_row_icon():
    r = _edit_row_menu(); move_to(S("edit_row_icon")); key("ok"); time.sleep(1.2); return r

def o_edit_delete_confirm():
    r = _edit_row_menu(); move_to(S("edit_row_delete")); key("ok"); time.sleep(1.5); return r

def o_edit_cardart():
    open_edit(); s = screen(); lab = s.label(); key("ok"); time.sleep(1.2)
    move_to(S("edit_change_image")); key("ok"); time.sleep(2); return ("label", lab)

def o_wallpaper():
    open_row("settings_group_appearance", "menu_wallpaper"); return ("label", S("menu_wallpaper"))

def o_gallery():
    open_row("settings_group_screensaver", "settings_screensaver_gallery"); return ("label", S("settings_screensaver_gallery"))

def o_import():
    o_wallpaper(); move_to(S("picker_add_from_phone"), "down", 6); key("ok"); time.sleep(2.5)
    return ("label", S("picker_add_from_phone"))

def o_gallery_preview():
    o_gallery(); lab = screen().label(); key("ok"); time.sleep(1.2); return ("label", lab)

def o_gallery_builtin_menu():
    o_gallery(); lab = screen().label(); long_ok(); return ("label", lab)

def o_gallery_delete():
    o_gallery(); s = move_to("e2e-ocean", "down", 8)
    if s is None: s = move_to("e2e-ocean", "right", 6)
    lab = screen().label(); long_ok(); return ("label", lab)

# (名字, 打开, 开着的判据)
CASES = [
    ("设置第一层", o_settings_root, lambda s: s.has(S("settings_group_general")) and s.has(S("menu_about"))),
    ("设置·通用", o_group("settings_group_general"), lambda s: has_exact(s, S("settings_language"))),
    ("设置·布局", o_group("settings_group_layout"), lambda s: has_exact(s, S("settings_card_size"))),
    ("设置·外观", o_group("settings_group_appearance"), lambda s: has_exact(s, S("menu_wallpaper"))),
    ("设置·屏保", o_group("settings_group_screensaver"), lambda s: has_exact(s, S("settings_screensaver_gallery"))),
    ("设置·语言选项层", o_lang_options, lambda s: s.has(S("settings_lang_zh_tw"))),
    ("设置·默认桌面页", o_default_home, lambda s: s.has(S("home_settings_change_button"))),
    ("设置·闲置画面子页", o_standby, lambda s: s.has(S("settings_idle_after")) and s.has(S("settings_idle_content"))),
    ("设置·闲置时长选项层", o_standby_options, lambda s: s.has(S("settings_idle_off"))),
    ("关于页", o_about, lambda s: s.has(S("about_title"))),
    ("恢复默认确认页", o_restore_confirm, lambda s: s.has(S("restore_title"))),
    ("所有应用页", o_apps, apps_page_open),
    ("所有应用页·菜单第一层", o_apps_menu1, lambda s: s.has(S("card_menu_open")) and s.has(S("apps_menu_add_to_home"))),
    # 第二层的胶囊是 text 节点;首页行名(在应用页底下仍被 uiautomator 报出来)只是行图标的 content-desc
    ("所有应用页·菜单第二层", o_apps_menu2, lambda s: not s.has(S("apps_menu_add_to_home")) and any(n["text"] == LAYOUT["rows"][0]["name"] for n in s.nodes)),
    ("输入源页", o_inputs, lambda s: s.count_focused() == 1 and s.focus()[0] > 1000),
    ("输入源·胶囊菜单", o_inputs_menu, lambda s: s.has(S("input_menu_rename"))),
    ("输入源·改名页", o_inputs_rename, lambda s: s.has(S("title_dialog_hint_input"))),
    ("首页·长按菜单", o_home_menu, lambda s: s.has(S("card_menu_uninstall"))),
    ("首页·改名页", o_home_rename, lambda s: s.has(S("title_dialog_hint"))),
    ("首页·换卡片图", o_home_cardart, lambda s: s.has(S("picker_card_image_title"))),
    ("编辑页", o_edit, lambda s: s.has(S("edit_title")) and s.has(S("edit_hint")[:20])),
    ("编辑·卡片菜单", o_edit_card_menu, lambda s: s.has(S("edit_remove"))),
    ("编辑·行菜单", o_edit_row_menu, lambda s: s.has(S("edit_row_add_app")) and s.has(S("edit_row_delete"))),
    ("编辑·添加应用", o_edit_add_app, lambda s: s.has(S("edit_add_app_title")) and not s.has(S("edit_row_delete"))),
    ("编辑·改行名", o_edit_rename_row, lambda s: s.has(S("edit_row_rename_hint"))),
    ("编辑·行图标", o_edit_row_icon, lambda s: s.has(S("edit_row_icon_heading"))),
    ("编辑·删行确认", o_edit_delete_confirm, lambda s: s.has("Delete “")),
    ("编辑·换卡片图", o_edit_cardart, lambda s: s.has(S("picker_card_image_title"))),
    ("换壁纸页", o_wallpaper, lambda s: s.has(S("picker_wallpaper_title"))),
    # 设置·屏保页上有一颗同名胶囊(右栏 x ≥ 1172,图库开着时它仍在树里),只认左上的页头「Screensaver Gallery · N items」
    ("屏保图库", o_gallery, lambda s: any(n["text"].startswith(S("picker_screensaver_title")) and n["b"][0] < 1000 for n in s.nodes)),
    ("扫码页", o_import, lambda s: s.has(S("import_title_wallpapers"))),
    ("图库·全屏预览", o_gallery_preview, lambda s: s.focus() is not None and s.focus()[2] - s.focus()[0] > 1800),
    ("图库·内置图菜单", o_gallery_builtin_menu, lambda s: s.has(S("pool_builtin_exclude")) or s.has(S("pool_builtin_include"))),
    ("图库·删除确认", o_gallery_delete, lambda s: s.has(S("pool_delete_title"))),
]

OVERLAY_WORDS = ["settings_group_general", "edit_title", "card_menu_uninstall", "picker_wallpaper_title", "picker_screensaver_title",
                 "picker_card_image_title", "about_title", "restore_title", "edit_row_add_app", "pool_delete_title", "title_dialog_hint",
                 "import_title_wallpapers", "input_menu_rename"]

def on_bare_home(s):
    left = [S(k) for k in OVERLAY_WORDS if s.has(S(k))]
    if apps_page_open(s): left.append(S("apps_page_hint"))
    return not left, left

def landed(s, expect):
    if expect is None: return True
    kind, v = expect
    if kind == "box": return s.focus() == v
    return v.lower() in s.label().lower()

def run():
    journey("overlays-setup")
    restart(settings_patch=BASE, layout=LAYOUT)
    # 图库删除确认要一张「我的」图
    sh(f"mkdir -p {FILES}/library/screensavers")
    adb("push", f"{FX}/e2e-ocean.jpg", f"{FILES}/library/screensavers/e2e-ocean.jpg")
    only = [x for x in os.environ.get("OVERLAYS_ONLY", "").split(",") if x]     # 只重跑几个:OVERLAYS_ONLY=所有应用页,扫码页
    for name, opener, is_open in CASES:
        if only and name not in only:
            continue
        for how in ("home", "back"):
            journey(f"{name}·{'HOME' if how == 'home' else '返回'}")
            try:
                restart(layout=LAYOUT) if name.startswith("编辑") else home_intent()
                expect = opener()
                s = screen()
                if not check(f"{name}:打开了", is_open(s), s.texts()[:6]):
                    continue
                check(f"{name}:开着时焦点恰好 1 个", s.count_focused() == 1, s.count_focused())
                if how == "home":
                    home_intent(); time.sleep(1.2)
                    s = screen()
                    ok, left = on_bare_home(s)
                    check(f"{name}:HOME 收干净(回到首页)", ok, left)
                    check(f"{name}:HOME 后焦点恰好 1 个", s.count_focused() == 1, s.count_focused())
                else:
                    key("back")
                    if name.endswith("改名页") or name == "编辑·改行名":
                        key("back")    # 第一下收输入法
                    time.sleep(1.2)
                    s = screen()
                    check(f"{name}:返回后浮层关了", not is_open(s), s.texts()[:6])
                    check(f"{name}:返回后焦点恰好 1 个", s.count_focused() == 1, s.count_focused())
                    check(f"{name}:返回落回打开它的地方", landed(s, expect), (expect, s.focus(), s.label()))
            except Exception as e:
                check(f"{name}:脚本异常", False, repr(e))
    sh(f"rm -f {FILES}/library/screensavers/e2e-ocean.jpg")
    if only and "引导" not in only:
        return
    run_onboarding()


def run_onboarding():
    journey("onboarding-home-back")
    restart(settings_patch={"onboardingDone": False}, layout=LAYOUT)
    s = screen()
    check("引导第 1 步出现", s.has(S("onb_step1_title")), s.texts()[:5])
    home_intent(); time.sleep(1.2)
    s = screen()
    check("引导里按 HOME:引导还在(HOME 不跳过引导)、单个焦点", s.has(S("onb_step1_title")) and s.count_focused() == 1, (s.count_focused(), s.texts()[:4]))
    # 第 1 步初始焦点在当前语言(English,列表最后一项);但 restart() 末尾的「下、上」唤醒键会把它挪到「繁體」——先走回 English 再确定
    move_to("English", "down", 3)
    key("ok"); time.sleep(2)
    s = screen()
    check("第 2 步", s.has(S("onb_step2_title")), s.texts()[:5])
    home_intent(); time.sleep(1.2)
    s = screen()
    check("第 2 步按 HOME:仍在第 2 步、单个焦点", s.has(S("onb_step2_title")) and s.count_focused() == 1, (s.count_focused(), s.texts()[:4]))
    # 第 2 步 → 第 3 步(按「跳过」;人为造的「已有布局 + onboardingDone=false」下它会写回空的默认三行,收尾时 restart 推回测试布局):
    # HOME 仍在第 3 步;返回回第 2 步
    s = move_to(S("onb_skip"), "down", 4)
    key("ok"); time.sleep(2)
    s = screen()
    check("第 3 步", s.has(S("onb_step3_title")), s.texts()[:5])
    home_intent(); time.sleep(1.2)
    s = screen()
    check("第 3 步按 HOME:仍在第 3 步、单个焦点", s.has(S("onb_step3_title")) and s.count_focused() == 1, (s.count_focused(), s.texts()[:4]))
    key("back"); time.sleep(1.5)
    s = screen()
    check("第 3 步返回 → 第 2 步", s.has(S("onb_step2_title")) and s.count_focused() == 1, s.texts()[:4])
    key("back"); time.sleep(1.5)
    s = screen()
    check("第 2 步返回 → 第 1 步", s.has(S("onb_step1_title")) and s.count_focused() == 1, s.texts()[:4])
    key("back"); time.sleep(1.5)
    s = screen()
    check("第 1 步返回 → 结束引导、回首页单个焦点", not s.has(S("onb_step1_title")) and s.count_focused() == 1, (s.count_focused(), s.texts()[:4]))
    st = pull_json("settings.json") or {}
    check("结束引导写 onboardingDone = true", st.get("onboardingDone") is True, st.get("onboardingDone"))
    restart(settings_patch=BASE, layout=LAYOUT)


if __name__ == "__main__":
    run()
    summary()
