"""重建路径:各种浮层开着时触发一次系统配置变化(字体缩放,不在 configChanges 里 → Activity 重建),再改回;
以及浮层开着时 `am force-stop` 后冷启动。期望:不崩、焦点恰好 1 个、扫码页若没被种回服务必须已停。
"""
import sys, time, json, os
sys.path.insert(0, os.path.dirname(__file__))
from lib import *
from j_overlays import (o_settings_root, o_group, o_lang_options, o_about, o_restore_confirm, o_apps, o_apps_menu1, o_inputs,
                        o_inputs_menu, o_home_menu, o_home_rename, o_edit, o_edit_row_menu, o_edit_add_app, o_wallpaper,
                        o_gallery, o_import, o_gallery_preview, o_standby)
from j_upload import up, forward_from_screen

BASE = {"language": "en", "onboardingDone": True, "showTitles": False}

CASES = [
    ("首页", lambda: (home_intent(), key("down", "up"))),
    ("设置·语言选项层", o_lang_options), ("恢复默认确认页", o_restore_confirm), ("所有应用页·菜单", o_apps_menu1),
    ("首页·改名页", o_home_rename), ("编辑·行菜单", o_edit_row_menu), ("图库·全屏预览", o_gallery_preview), ("扫码页", o_import),
]

def fatal():
    out = sh("logcat -b crash -d") + sh("logcat -d | grep -E 'FATAL EXCEPTION|ANR in com.uniteduone'")
    return [l for l in out.splitlines() if l.strip() and not l.startswith("---------")]

def after(tag):
    time.sleep(3.5)
    s = screen()
    if s.count_focused() != 1:
        time.sleep(2); s = screen()
    check(f"{tag}:前台是 UnitedU", foreground() == PKG, foreground())
    check(f"{tag}:焦点恰好 1 个", s.count_focused() == 1, (s.count_focused(), s.texts()[:6]))
    fl = fatal()
    check(f"{tag}:没有崩溃", not fl, fl[:3])
    return s

def run():
    journey("recreate-setup")
    sh("settings put system font_scale 1.0")
    restart(settings_patch=BASE, layout=LAYOUT)
    for name, opener in CASES:
        journey(f"recreate-{name}")
        try:
            sh("logcat -b all -c")
            restart(layout=LAYOUT) if name.startswith("编辑") else home_intent()
            opener()
            if name == "扫码页":
                forward_from_screen()
            sh("settings put system font_scale 1.15")
            s = after(f"{name}·字体放大重建")
            shot(f"recreate-{name}-1.15")
            if name == "扫码页":
                page = s.has(S("import_title_wallpapers"))
                if not page:
                    check("扫码页没被种回 → 上传服务已停", not up("/api/list?type=wallpapers"))
                else:
                    port = forward_from_screen()
                    check("扫码页被种回 → 服务在", up("/api/list?type=wallpapers"), port)
            sh("settings put system font_scale 1.0")
            s = after(f"{name}·字体改回重建")
            key("down"); key("up")
            ok, s = focus_stable()
            check(f"{name}:重建后方向键照常(单个焦点)", ok, s.count_focused())
            home_intent(); time.sleep(1)
            ok, s = focus_stable()
            check(f"{name}:重建后 HOME 单个焦点", ok, s.count_focused())
        except Exception as e:
            check(f"{name}:脚本异常", False, repr(e))
            sh("settings put system font_scale 1.0")

    journey("force-stop-cold-start")
    for name, opener in [("所有应用页", o_apps), ("编辑·行菜单", o_edit_row_menu), ("扫码页", o_import)]:
        sh("logcat -b all -c")
        restart(layout=LAYOUT) if name.startswith("编辑") else home_intent()
        opener()
        sh(f"am force-stop {PKG}")
        home_intent()
        if foreground() != PKG: home_intent()
        s = after(f"{name} 开着时 force-stop → 冷启动")
        check(f"{name}:冷启动回到干净的首页", not s.has(S("settings_group_general")) and not s.has(S("edit_title")) and not any(n["text"] == S("apps_page_hint") for n in s.nodes), s.texts()[:5])
    sh("settings put system font_scale 1.0")


if __name__ == "__main__":
    run()
    summary()
