"""三语文字溢出:en / zh-CN / zh-TW 各走一遍主要页面,uiautomator 找两类问题并截图留给人看:
1. 有字的节点 bounds 超出屏幕(1920×1080);
2. 有字的节点比它所在的可聚焦 / 可点击祖先(胶囊、卡片、按钮)更宽或更高(字溢出了胶囊)。
Compose 的 maxLines + 省略号截断在 uiautomator 里看不出来(语义里是全文),截断要看截图。
"""
import sys, time, json, os, re
import xml.etree.ElementTree as ET
sys.path.insert(0, os.path.dirname(__file__))
from lib import *
from j_overlays import (o_settings_root, o_group, o_standby, o_about, o_restore_confirm, o_apps, o_apps_menu1, o_inputs,
                        o_home_menu, o_home_rename, o_edit, o_edit_row_menu, o_wallpaper, o_gallery, o_import, o_default_home,
                        o_lang_options, o_standby_options)

W, H_ = 1920, 1080
LANGS = ["en", "zh-CN", "zh-TW"]
PAGES = [
    ("home", lambda: (home_intent(), key("down", "up"))),
    ("settings", o_settings_root), ("layout", o_group("settings_group_layout")), ("general", o_group("settings_group_general")),
    ("appearance", o_group("settings_group_appearance")), ("screensaver", o_group("settings_group_screensaver")),
    ("standby", o_standby), ("default-home", o_default_home), ("about", o_about), ("restore", o_restore_confirm),
    ("apps-menu", o_apps_menu1), ("home-menu", o_home_menu), ("rename", o_home_rename),
    ("edit-row-menu", o_edit_row_menu), ("gallery", o_gallery), ("import", o_import),
]

def tree():
    sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
    x = sh("cat /sdcard/ui.xml")
    i = x.find("<?xml")
    try:
        return ET.fromstring(x[i:]) if i >= 0 else None
    except ET.ParseError:
        return None

def bounds(n):
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", n.get("bounds", ""))
    return tuple(int(v) for v in m.groups()) if m else None

def problems(root):
    out = []
    def walk(n, box_anc):
        b = bounds(n)
        txt = n.get("text") or n.get("content-desc") or ""
        is_box = n.get("clickable") == "true" or n.get("focusable") == "true"
        if txt and b:
            x0, y0, x1, y1 = b
            if x1 > W + 1 or y1 > H_ + 1 or x0 < -1 or y0 < -1:
                # 纵向滚出屏幕的网格行 / 列表项是刻意的(自算位移),只报横向越界
                if x1 > W + 1 or x0 < -1:
                    out.append(("off-screen-x", txt[:40], b))
            if box_anc and not is_box:
                a0, b0, a1, b1 = box_anc
                if (x1 - x0) > (a1 - a0) + 2 or x0 < a0 - 2 or x1 > a1 + 2:
                    out.append(("wider-than-capsule", txt[:40], b, box_anc))
        nb = b if (is_box and b) else box_anc
        for c in n:
            walk(c, nb)
    walk(root, None)
    return out

def run():
    report = {}
    for lang in LANGS:
        journey(f"i18n-{lang}")
        set_lang(lang)      # 导航用的标签跟着界面语言换
        restart(settings_patch={"language": lang, "onboardingDone": True, "showTitles": True}, layout=LAYOUT)
        for name, opener in PAGES:
            try:
                restart(layout=LAYOUT) if name.startswith("edit") else home_intent()
                opener(); time.sleep(0.8)
                root = tree()
                if root is None:
                    check(f"{lang}/{name}:读得到界面树", False); continue
                probs = problems(root)
                shot(f"i18n-{lang}-{name}")
                report[f"{lang}/{name}"] = probs
                check(f"{lang}/{name}:没有字越出屏幕 / 胶囊", not probs, probs[:3])
            except Exception as e:
                check(f"{lang}/{name}:脚本异常", False, repr(e))
    json.dump(report, open(f"{OUT}/i18n-report.json", "w"), ensure_ascii=False, indent=1)
    set_lang("en")
    restart(settings_patch={"language": "en", "showTitles": False}, layout=LAYOUT)


if __name__ == "__main__":
    run()
    summary()
