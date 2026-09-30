"""旅程:从手机添加(扫码页 + 上传服务)——上传 / 拒收 / 删除 / 落点 / 关页即停(前台、后台)/ 快速重开端口 / 屏保视频预览与删除。"""
import sys, time, json, os, re, subprocess
sys.path.insert(0, os.path.dirname(__file__))
from lib import *
from j_edit import open_settings

H = ["-H", "X-Requested-With: UnitedU"]
# 每台模拟器一个本机端口(emulator-5560 → 18150):两台模拟器并行跑时互不抢端口(2026-09-30 测试轮)
LOCAL = 18090 + (int(DEV.rsplit("-", 1)[1]) % 100 if DEV.startswith("emulator-") else 0)

def forward_from_screen():
    s = screen()
    addr = next((t for t in s.texts() if re.search(r"\d+\.\d+\.\d+\.\d+:\d+", t)), None)
    port = int(re.search(r":(\d+)", addr).group(1)) if addr else None
    adb("forward", "--remove", f"tcp:{LOCAL}")   # 只拆自己的:--remove-all 会把别的设备的转发一起拆掉
    if port: adb("forward", f"tcp:{LOCAL}", f"tcp:{port}")
    return port

def curl(*args, timeout=15):
    r = subprocess.run(["curl", "-s", "-m", str(timeout), "-o", "/dev/stderr", "-w", "%{http_code}", *args],
                       capture_output=True, text=True)
    return r.stdout.strip(), r.stderr

def url(path): return f"http://127.0.0.1:{LOCAL}{path}"

def up(path):  # 服务是否还开着:能连上 = 有 HTTP 状态码
    code, _ = curl(url(path), timeout=3)
    return code not in ("000", "")

def open_wallpaper_picker():
    open_settings()
    move_to(S("settings_group_appearance")); key("ok"); time.sleep(1.2)
    move_to("Change Wallpaper"); key("ok"); time.sleep(2)

