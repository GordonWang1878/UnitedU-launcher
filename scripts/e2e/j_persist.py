"""落盘健壮性:settings / layout / titles / hidden-inputs 写成损坏 JSON、空文件、类型错误、超大文件后冷启动。

期望(落盘铁律 + LockedFile.load 的口径):不崩;坏的正式文件改名 `.bad` 留证;有 `.prev` 就从它恢复(用户的布局不被
换成默认);两份都坏才写回默认值;设置坏了不重新弹引导(layout.json 在 = 老用户)。
"""
import sys, time, json, os
sys.path.insert(0, os.path.dirname(__file__))
from lib import *

BASE_SETTINGS = {"language": "en", "onboardingDone": True, "showTitles": False}
# R163:行没有名字——.prev 里的布局靠(各行的)应用列表认,与默认三行(装着的认得的应用)不会撞
PREV_LAYOUT = {"rows": [{"icon": "movie", "apps": ["test.dummy.app09", "test.dummy.app14", "test.dummy.app15"]},
                        {"icon": "kids", "apps": ["test.dummy.app00"]}]}


def push_raw(name, text):
    p = f"{OUT}/_raw_{name}"
    open(p, "w", encoding="utf-8").write(text)
    adb("push", p, f"{FILES}/{name}")


def rm(*names):
    sh("rm -f " + " ".join(f"{FILES}/{n}" for n in names))


def cold_start():
    sh("logcat -b all -c")
    home_intent()
    if foreground() != PKG:
        home_intent()
    time.sleep(2.5)
    key("down", "up")


def fatal():
    out = sh("logcat -b crash -d") + sh("logcat -d | grep -E 'FATAL EXCEPTION|ANR in com.uniteduone'")
    return [l for l in out.splitlines() if l.strip() and not l.startswith("---------")]


def exists(name):
    return sh(f"ls {FILES}/{name} 2>/dev/null").strip() != ""


def healthy(tag):
    fl = fatal()
    check(f"{tag}:没有崩溃 / ANR", not fl, fl[:4])
    check(f"{tag}:前台是 UnitedU", foreground() == PKG, foreground())
    s = screen()
    check(f"{tag}:焦点恰好 1 个", s.count_focused() == 1, s.count_focused())
    check(f"{tag}:没有弹首次引导", not s.has(S("onb_step1_title")), s.texts()[:5])
    return s


def layout_case(tag, main_text, with_prev, expect_rows_from_prev, expect_bad=True):
    sh(f"am force-stop {PKG}")
    rm("layout.json", "layout.json.prev", "layout.json.bad")
    push_json("settings.json", {**(pull_json("settings.json") or {}), **BASE_SETTINGS})
    if with_prev:
        push_raw("layout.json.prev", json.dumps(PREV_LAYOUT))
    if main_text is not None:
        push_raw("layout.json", main_text)
    cold_start()
    healthy(tag)
    lay = pull_json("layout.json")
    check(f"{tag}:layout.json 写回成可解析的文件", bool(lay and lay.get("rows")), str(lay)[:160])
    if expect_rows_from_prev:
        got = [r.get("apps") for r in (lay or {}).get("rows", [])]
        check(f"{tag}:从 .prev 恢复了用户的布局(不是默认三行)", got == [r["apps"] for r in PREV_LAYOUT["rows"]], got)
    if main_text is not None and expect_bad:
        check(f"{tag}:坏的正式文件改名 .bad 留证", exists("layout.json.bad"))
    if not expect_bad:
        check(f"{tag}:能解析的文件不改名 .bad", not exists("layout.json.bad"))


