"""旅程:设置外壳逐组逐行(左侧说明)、选项层、滑块、子页、切语言重建、立即屏保、跳系统页再回来、关于页、恢复默认确认。"""
import sys, time, json, os
sys.path.insert(0, os.path.dirname(__file__))
from lib import *
from j_edit import open_settings


GROUPS = {
    "settings_group_general": ["settings_language", "menu_set_default_home", "settings_phone_transfer", "settings_standby", "settings_clock_display"],
    "settings_group_layout": ["menu_edit", "settings_card_size", "settings_show_titles", None, None, None],
    "settings_group_appearance": ["menu_wallpaper", "shell_slider_wallpaper_blur", "shell_slider_wallpaper_brightness", "settings_theme_color", "settings_follow_wallpaper"],
    "settings_group_screensaver": ["settings_start_screensaver", "settings_screensaver_after", "settings_screensaver_gallery",
                                   "settings_screensaver_interval", "settings_system_screensaver", "settings_screen_off"],  # R158
}

def left_texts(s):
    """左半屏(x < 960 px)上的文字:页名、路径、说明。"""
    return [n["text"] for n in s.nodes if n["text"] and n["b"][2] <= 960]

def settings_json():
    return pull_json("settings.json") or {}

def run():
    journey("settings-setup")
    restart(settings_patch={"language": "en", "onboardingDone": True, "cardsPerRow": 6, "cardSaturation": 30}, layout=LAYOUT)
    open_settings()
    s = screen()
    for k in ["settings_group_general", "settings_group_layout", "settings_group_appearance", "settings_group_screensaver", "menu_system_settings", "menu_about"]:
        check(f"第一层有「{S(k)}」", s.has(S(k)))
    check("第一层默认焦点在 General", S("settings_group_general") in s.label(), s.label())

    journey("settings-descriptions")
    for g, rows in GROUPS.items():
        open_settings()
        move_to(S(g)); key("ok"); time.sleep(1.2)
        seen = []
        for i, rk in enumerate(rows):
            s = screen()
            lab = s.label()
            if rk: check(f"{S(g)} 第 {i + 1} 行是「{S(rk)}」", S(rk) in lab, lab)
            lt = [t for t in left_texts(s) if t not in (S(g), "Settings")]
            check(f"{S(g)} 第 {i + 1} 行左侧有说明", len(lt) > 0, lt)
            seen.append(" ".join(lt))
            key("down")
        check(f"{S(g)} 每行说明各不相同", len(set(seen)) == len(seen), seen)
        key("back"); time.sleep(0.8)

    journey("settings-option-layer")
    open_settings()
    move_to(S("settings_group_layout")); key("ok"); time.sleep(1.2)
    move_to(S("settings_card_size")); key("ok"); time.sleep(1.2)
    s = screen()
    check("卡片大小选项层(Small / Medium / Large)", s.has(S("settings_card_small")) and s.has(S("settings_card_large")), s.texts()[:8])
    check("选项层焦点在当前值 Medium", S("settings_card_medium") in s.label(), s.label())
    move_to(S("settings_card_large")); key("ok"); time.sleep(1.5)
    check("写盘 cardsPerRow = 5", settings_json().get("cardsPerRow") == 5, settings_json().get("cardsPerRow"))
    s = screen()
    check("回到布局页的「Card Size · Large」", S("settings_card_size") in s.label() and S("settings_card_large") in s.label(), s.label())
    key("ok"); time.sleep(1); move_to(S("settings_card_medium"), "up"); key("ok"); time.sleep(1.2)
    check("改回 6", settings_json().get("cardsPerRow") == 6, settings_json().get("cardsPerRow"))
    # 滑块
    move_to("Saturation")
    key("right", "right"); time.sleep(0.8)
    check("饱和度 +20 写盘", settings_json().get("cardSaturation") == 50, settings_json().get("cardSaturation"))
    key("left", "left"); time.sleep(0.8)
    check("饱和度复原", settings_json().get("cardSaturation") == 30, settings_json().get("cardSaturation"))
    s = screen()
    check("滑块左右键不让焦点出列", "Saturation" in s.label(), s.label())

    journey("settings-subpage")
    key("back"); time.sleep(0.8)
    move_to(S("settings_group_general"), "up"); key("ok"); time.sleep(1.2)
    move_to(S("settings_standby")); key("ok"); time.sleep(1.2)
    s = screen()
    check("闲置画面子页两行", s.has(S("settings_idle_after")) and s.has(S("settings_idle_content")), s.texts()[:8])
    key("back"); time.sleep(0.8)
    s = screen()
    check("返回落回「Idle Screen」", S("settings_standby") in s.label(), s.label())

    journey("settings-language-recreate")
    move_to(S("settings_language"), "up"); key("ok"); time.sleep(1.2)
    move_to(S("settings_lang_zh_cn"), "up"); key("ok"); time.sleep(4)
    s = screen()
    check("切到简体后重建,仍在设置里", s.has("通用") or s.has("语言"), s.texts()[:8])
    check("焦点落回「语言」", "语言" in s.label(), s.label())
    check("写盘 language = zh-CN", settings_json().get("language") == "zh-CN", settings_json().get("language"))
    shot("settings-zh")
    key("ok"); time.sleep(1.2); move_to("English", "down"); key("ok"); time.sleep(4)
    s = screen()
    check("切回英文,焦点落回 Language", S("settings_language") in s.label(), s.label())

    journey("settings-screensaver-now")
    key("back"); time.sleep(0.8)
    move_to(S("settings_group_screensaver")); key("ok"); time.sleep(1.2)
    move_to(S("settings_start_screensaver")); key("ok"); time.sleep(3)
    s = screen()
    check("立即开始屏保:设置页不见了", not s.has(S("settings_start_screensaver")), s.texts()[:5])
    shot("settings-screensaver-running")
    key("down"); time.sleep(1.5)
    s = screen()
    check("任意键退出屏保,单个焦点", s.count_focused() == 1, s.count_focused())
    # 立即开始屏保会先关掉设置(startScreensaverNow → leaveSettings),退出后回到首页(平时落在顶栏「设置」那颗;
    # 刚切过语言、Activity 重建过的话首页没有记住药丸,落第一张卡——两种都算对,只要焦点在)
    check("退出后回到首页", not s.has(S("settings_start_screensaver")), s.texts()[:4])
    open_settings()
    move_to(S("settings_group_screensaver")); key("ok"); time.sleep(1.2)

    journey("settings-system-pages")
    for rk in ["settings_system_screensaver", "settings_screen_off"]:
        move_to(S(rk)); key("ok"); time.sleep(3)
        fg = foreground()
        check(f"「{S(rk)}」跳到系统页", fg != PKG, fg)
        key("back"); time.sleep(2)
        if foreground() != PKG: key("back"); time.sleep(1.5)
        s = screen()
        check(f"从系统页返回,焦点回「{S(rk)}」", S(rk) in s.label(), (foreground(), s.label()))
    key("back"); time.sleep(0.8)
    move_to(S("menu_system_settings")); key("ok"); time.sleep(3)
    check("「TV Settings」跳到系统设置", foreground() != PKG, foreground())
    key("back"); time.sleep(2)
    if foreground() != PKG: key("back"); time.sleep(1.5)
    s = screen()
    check("返回落回「TV Settings」", S("menu_system_settings") in s.label(), (foreground(), s.label()))

    journey("settings-about")
    move_to(S("menu_about")); key("ok"); time.sleep(1.5)
    s = screen()
    check("关于页", s.has(S("about_title")), s.texts()[:6])
    check("关于页焦点在「Check for Updates」", S("about_check") in s.label(), s.label())
    key("ok"); time.sleep(5)
    s = screen()
    check("检查更新给出一句大白话结果", any(x in " ".join(s.texts()) for x in [S("about_net_failed"), S("about_bad_json"), S("about_latest"), "update"]), s.texts()[:10])
    key("down"); key("ok"); time.sleep(1.5)
    s = screen()
    check("恢复默认确认页,默认在取消", S("dialog_cancel") in s.label(), s.label())
    key("ok"); time.sleep(1.2)
    s = screen()
    check("取消后回到关于页的「Restore Defaults」", S("settings_action_restore_defaults") in s.label(), s.label())
    key("back"); time.sleep(1)
    s = screen()
    check("关于页返回落回第一层「About」", S("menu_about") in s.label(), s.label())
    home_intent(); time.sleep(1)
    ok, s = focus_stable()
    check("HOME 关外壳,单个焦点", ok, s.count_focused())

if __name__ == "__main__":
    run()
    summary()
