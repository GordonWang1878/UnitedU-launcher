"""设置取值往返:每个选项的每个取值都选一遍,核对 settings.json 与胶囊上的摘要文字;滑块到两端;开关来回。

语言(要重建)在 j_i18n.py 里逐个切;这里只管不重建的字段。
"""
import sys, time, json, os
sys.path.insert(0, os.path.dirname(__file__))
from lib import *
from j_edit import open_settings

BASE = {"language": "en", "onboardingDone": True, "showTitles": False, "cardsPerRow": 6, "idleAfterMs": 180000,
        "idleContent": "CLOCK_ONLY", "showDate": False, "themePresetId": "white", "followWallpaperColor": False,
        "screensaverAfterMs": 300000, "screensaverIntervalMs": 60000, "cardSaturation": 30, "cardBrightness": 80,
        "cardOpacity": 100, "wallpaperBlur": 0, "wallpaperBrightness": 0}

def js():
    return pull_json("settings.json") or {}

def find(sub, max_steps=8):
    """在当前一列里找标签含 sub 的胶囊:先往下,再往上。"""
    s = move_to(sub, "down", max_steps)
    if s is None:
        s = move_to(sub, "up", max_steps * 2)
    return s

def open_group(g):
    open_settings()
    move_to(S(g)); key("ok"); time.sleep(1.2)

def open_sub(g, sub_row):
    open_group(g); find(S(sub_row)); key("ok"); time.sleep(1.2)

# (组, 子页行或 None, 行 key, JSON 字段, [(选项文字, 期望值)])
SEGMENTED = [
    ("settings_group_layout", None, "settings_card_size", "cardsPerRow",
     [(S("settings_card_large"), 5), (S("settings_card_small"), 8), (S("settings_card_medium"), 6)]),
    ("settings_group_general", "settings_standby", "settings_idle_after", "idleAfterMs",
     [(S("settings_idle_off"), 0), ("1 min", 60000), ("5 min", 300000), ("10 min", 600000), ("3 min", 180000)]),
    ("settings_group_general", "settings_standby", "settings_idle_content", "idleContent",
     [(S("settings_idle_black"), "BLACK"), (S("settings_idle_nofade"), "NO_FADE"), (S("settings_idle_clock"), "CLOCK_ONLY")]),
    ("settings_group_general", None, "settings_clock_display", "showDate",
     [(S("settings_clock_time_date"), True), (S("settings_clock_time_only"), False)]),
    ("settings_group_appearance", None, "settings_theme_color", "themePresetId",
     [(S("preset_champagne"), "champagne"), (S("preset_blue"), "blue"), (S("preset_purple"), "purple"),
      (S("preset_green"), "green"), (S("preset_white"), "white")]),
    ("settings_group_screensaver", None, "settings_screensaver_after", "screensaverAfterMs",
     [(S("settings_idle_off"), 0), ("1 min", 60000), ("10 min", 600000), ("30 min", 1800000), ("5 min", 300000)]),
    ("settings_group_screensaver", None, "settings_screensaver_interval", "screensaverIntervalMs",
     [("30 s", 30000), ("5 min", 300000), ("1 min", 60000)]),
]

# (组, 行文字, 字段, 左到头的期望, 右到头的期望)
SLIDERS = [
    ("settings_group_layout", "Saturation", "cardSaturation", 0, 100),
    ("settings_group_layout", "Brightness", "cardBrightness", 50, 100),
    ("settings_group_layout", "Transparency", "cardOpacity", 100, 40),
    ("settings_group_appearance", "Blur", "wallpaperBlur", 0, 50),
    ("settings_group_appearance", "Brightness", "wallpaperBrightness", -50, 50),
]

TOGGLES = [
    ("settings_group_layout", "settings_show_titles", "showTitles"),
    ("settings_group_appearance", "settings_follow_wallpaper", "followWallpaperColor"),
]

def run():
    journey("values-setup")
    restart(settings_patch=BASE, layout=LAYOUT)

    for g, sub, row, field, opts in SEGMENTED:
        journey(f"values-{field}")
        for label, want in opts:
            if sub: open_sub(g, sub)
            else: open_group(g)
            s = find(S(row))
            if not check(f"{field}:找到「{S(row)}」", s is not None):
                break
            key("ok"); time.sleep(1.2)
            s = find(label)
            if not check(f"{field}:选项层里有「{label}」", s is not None, screen().texts()[:10]):
                key("back"); continue
            key("ok"); time.sleep(1.5)
            got = js().get(field)
            check(f"{field} = {want!r}(选「{label}」)", got == want, got)
            s = screen()
            check(f"{field}:回到「{S(row)}」胶囊,摘要写「{label}」", S(row) in s.label() and label.lower() in s.label().lower(), s.label())
            check(f"{field}:单个焦点", s.count_focused() == 1, s.count_focused())
            home_intent()

    for g, row, field, lo, hi in SLIDERS:
        journey(f"values-slider-{field}")
        open_group(g)
        s = find(row)
        if not check(f"{field}:找到滑块「{row}」", s is not None):
            continue
        keys_fast(*(["left"] * 12), gap=0.3); time.sleep(1)
        check(f"{field} 左到头 = {lo}", js().get(field) == lo, js().get(field))
        keys_fast(*(["right"] * 12), gap=0.3); time.sleep(1)
        check(f"{field} 右到头 = {hi}", js().get(field) == hi, js().get(field))
        s = screen()
        check(f"{field}:滑块吃掉左右键,焦点没出列", row.lower() in s.label().lower() and s.count_focused() == 1, s.label())
        home_intent()

    for g, row, field in TOGGLES:
        journey(f"values-toggle-{field}")
        open_group(g)
        find(S(row))
        v0 = js().get(field)
        key("ok"); time.sleep(1.2)
        v1 = js().get(field)
        check(f"{field}:确定键翻转({v0} → {v1})", v1 == (not v0), (v0, v1))
        key("ok"); time.sleep(1.2)
        check(f"{field}:再按翻回", js().get(field) == v0, js().get(field))
        s = screen()
        check(f"{field}:焦点仍在这一行", S(row) in s.label(), s.label())
        home_intent()

    journey("values-cancel-preview")
    # 布局组带预览:在选项层里移到别的档不按确定、直接返回 → 不写盘
    open_group("settings_group_layout")
    find(S("settings_card_size")); key("ok"); time.sleep(1.2)
    find(S("settings_card_small")); time.sleep(0.8)
    key("back"); time.sleep(1.2)
    check("选项层里只移动不确定就返回 → cardsPerRow 不变", js().get("cardsPerRow") == 6, js().get("cardsPerRow"))
    s = screen()
    check("返回后摘要仍是 Medium", S("settings_card_medium").lower() in s.label().lower(), s.label())
    home_intent()

    journey("values-restore-defaults")
    open_settings(); move_to(S("menu_about")); key("ok"); time.sleep(1.5)
    move_to(S("settings_action_restore_defaults")); key("ok"); time.sleep(1.2)
    move_to(S("restore_ok")); key("ok"); time.sleep(3)
    st = js()
    check("恢复默认:onboardingDone 保留", st.get("onboardingDone") is True, st.get("onboardingDone"))
    check("恢复默认:卡片大小回中", st.get("cardsPerRow") == 6, st.get("cardsPerRow"))
    check("恢复默认:语言回跟随系统", st.get("language") == "system", st.get("language"))
    ok, s = focus_stable()
    check("恢复默认后单个焦点", ok, s.count_focused())
    restart(settings_patch=BASE, layout=LAYOUT)


if __name__ == "__main__":
    run()
    summary()
