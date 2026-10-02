"""旅程:全新安装(pm clear)→ 首次引导三步(简体):语言、放到桌面(计划列表:行图标 + 应用名,R163 起没有行名)、返回上一步、完成;默认三行的图标写盘。"""
import sys, time, json, os
sys.path.insert(0, os.path.dirname(__file__))
from lib import *


def run():
    journey("onb-fresh-install")
    out = sh(f"pm clear {PKG}")
    check("pm clear 成功", "Success" in out, out)
    time.sleep(1)
    check("外置目录已清空", "No such file" in sh(f"ls {FILES}/ 2>&1"))
    sh(f"cmd package set-home-activity {PKG}/.MainActivity")
    home_intent(); time.sleep(3)
    if foreground() != PKG: home_intent(); time.sleep(2)
    key("down", "up")
    s = screen()
    check("引导第 1 步:选择语言", s.has(S("onb_step1_title")), s.texts()[:6])
    check("第 1 步焦点在当前语言「System Default」", S("settings_lang_system") in s.label(), s.label())
    # 选简体
    move_to(S("settings_lang_zh_cn")); key("ok"); time.sleep(4)
    s = screen()
    check("选简体 → 重建后进第 2 步(中文)", s.has("把已安装的应用放到桌面"), s.texts()[:6])
    check("第 2 步焦点在「放到桌面」", "放到桌面" in s.label(), s.label())
    shot("onb-step2-zh")
    # R163:计划列表每行 = 行图标 + 应用名,没有行名(图标只能看截图;这里认应用名)
    for n in ["云视听极光", "央视频TV", "网易云音乐TV"]:
        check(f"计划列表有「{n}」", s.has(n))
    check("计划列表没有行名(R163)", not any(s.has(n) for n in ["影视", "直播"]), s.texts()[:12])
    key("back"); time.sleep(1.5)
    s = screen()
    check("返回 → 回到第 1 步,焦点在已选的「简体」", "简体" in s.label(), s.label())
    key("ok"); time.sleep(2)
    s = screen()
    check("在第 1 步再按确定(语言不变)→ 第 2 步", s.has("把已安装的应用放到桌面"), s.texts()[:4])
    key("ok"); time.sleep(2)
    s = screen()
    check("放到桌面 → 第 3 步", s.has("把 UnitedU 设为默认桌面"), s.texts()[:6])
    lay = pull_json("layout.json") or {}
    rows = lay.get("rows", [])
    check("写盘:每行只有 icon + apps,没有 name(R163)", all(set(r) == {"icon", "apps"} for r in rows), rows[:3])
    check("写盘:三行图标 movie / tv / music", [r.get("icon") for r in rows][:3] == ["movie", "tv", "music"], [r.get("icon") for r in rows])
    check("写盘:三个认得的应用各进各的行", len(rows) >= 3 and "com.ktcp.video" in rows[0]["apps"] and "com.newtv.cboxtv" in rows[1]["apps"]
          and "com.netease.cloudmusic.tv" in rows[2]["apps"], rows)
    move_to("完成"); key("ok"); time.sleep(2)
    s = screen()
    check("完成 → 首页,单个焦点", s.count_focused() == 1 and not s.has("把 UnitedU 设为默认桌面"), s.count_focused())
    st = pull_json("settings.json") or {}
    check("onboardingDone 写盘", st.get("onboardingDone") is True, st.get("onboardingDone"))
    shot("onb-home-zh")

    journey("onb-restore-test-state")
    restart(settings_patch={"language": "en", "onboardingDone": True}, layout=LAYOUT)
    ok, s = focus_stable()
    check("复原测试布局,单个焦点", ok, s.count_focused())

if __name__ == "__main__":
    run()
    summary()
