"""上传服务的边界:怪文件名(中文 / emoji / 空格 / ../ / 超长 / 只差大小写)、一次 20 张、> 30 MB 被拒、并发两个上传、
上传中途关掉电视上的扫码页、删掉刚传的那张(关页后的落点)、删掉正在用的壁纸。

图库目录在外置存储上,Android 11+ 的 /sdcard 是**不区分大小写**的(ext4 casefold;模拟器实测 `ls PHOTO.JPG` 找得到
photo.jpg)——同名只差大小写的上传不能覆盖已有的那张。
"""
import sys, time, json, os, re, subprocess, threading
sys.path.insert(0, os.path.dirname(__file__))
from lib import *
from j_upload import forward_from_screen, curl, url, up, open_wallpaper_picker, H

LIB = f"{FILES}/library/wallpapers"


def http_code(u, timeout=15):
    """只要状态码(响应体可能是二进制,不能按文本解)。"""
    r = subprocess.run(["curl", "-s", "-m", str(timeout), "-o", "/dev/null", "-w", "%{http_code}", u], capture_output=True, text=True)
    return r.stdout.strip()


def quote(name):
    import urllib.parse
    return urllib.parse.quote(name)


def lib_names():
    return [l for l in sh(f"ls -1 {LIB} 2>/dev/null").splitlines() if l.strip()]


def listed():
    code, body = curl(url("/api/list?type=wallpapers"))
    try:
        return json.loads(body)
    except Exception:
        return {"raw": body[:200]}


def upload(path, name=None, typ="wallpapers"):
    spec = f"files=@{path}" + (f";filename={name}" if name else "")
    code, body = curl("-X", "POST", *H, "-F", spec, url(f"/api/upload?type={typ}"), timeout=120)
    try:
        return code, json.loads(body)
    except Exception:
        return code, {"raw": body[:300]}


def saved(resp):
    s = resp.get("saved") if isinstance(resp, dict) else None
    return s if isinstance(s, list) else []


def make_big_jpeg(p, mb):
    """一张能解码的 JPEG,尾部垫零字节到 mb MB(JPEG 解码器忽略 EOI 之后的字节)。"""
    from PIL import Image
    Image.new("RGB", (640, 360), (90, 30, 160)).save(p, quality=80)
    with open(p, "ab") as f:
        f.write(b"\0" * (mb * 1024 * 1024))


def open_import():
    open_wallpaper_picker()
    move_to(S("picker_add_from_phone"), "down", 6)
    key("ok"); time.sleep(2.5)
    return forward_from_screen()


