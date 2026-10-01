#!/usr/bin/env python3
"""端到端测试要用的测试 APK 与上传素材:造出来、装到模拟器上;`--uninstall` 全部卸掉。

- 占位应用 test.dummy.app00–18:无代码(hasCode=false)、带 LEANBACK_LAUNCHER,名字照国行常见应用起(不同名字才好认焦点)。
- 认得的应用 com.ktcp.video / com.newtv.cboxtv / com.netease.cloudmusic.tv:包名在默认分类表三行里各一个,首次引导第 2 步才有计划列表
  (R160 起表里没有 com.ktcp.tvvideo / com.huya.nftv 了;旧模拟器上装过的这两个用 `--uninstall` 之外的 `adb uninstall` 清)。
- 假调谐器 test.tvinput(一个空的 TvInputService,输入源页才有东西)+ 假直播 test.livetv(接 content://android.media.tv 的 VIEW,
  输入源页按确定才切得过去)。做法见 CLAUDE.md「模拟器上没有电视输入源」一条。
- 上传素材:两张 1920×1080 JPEG、一张卡片 PNG、一段 3 秒 MP4(ffmpeg)、一个 txt(拒收用)。

工具链:先 `source scripts/env.sh`(JDK 17 + Android SDK build-tools 35.0.0);ffmpeg 与 Pillow 要装在本机。
"""
import os, subprocess, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import DEV, OUT, APKS, FX

SDK = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
BT = os.path.join(SDK, "build-tools", "35.0.0")
JAR = os.path.join(SDK, "platforms", "android-35", "android.jar")
KS = os.path.expanduser("~/.android/debug.keystore")

DUMMY_LABELS = ["爱奇艺", "腾讯视频", "优酷", "bilibili", "芒果TV", "虎牙直播", "斗鱼", "网易云音乐", "QQ音乐", "酷狗音乐",
                "Netflix", "Disney+", "Prime Video", "Spotify", "Twitch", "Plex", "Kodi", "VLC", "当贝市场"]
KNOWN = {"com.ktcp.video": "云视听极光", "com.newtv.cboxtv": "央视频TV", "com.netease.cloudmusic.tv": "网易云音乐TV"}

LAUNCHER = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{pkg}" android:versionCode="1" android:versionName="1">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/>
  <uses-feature android:name="android.software.leanback" android:required="false"/>
  <application android:label="{label}" android:hasCode="false">
    <activity android:name="android.app.Activity" android:exported="true" android:label="{label}">
      <intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LEANBACK_LAUNCHER"/></intent-filter>
    </activity>
  </application>
</manifest>
"""
LIVETV = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.livetv" android:versionCode="1" android:versionName="1">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/>
  <application android:label="Fake Live TV" android:hasCode="false">
    <activity android:name="android.app.Activity" android:exported="true" android:label="Fake Live TV">
      <intent-filter>
        <action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.DEFAULT"/>
        <data android:scheme="content" android:host="android.media.tv"/>
      </intent-filter>
      <!-- 输入源页切换发的是频道列表 URI,解析出的类型是这个(只有上面那条接不住) -->
      <intent-filter>
        <action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.DEFAULT"/>
        <data android:mimeType="vnd.android.cursor.dir/channel"/>
      </intent-filter>
    </activity>
  </application>
</manifest>
"""
TVINPUT = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.tvinput" android:versionCode="1" android:versionName="1">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/>
  <application android:label="Fake Tuner">
    <service android:name=".FakeInput" android:label="Fake Tuner" android:permission="android.permission.BIND_TV_INPUT" android:exported="true">
      <intent-filter><action android:name="android.media.tv.TvInputService"/></intent-filter>
      <meta-data android:name="android.media.tv.input" android:resource="@xml/tvinput"/>
    </service>
  </application>