def run():
    journey("persist-setup")
    restart(settings_patch=BASE_SETTINGS, layout=LAYOUT)

    journey("persist-layout")
    layout_case("layout 乱码 + 有 .prev", "{not json", True, True)
    layout_case("layout 空文件 + 有 .prev", "", True, True)
    layout_case("layout rows 类型错(字符串)+ 有 .prev", '{"rows": "abc"}', True, True)
    layout_case("layout 零行 + 有 .prev", '{"rows": []}', True, True)
    layout_case("layout 顶层是数组 + 有 .prev", '[1,2,3]', True, True)
    layout_case("layout 截断的半截 JSON + 有 .prev", json.dumps(LAYOUT)[:57], True, True)
    layout_case("layout 超大(1.2 MB)+ 有 .prev", '{"rows":[{"icon":"' + "x" * 1_200_000 + '","apps":[]}]}', True, True)
    layout_case("layout 正式文件缺失 + 有 .prev", None, True, True)
    layout_case("layout 乱码、没有 .prev(回落默认)", "\x00\x01garbage", False, False)
    # 字段类型怪但语法合法:apps 里混进数字 / null / 对象、icon 是数字、老字段 name 也是数字 —— 读得出来,不崩
    layout_case("layout 字段类型怪(apps 混数字 / null / 对象)", json.dumps(
        {"rows": [{"name": 42, "icon": 7, "apps": ["test.dummy.app00", 1, None, {"x": 1}, "", "  test.dummy.app01  "]}]}), True, False,
        expect_bad=False)
    # 语法合法 = 照读不改写(read 不写盘),盘上仍是原样;首页照常画出第一行
    lay = pull_json("layout.json") or {}
    apps = (lay.get("rows") or [{}])[0].get("apps", [])
    check("类型怪的行:文件没被换成默认", "test.dummy.app00" in apps, apps)

    journey("persist-settings")
    for tag, text in [("settings 乱码", "{{{"), ("settings 空文件", ""), ("settings 顶层是数组", "[]"),
                      ("settings 字段类型全错", json.dumps({"language": 5, "cardsPerRow": "big", "idleAfterMs": -1,
                                                          "themePresetId": 123, "wallpaperFile": "../../../etc/passwd",
                                                          "showTitles": "yes", "rowCount": 99, "cardOpacity": -5,
                                                          "onboardingDone": True})),
                      ("settings 数值越界", json.dumps({"language": "en", "onboardingDone": True, "idleAfterMs": 12345,
                                                     "screensaverAfterMs": 99999999999, "wallpaperBlur": 999, "cardSaturation": 1000,
                                                     "wallpaperBrightness": -999, "newAppsSeenAt": -5}))]:
        sh(f"am force-stop {PKG}")
        push_json("layout.json", LAYOUT)
        rm("settings.json", "settings.json.prev", "settings.json.bad")
        push_raw("settings.json", text)
        cold_start()
        healthy(tag)
        st = pull_json("settings.json")
        check(f"{tag}:settings.json 写回成合法 JSON", isinstance(st, dict), str(st)[:120])
        if isinstance(st, dict):
            check(f"{tag}:壁纸文件名不含路径", "/" not in st.get("wallpaperFile", "") and ".." not in st.get("wallpaperFile", ""), st.get("wallpaperFile"))
            check(f"{tag}:idleAfterMs 在五档之内", st.get("idleAfterMs") in (0, 60000, 180000, 300000, 600000), st.get("idleAfterMs"))
            check(f"{tag}:layout 没被换成默认", [(r.get("icon"), r.get("apps")) for r in (pull_json("layout.json") or {}).get("rows", [])] == [(r["icon"], r["apps"]) for r in LAYOUT["rows"]])

    journey("persist-titles-hidden")
    for tag, name, text in [("titles 乱码", "titles.json", "{oops"), ("titles 类型错(数字值)", "titles.json", '{"test.dummy.app00": 5, "test.dummy.app01": null}'),
                            ("titles 顶层数组", "titles.json", "[1]"), ("hidden-inputs 乱码", "hidden-inputs.json", "}}"),
                            ("hidden-inputs 空文件", "hidden-inputs.json", "")]:
        sh(f"am force-stop {PKG}")
        push_json("settings.json", {**(pull_json("settings.json") or {}), **BASE_SETTINGS})
        push_json("layout.json", LAYOUT)
        rm(name, name + ".prev", name + ".bad")
        push_raw(name, text)
        cold_start()
        healthy(tag)
        # 顶栏「输入源」页要读 hidden-inputs;打开一次再关,确认不崩
        if name.startswith("hidden"):
            for _ in range(4): key("up")
            key("right"); key("right"); key("ok"); time.sleep(1.5)
            s = screen()
            check(f"{tag}:输入源页打得开、单个焦点", s.count_focused() == 1, s.texts()[:5])
            key("back"); time.sleep(1)
            check(f"{tag}:打开输入源页后仍没有崩溃", not fatal(), fatal()[:3])
        rm(name, name + ".bad")

    journey("persist-cleanup")
    sh(f"am force-stop {PKG}")
    rm("layout.json.bad", "settings.json.bad", "titles.json.bad", "hidden-inputs.json.bad", "titles.json", "hidden-inputs.json")
    restart(settings_patch=BASE_SETTINGS, layout=LAYOUT)
    ok, s = focus_stable()
    check("收尾:推回测试布局后单个焦点", ok, s.count_focused())


if __name__ == "__main__":
    run()
    summary()