def run():
    journey("upload-setup")
    restart(settings_patch={"language": "en", "onboardingDone": True, "wallpaperFile": ""}, layout=LAYOUT)
    sh(f"rm -f {FILES}/library/wallpapers/e2e-* {FILES}/library/screensavers/e2e-*")
    open_wallpaper_picker()
    s = screen()
    check("换壁纸页", s.has(S("picker_wallpaper_title")), s.texts()[:5])
    key("down")
    s = screen()
    check("第二块第一格是「＋ 从手机添加」", S("picker_add_from_phone") in s.label(), s.label())
    plus = s.focus()
    key("ok"); time.sleep(2.5)
    s = screen()
    check("扫码页(壁纸)标题", s.has(S("import_title_wallpapers")), s.texts()[:6])
    port = forward_from_screen()
    check("扫码页显示地址 + 端口 8090", port == 8090, port)
    shot("upload-import-page")

    journey("upload-web-page")
    code, body = curl(url("/"))
    check("网页 200", code == "200", code)
    check("网页注入了文案(没有残留占位符)", all(p not in body for p in ["__STRINGS__", "__ACCENT__", "__LANG__", "__DEFAULT_TAB__"]),
          [p for p in ["__STRINGS__", "__ACCENT__", "__LANG__", "__DEFAULT_TAB__"] if p in body])
    check("网页语言跟电视(en)", 'lang="en"' in body)
    code, body = curl(url("/api/list?type=wallpapers"))
    check("列表接口 200", code == "200", code)

    journey("upload-wallpaper")
    code, _ = curl("-X", "POST", "-F", f"files=@{FX}/e2e-sunset.jpg", url("/api/upload?type=wallpapers"))
    check("不带 X-Requested-With 的上传被拒(4xx)", code.startswith("4"), code)
    code, body = curl("-X", "POST", *H, "-F", f"files=@{FX}/e2e-sunset.jpg", url("/api/upload?type=wallpapers"))
    check("上传照片 200", code == "200", (code, body[:200]))
    check("回执里 saved 有这张", "e2e-sunset" in body, body[:200])
    time.sleep(1)
    s = screen()
    check("电视上写「1 file received」", s.has("1 file received"), [t for t in s.texts() if "received" in t or "Latest" in t])
    code, body = curl("-X", "POST", *H, "-F", f"files=@{FX}/notes.txt", url("/api/upload?type=wallpapers"))
    check("txt 被拒收(reason type)", '"type"' in body, body[:200])
    code, body = curl("-X", "POST", *H, "-H", "Content-Length: 999999999", "--data-binary", "@" + f"{FX}/notes.txt",
                      url("/api/upload?type=wallpapers"))
    check("声明长度超限 → 413", code == "413", (code, body[:120]))
    key("back"); time.sleep(2)
    s = screen()
    check("关扫码页 → 焦点落在刚传的那张", "e2e-sunset" in s.label(), s.label())
    check("关页之后服务已停(前台)", not up("/api/list?type=wallpapers"))
    key("ok"); time.sleep(2)
    st = pull_json("settings.json")
    check("选用刚传的壁纸", st.get("wallpaperFile") == "e2e-sunset.jpg", st.get("wallpaperFile"))
    s = screen()
    check("选完回到外观页(单个焦点)", s.count_focused() == 1, s.count_focused())

    journey("upload-quick-reopen-and-background")
    move_to("Change Wallpaper"); key("ok"); time.sleep(2)
    key("down")
    # 格子里第二块现在有两格(＋、e2e-sunset),＋在第一格
    s = screen()
    if S("picker_add_from_phone") not in s.label():
        key("left")
    key("ok"); time.sleep(2.5)
    port1 = forward_from_screen()
    check("扫码页开着服务在", up("/api/list?type=wallpapers"), port1)
    keys_fast("back", "ok", gap=0.35)      # 关页后 0.35 s 内重开(旧页还在淡出)
    time.sleep(2.5)
    s = screen()
    port2 = forward_from_screen()
    check("快速关了再开:仍是 8090(旧服务已停,没占着端口)", port2 == 8090, (port1, port2))
    check("重开后服务在", up("/api/list?type=wallpapers"))
    # 后台:切到别的应用
    sh("am start -n test.dummy.app00/android.app.Activity")
    time.sleep(3)
    check("切到别的应用后前台不是 UnitedU", foreground() != PKG, foreground())
    check("在后台关页 → 服务已停(复审 Critical)", not up("/api/list?type=wallpapers"))
    home_intent(); time.sleep(1.5)
    ok, s = focus_stable()
    check("回到桌面单个焦点", ok, s.count_focused())
    check("回来时扫码页没有残影闪一下(页面已关)", not s.has(S("import_title_wallpapers")))

    journey("upload-screensaver-video")
    open_settings()
    move_to(S("settings_group_screensaver")); key("ok"); time.sleep(1.2)
    move_to(S("settings_screensaver_gallery")); key("ok"); time.sleep(2)
    s = screen()
    check("屏保图库页", s.has(S("picker_screensaver_title")), s.texts()[:5])
    key("down"); time.sleep(0.5)
    s = screen()
    if S("picker_add_from_phone") not in s.label():
        move_to(S("picker_add_from_phone"), "left", 6)
    key("ok"); time.sleep(2.5)
    s = screen()
    check("扫码页(屏保)标题", s.has(S("import_title_screensavers")), s.texts()[:6])
    forward_from_screen()
    code, body = curl("-X", "POST", *H, "-H", "Content-Type: application/octet-stream", "--data-binary", f"@{FX}/e2e-clip.mp4",
                      url("/api/upload-raw?type=screensavers&name=e2e-clip.mp4"))
    check("上传视频 200", code == "200" and "e2e-clip" in body, (code, body[:200]))
    key("back"); time.sleep(2.5)
    s = screen()
    check("回到图库,焦点落在视频", "e2e-clip" in s.label(), s.label())
    check("视频格有时长角标(▶ 0:03)", "▶" in s.label(), s.label())
    key("ok"); time.sleep(0.3)
    # 名字只显示 3 s,而视频在放时 uiautomator 要等界面空闲,宿主负载高时一次读屏 2 s 多:先睡久了就读不到
    s = screen()
    check("全屏预览左下角写名字", any(t.startswith("e2e-clip") for t in s.texts()), s.texts()[:5])
    check("预览单个焦点", s.count_focused() == 1, s.count_focused())
    shot("upload-video-preview")
    key("back"); time.sleep(1.5)
    s = screen()
    check("关预览回到那一格", "e2e-clip" in s.label(), s.label())
    long_ok(); time.sleep(0.5)
    s = screen()
    check("长按我的图 → 删除确认页,默认在取消", S("dialog_cancel") in s.label() and s.has(S("pool_delete_title")), (s.label(), s.texts()[:4]))
    key("down"); key("ok"); time.sleep(2)
    left = sh(f"ls {FILES}/library/screensavers/ 2>/dev/null")
    check("视频文件已删", "e2e-clip" not in left, left)
    ok, s = focus_stable()
    check("删完单个焦点(落上一格或「＋」)", ok, (s.count_focused(), s.label()))

    journey("upload-builtin-names-en")
    # 图库里内置图的英文名
    for name in ["Light Above the Clouds", "Sandworm at Dusk", "Train on the Sea", "Deep Space Expedition"]:
        check(f"内置屏保英文名「{name}」", s.has(name))
    home_intent()

if __name__ == "__main__":
    run()
    summary()