</manifest>
"""
FAKE_INPUT_JAVA = """package test.tvinput;
public class FakeInput extends android.media.tv.TvInputService {
    @Override public Session onCreateSession(String inputId) { return null; }
}
"""

def run(*cmd, cwd=None):
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(f"失败:{' '.join(cmd)}\n{r.stdout}\n{r.stderr}")
    return r.stdout

def sign(unsigned, out):
    aligned = unsigned + ".aligned"
    run(f"{BT}/zipalign", "-f", "4", unsigned, aligned)
    run(f"{BT}/apksigner", "sign", "--ks", KS, "--ks-pass", "pass:android", "--key-pass", "pass:android",
        "--ks-key-alias", "androiddebugkey", "--out", out, aligned)

def manifest_only(name, manifest):
    d = os.path.join(APKS, name); os.makedirs(d, exist_ok=True)
    open(os.path.join(d, "AndroidManifest.xml"), "w", encoding="utf-8").write(manifest)
    run(f"{BT}/aapt2", "link", "-I", JAR, "--manifest", os.path.join(d, "AndroidManifest.xml"), "-o", os.path.join(d, "u.apk"))
    sign(os.path.join(d, "u.apk"), os.path.join(APKS, name + ".apk"))

def tvinput():
    d = os.path.join(APKS, "test.tvinput"); os.makedirs(os.path.join(d, "res", "xml"), exist_ok=True)
    os.makedirs(os.path.join(d, "src", "test", "tvinput"), exist_ok=True)
    open(os.path.join(d, "AndroidManifest.xml"), "w", encoding="utf-8").write(TVINPUT)
    open(os.path.join(d, "res", "xml", "tvinput.xml"), "w").write(
        '<?xml version="1.0" encoding="utf-8"?>\n<tv-input xmlns:android="http://schemas.android.com/apk/res/android" />\n')
    open(os.path.join(d, "src", "test", "tvinput", "FakeInput.java"), "w").write(FAKE_INPUT_JAVA)
    run(f"{BT}/aapt2", "compile", "--dir", os.path.join(d, "res"), "-o", os.path.join(d, "res.zip"))
    run(f"{BT}/aapt2", "link", "-I", JAR, "--manifest", os.path.join(d, "AndroidManifest.xml"), os.path.join(d, "res.zip"),
        "-o", os.path.join(d, "u.apk"))
    classes = os.path.join(d, "classes"); os.makedirs(classes, exist_ok=True)
    run("javac", "--release", "11", "-cp", JAR, "-d", classes, os.path.join(d, "src", "test", "tvinput", "FakeInput.java"))
    run(f"{BT}/d8", "--lib", JAR, "--min-api", "26", "--output", d, os.path.join(classes, "test", "tvinput", "FakeInput.class"))
    run("zip", "-j", os.path.join(d, "u.apk"), os.path.join(d, "classes.dex"))
    sign(os.path.join(d, "u.apk"), os.path.join(APKS, "test.tvinput.apk"))

def media():
    os.makedirs(FX, exist_ok=True)
    from PIL import Image, ImageDraw
    for name, col in [("e2e-sunset", (230, 120, 60)), ("e2e-ocean", (40, 110, 190))]:
        im = Image.new("RGB", (1920, 1080), col); d = ImageDraw.Draw(im)
        for i in range(0, 1920, 120): d.line([(i, 0), (i + 400, 1080)], fill=(255, 255, 255), width=6)
        im.save(os.path.join(FX, name + ".jpg"), quality=88)
    Image.new("RGB", (1280, 720), (20, 160, 90)).save(os.path.join(FX, "e2e-card.png"))
    open(os.path.join(FX, "notes.txt"), "w").write("hello")
    run("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=duration=3:size=640x360:rate=30",
        "-pix_fmt", "yuv420p", os.path.join(FX, "e2e-clip.mp4"))

def packages():
    return [f"test.dummy.app{i:02d}" for i in range(len(DUMMY_LABELS))] + list(KNOWN) + ["test.tvinput", "test.livetv"]

def build():
    os.makedirs(APKS, exist_ok=True)
    for i, label in enumerate(DUMMY_LABELS):
        manifest_only(f"test.dummy.app{i:02d}", LAUNCHER.format(pkg=f"test.dummy.app{i:02d}", label=label))
    for pkg, label in KNOWN.items():
        manifest_only(pkg, LAUNCHER.format(pkg=pkg, label=label))
    manifest_only("test.livetv", LIVETV)
    tvinput()
    media()

def install(skip=("test.dummy.app18",)):
    for pkg in packages():
        if pkg in skip: continue   # app18 留给「在应用页开着时装一个新应用」那一步
        r = subprocess.run(["adb", "-s", DEV, "install", "-r", os.path.join(APKS, pkg + ".apk")], capture_output=True, text=True)
        print(pkg, (r.stdout.strip().splitlines() or ["?"])[-1])

def uninstall():
    for pkg in packages():
        subprocess.run(["adb", "-s", DEV, "shell", "pm", "uninstall", pkg], capture_output=True)
    print("已卸载全部测试包")

if __name__ == "__main__":
    if "--uninstall" in sys.argv:
        uninstall()
    else:
        build()
        install()
        print("fixtures ok:", APKS, FX)
