"""闲置画面与屏保计时:idleAfterMs = 1 分钟、screensaverAfterMs = 1 分钟。

期望:闲置 1 分钟后首页淡出只留时钟(卡片全透明,uiautomator 数不到焦点);按一个键唤醒,这一下被吞(焦点不动),
焦点仍在原来那张卡;再闲置 1 分钟进待机,再过 1 分钟自动开始屏保(整屏图片);按键退出屏保,同样被吞、焦点还在。
设置外壳开着时不进待机。
"""
import sys, time, json, os, subprocess
sys.path.insert(0, os.path.dirname(__file__))
from lib import *
from j_edit import open_settings

def mean_luma():
    raw = subprocess.run(["adb", "-s", DEV, "exec-out", "screencap"], capture_output=True, timeout=60).stdout
    if len(raw) < 16: return None
    w = int.from_bytes(raw[0:4], "little"); h = int.from_bytes(raw[4:8], "little")
    px = raw[16:16 + w * h * 4]
    # 抽样:每 997 个像素取一个
    tot = n = 0
    for i in range(0, len(px) - 4, 4 * 997):
        tot += (px[i] * 299 + px[i + 1] * 587 + px[i + 2] * 114) // 1000; n += 1
    return tot / max(n, 1)

def wait_quiet(sec):
    """期间不发任何按键 / 输入(uiautomator dump 也不做)。"""
    time.sleep(sec)

def run():
    journey("idle-setup")
    restart(settings_patch={"language": "en", "onboardingDone": True, "idleAfterMs": 60000, "idleContent": "CLOCK_ONLY",
                            "screensaverAfterMs": 60000, "screensaverIntervalMs": 30000}, layout=LAYOUT)
    key("right")
    s = screen(); card = s.focus()
    check("起点:单个焦点在第一行第二张", s.count_focused() == 1, s.count_focused())
    l0 = mean_luma()

    journey("idle-enter")
    wait_quiet(68)
    s = screen()
    shot("idle-clock-only")
    check("闲置 1 分钟后:卡片淡出(uiautomator 数不到焦点)", s.count_focused() == 0, s.count_focused())
    check("闲置画面上还有时钟", any(":" in t for t in s.texts()), s.texts()[:6])

    journey("idle-wake")
    key("right"); time.sleep(1.2)
    s = screen()
    check("唤醒:单个焦点", s.count_focused() == 1, s.count_focused())
    check("唤醒键被吞:焦点没往右走", s.focus() == card, (s.focus(), card))
    key("right"); time.sleep(0.8)
    s = screen()
    check("唤醒后下一下正常生效", s.focus() != card and s.count_focused() == 1, (s.focus(), card))
    key("left"); time.sleep(0.8)

    journey("screensaver-auto")
    wait_quiet(66)       # 进待机
    l_idle = mean_luma()
    wait_quiet(66)       # 再过 1 分钟:屏保
    l_ss = mean_luma()
    shot("screensaver-auto")
    s = screen()
    check("待机 1 分钟后自动开始屏保(画面变亮 / 换成整屏图片)", l_ss is not None and l_idle is not None and l_ss > l_idle + 15,
          (l0, l_idle, l_ss))
    key("down"); time.sleep(1.5)
    s = screen()
    check("按键退出屏保:单个焦点", s.count_focused() == 1, s.count_focused())
    check("退出屏保的那一下被吞:焦点还在原来那张卡", s.focus() == card, (s.focus(), card))

    journey("idle-not-in-settings")
    open_settings()
    wait_quiet(68)
    s = screen()
    check("设置外壳开着时不进待机(焦点还在)", s.count_focused() == 1, s.count_focused())
    home_intent()

    journey("idle-restore")
    restart(settings_patch={"idleAfterMs": 180000, "screensaverAfterMs": 300000}, layout=LAYOUT)


if __name__ == "__main__":
    run()
    summary()