def run():
    journey("upload-edge-setup")
    restart(settings_patch={"language": "en", "onboardingDone": True, "wallpaperFile": ""}, layout=LAYOUT)
    sh(f"rm -f {LIB}/*")
    port = open_import()
    check("扫码页开着、服务在", port is not None and up("/api/list?type=wallpapers"), port)
    img = f"{FX}/e2e-sunset.jpg"

    journey("upload-edge-names")
    cases = [("日落 海边.jpg", "日落 海边.jpg"), ("🌅 sunset 😀.jpg", "🌅 sunset 😀.jpg"), ("my  photo (1).JPG", "my  photo (1).JPG"),
             ("../../../../data/evil.jpg", "evil.jpg"), ("..\\..\\win.jpg", "win.jpg"), ("a" * 150 + ".jpg", "a" * 96 + ".jpg")]
    for raw, want in cases:
        code, resp = upload(img, raw)
        got = saved(resp)
        check(f"文件名「{raw[:30]}」→ 存为「{want[:30]}」", code == "200" and got == [want], (code, resp))
        if got:
            check(f"「{want[:20]}」真的在图库目录里", want in lib_names(), lib_names()[:10])
    # 超长中文名:100 个汉字 = 300 字节,超过 ext4 的 255 字节文件名上限
    long_cn = "长" * 120 + ".jpg"
    code, resp = upload(img, long_cn)
    got = saved(resp)
    check("超长中文名(120 个汉字)也能存下(截短到文件系统放得下)", code == "200" and len(got) == 1, (code, resp))
    if got:
        check("超长中文名:落盘名在目录里", got[0] in lib_names(), (got[0][:20], len(got[0].encode())))
        check("超长中文名:落盘名 ≤ 255 字节", len(got[0].encode("utf-8")) <= 255, len(got[0].encode("utf-8")))
    # 超长 emoji 名:截断处不能劈开代理对(前面垫一个 a,让第 96 个 UTF-16 单元正好落在一对代理的中间)
    long_emoji = "a" + "😀" * 60 + ".jpg"
    code, resp = upload(img, long_emoji)
    got = saved(resp)
    check("超长 emoji 名能存下", code == "200" and len(got) == 1, (code, resp))
    if got:
        check("超长 emoji 名:落盘名在目录里(没有劈开的半个字符)", got[0] in lib_names(), got[0][:12])
        code = http_code(url("/thumb?type=wallpapers&name=" + quote(got[0])))
        check("超长 emoji 名:缩略图接口 200", code == "200", code)
    code = http_code(url("/thumb?type=wallpapers&name=" + quote("日落 海边.jpg")))
    check("中文名的缩略图接口 200", code == "200", code)

    journey("upload-edge-case-collision")
    code, resp = upload(img, "Beach.jpg")
    check("先传 Beach.jpg", saved(resp) == ["Beach.jpg"], resp)
    before = sh(f"md5sum {LIB}/Beach.jpg").split()[0] if "Beach.jpg" in lib_names() else None
    code, resp = upload(f"{FX}/e2e-ocean.jpg", "BEACH.JPG")
    got = saved(resp)
    names = lib_names()
    beach = [n for n in names if n.lower().startswith("beach")]
    check("再传只差大小写的 BEACH.JPG:两张都在(没有覆盖)", len(beach) == 2, (got, beach))
    after = sh(f"md5sum {LIB}/Beach.jpg").split()[0] if any(n == "Beach.jpg" for n in names) else None
    check("原来那张 Beach.jpg 内容没变", before is not None and before == after, (before, after, names))

    journey("upload-edge-batch-20")
    from PIL import Image
    paths = []
    for i in range(20):
        p = f"{FX}/batch-{i:02d}.jpg"
        if not os.path.exists(p):
            Image.new("RGB", (800, 450), (i * 12, 200 - i * 8, 90)).save(p, quality=85)
        paths.append(p)
    n0 = len(lib_names())
    # 网页分批上传(每批 5 张);这里照做 4 批
    ok_all = []
    for b in range(4):
        args = []
        for p in paths[b * 5:(b + 1) * 5]:
            args += ["-F", f"files=@{p}"]
        code, body = curl("-X", "POST", *H, *args, url("/api/upload?type=wallpapers"), timeout=120)
        try:
            ok_all += saved(json.loads(body))
        except Exception:
            pass
    check("20 张分 4 批全部收下", len(ok_all) == 20, len(ok_all))
    check("图库目录多了 20 张", len(lib_names()) == n0 + 20, (n0, len(lib_names())))
    time.sleep(1.5)
    s = screen()
    check("电视上的计数跟着涨", any(("received" in t) for t in s.texts()), [t for t in s.texts() if "eceived" in t])
    # 一次请求 20 张
    args = []
    for p in paths:
        args += ["-F", f"files=@{p}"]
    code, body = curl("-X", "POST", *H, *args, url("/api/upload?type=wallpapers"), timeout=180)
    try:
        one = saved(json.loads(body))
    except Exception:
        one = []
    check("一次请求 20 张也全部收下(重名自动加 -1)", len(one) == 20 and all("-1" in n for n in one), (code, one[:3]))

    journey("upload-edge-size")
    big = f"{FX}/big31.jpg"
    if not os.path.exists(big): make_big_jpeg(big, 31)
    code, resp = upload(big, "big31.jpg")
    rej = resp.get("rejected") if isinstance(resp, dict) else None
    check("31 MB 照片被拒(reason size)", not saved(resp) and "size" in json.dumps(rej), (code, resp))
    check("被拒的照片不在图库里", "big31.jpg" not in lib_names())
    ok29 = f"{FX}/ok29.jpg"
    if not os.path.exists(ok29): make_big_jpeg(ok29, 29)
    code, resp = upload(ok29, "ok29.jpg")
    check("29 MB 照片收下", saved(resp) == ["ok29.jpg"], (code, resp))

    journey("upload-edge-concurrent")
    res = {}
    def worker(tag, path):
        res[tag] = upload(path, "same-name.jpg")
    ts = [threading.Thread(target=worker, args=("a", img)), threading.Thread(target=worker, args=("b", f"{FX}/e2e-ocean.jpg"))]
    for t in ts: t.start()
    for t in ts: t.join()
    names_c = sorted(saved(res["a"][1]) + saved(res["b"][1]))
    check("并发两个同名上传:都收下、名字不同", names_c == ["same-name-1.jpg", "same-name.jpg"], (res.get("a"), res.get("b")))
    check("两张都在目录里", "same-name.jpg" in lib_names() and "same-name-1.jpg" in lib_names())
    time.sleep(1)
    ok, s = focus_stable()
    check("上传期间电视焦点不丢", ok, s.count_focused())

    journey("upload-edge-delete-latest-then-close")
    # 落点规则(landingCell):本次打开扫码页以来传的文件里第一张还在的;一张都不在 → 「＋」。关掉重开,开一个新的「本次」
    home_intent()
    restart(settings_patch={"wallpaperFile": ""}, layout=LAYOUT)
    open_import()
    code, resp = upload(img, "landing-target.jpg")
    check("传一张 landing-target", saved(resp) == ["landing-target.jpg"], resp)
    code, _ = curl("-X", "DELETE", *H, url("/api/file?type=wallpapers&name=landing-target.jpg"))
    check("网页删掉刚传的那张 200", code == "200", code)
    check("目录里没有了", "landing-target.jpg" not in lib_names())
    key("back"); time.sleep(2.5)
    s = screen()
    check("关扫码页:刚传的那张已删 → 落「＋」、单个焦点", s.count_focused() == 1 and S("picker_add_from_phone") in s.label(), s.label())
    check("关页后服务已停", not up("/api/list?type=wallpapers"))

    journey("upload-edge-delete-current-wallpaper")
    home_intent()
    restart(settings_patch={"wallpaperFile": "Beach.jpg"}, layout=LAYOUT)
    check("把我的图 Beach.jpg 设成壁纸", (pull_json("settings.json") or {}).get("wallpaperFile") == "Beach.jpg")
    # 走「通用 → 从手机添加」打开扫码页(换壁纸页的初始焦点在当前壁纸上,走不到「＋」)
    from j_edit import open_settings
    open_settings(); move_to(S("settings_group_general")); key("ok"); time.sleep(1.2)
    move_to(S("settings_phone_transfer")); key("ok"); time.sleep(2.5)
    port = forward_from_screen()
    code, _ = curl("-X", "DELETE", *H, url("/api/file?type=wallpapers&name=Beach.jpg"))
    check("网页删掉正在用的壁纸 200", code == "200", code)
    home_intent(); time.sleep(3)
    ok, s = focus_stable()
    check("删掉正在用的壁纸后回首页:不崩、单个焦点", ok and foreground() == PKG, (s.count_focused(), foreground()))
    sh(f"am force-stop {PKG}"); home_intent(); time.sleep(3)
    ok, s = focus_stable()
    check("壁纸文件没了再冷启动:不崩、单个焦点", ok and foreground() == PKG, (s.count_focused(), foreground()))
    fl = sh("logcat -b crash -d")
    check("没有崩溃记录", "uniteduone" not in fl, fl[-300:])

    journey("upload-edge-close-mid-upload")
    port = open_import()
    vid = f"{FX}/slow.mp4"
    if not os.path.exists(vid):
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=duration=20:size=1280x720:rate=30",
                        "-b:v", "8M", "-pix_fmt", "yuv420p", vid], check=False)
    before = sh(f"ls {FILES}/library/screensavers/ 2>/dev/null")
    p = subprocess.Popen(["curl", "-s", "-m", "60", "--limit-rate", "400k", "-X", "POST", *H, "-H", "Content-Type: application/octet-stream",
                          "--data-binary", f"@{vid}", url("/api/upload-raw?type=screensavers&name=slow.mp4")],
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    time.sleep(4)
    key("back"); time.sleep(2)
    try:
        out, err = p.communicate(timeout=70)
    except subprocess.TimeoutExpired:
        p.kill(); out, err = "", "timeout"
    check("中途关页:上传没有成功落盘", "slow.mp4" not in sh(f"ls {FILES}/library/screensavers/ 2>/dev/null"), out[:200])
    time.sleep(2)
    leftovers = sh("ls /sdcard/Android/data/com.uniteduone.launcher/cache/upload/ 2>/dev/null").split()
    check("中途关页:cache/upload 里没有残留的 .part", not [x for x in leftovers if x.endswith(".part")], leftovers)
    ok, s = focus_stable()
    check("中途关页后单个焦点、前台是我们", ok and foreground() == PKG, (s.count_focused(), foreground()))
    check("中途关页后服务已停", not up("/api/list?type=wallpapers"))

    journey("upload-edge-cleanup")
    sh(f"rm -f {LIB}/*")
    restart(settings_patch={"wallpaperFile": ""}, layout=LAYOUT)


if __name__ == "__main__":
    run()
    summary()
