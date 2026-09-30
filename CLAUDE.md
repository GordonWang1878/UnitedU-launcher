# UnitedU

零广告、零推荐,只有你放上去的应用。面向国行无 GMS Android TV 的开源桌面。设计定稿见 `docs/DESIGN-unitedu-open-source.md`(产品基线);现行界面与每条裁定(R 号)以 `docs/superpowers/specs/2026-09-20-gtv-line-design.md` 为准。给外部评审的入口是 `docs/REVIEW-GUIDE.md`。包名 `com.uniteduone.launcher`。

## 构建

```bash
source scripts/env.sh && gradle --no-daemon assembleRelease
```

工具链在 `~/Library/{Java,Gradle,Android}`,刻意不进全局 PATH。装法与版本见 `docs/WORKLOG.md`。

## 模拟器(开发验证)

AVD `unitedu-tv`:Android 14 TV(arm64-v8a),1920×1080/320dpi,HVF 加速(另有同配置的 `unitedu-tv-2` / `unitedu-tv-3` 给并行代理各用一台,`unitedu-gtv` 是装了 Google TV 的对照机)。工具链已装 emulator + system-image(手装,同 SDK 绕法,见 `docs/WORKLOG.md`)。

```bash
source scripts/env.sh
emulator -avd unitedu-tv -no-snapshot -no-audio -gpu swiftshader_indirect &   # 启动
adb wait-for-device && adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package set-home-activity com.uniteduone.launcher/.MainActivity   # 不带 --user 0
adb shell am start -n com.uniteduone.launcher/.MainActivity                     # 不用 HOME 键,见下
adb exec-out screencap -p > /tmp/home.png        # 截图
adb emu kill                                     # 关闭
```

改 settings.json 单个字段验证用:pull → 正则替换 → push → force-stop → `am start -n`(见 M3 计划 Task 5 的 setjson.sh)。

**模拟器验证的坑**(M7 实测,细节见 WORKLOG 2026-09-17):
- `KEYCODE_HOME` 不认 `set-home-activity`(原厂 Google TV 桌面照抢)→ 一律 `am start -n` 拉起;`set-home-activity` 带 `--user 0` 会返回 Success 却不生效。
- 真机是被 HOME 拉起的:`am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.uniteduone.launcher/.MainActivity`。依赖启动 intent 的 bug 在 `am start -n` 下藏得住(`recreate()` 沿用原 intent)。
- 按不住键:`settings put secure long_press_timeout 700` 后 `input keyevent --longpress KEYCODE_DPAD_CENTER`(注入的重复事件时间戳 = downTime + 该值,越过 600 ms 阈值;测完改回 400);或装 `LONG_PRESS_MS = 0` 的探针 APK。
- HOME 角色进程受保护:`am kill` 无效,`am crash` 连任务带状态一起丢,`always_finish_activities` 不生效 ——「进程死后带 Bundle 重建」这台 AVD 造不出来。
- `pm clear` 连数据带 HOME 角色一起清(卸载同样退回原厂桌面),之后重设。
- HOME 角色没生效时应用会弹「Default Home」对话框(2026-09-17 美化轮截图时遇到):`input keyevent 4` 按两次再截;截图前 `dumpsys window | grep mCurrentFocus` 确认前台是 UnitedU。
- 系统设置是半透明侧边面板:盖着时本应用仍是 STARTED,不能拿它当「退到后台」。
- 测「从别的应用回来焦点还在不在」时,回来要按 BACK:对活着的实例 `am start -n …MainActivity` 走 `onNewIntent`(= HOME 语义),所有浮层当场收掉,测不出焦点记忆。
- **量动画时长/曲线,用 mp4 真实时间戳,不要数帧、不要放慢倍率**(2026-09-22 起,Round 9 实证):`adb shell screenrecord --time-limit 6 /sdcard/x.mp4` 录下按键过程,拉回后 `ffmpeg -fps_mode passthrough -vf scale=960:540 f%04d.png` 抽帧、`ffprobe -show_entries frame=pts_time -of csv=p=0 x.mp4` 取每帧真实 pts(ffmpeg 已 `brew install`),再按像素追踪目标位置/面积。这台 AVD 只跑 24–40 fps 且帧率会飘,`screenrecord --output-format=frames` 只在画面变化时吐帧,**帧号 ≠ 时间**;`animator_duration_scale` 放慢只对我们自己的 Compose 动画有效(launcherx 的行滚动走 RecyclerView scroller、不吃倍率),而且 `settings delete` 后已在跑的进程不一定重读,要 `put 1.0` + `am force-stop`。此前用这两种方法得出的「Google 动画多少毫秒」全是猜的——Round 9 用 pts 一量,进焦放大是 1.2 s 不是 150 ms(150 是失焦缩回)。
- **量我们自己 150–200 ms 的淡入淡出**(R108,2026-09-27 实测):宿主负载高(三台模拟器并跑,load ≈ 15)时这台 AVD 画整屏半透明层只有 3–10 fps、帧完成比 vsync 晚 300 ms 以上,1× 的 mp4 里一次淡化只落 0–2 帧,量不出来。做法:`animator_duration_scale 10`(只对我们的 Compose 动画生效)+ `screenrecord --size 960x540`,取区域亮度按 mp4 pts 拟合 `FastOutSlowIn`,结果 ÷ 10;区域选**不受焦点动画影响**的(左栏标题、未聚焦的卡片),否则胶囊填色的 150 ms 会混进来。另:模拟器待机(默认 3 分)后第一下按键会被当唤醒吞掉,脚本开头先发一下无害的方向键。
- **要按真实帧率录动效,模拟器用 `-gpu host` 起**(2026-09-29 R129 实证):`swiftshader_indirect` 在宿主负载 10–20 时录屏只有 2–7 fps,换 `-gpu host` 后 `screenrecord --size 960x540` ≈ 52–60 fps,1× 就量得出百毫秒级的延迟 / 淡入(上面两条的慢放法只在拿不到 host GPU 时用)。**A95L 自己的 `screenrecord` 也有 ≈ 60 fps**,可用来量我们的界面动效(HDMI / 视频层画面录不进去);只做被动录屏,按键由 Gordon 自己按。
- **`graphicsLayer` alpha < 1 会把内容画进以图层尺寸为界的离屏层,越界的部分被裁**(2026-09-29 R129f):焦点卡的放大 + 描边 + 60 dp 柔光本来就画出行框之外,平时不裁、淡入那几百毫秒被裁,到 alpha = 1 又冒出来。R129 起首页行图层四边各撑大 `APP_FOCUS_GLOW_DP`、外层 `layout` 按原尺寸上报(`HomeScreen.kt` 的 `CategoryRow`);此前只撑了上下,行尾那张焦点卡在淡入期间右边被截(Gordon 电视上看到)。给任何带焦点溢出的容器加 alpha 动画都要先想这一条。
- 只想慢放**我们自己**的 Compose 动画看顺序(不量时长):`settings put global animator_duration_scale 60`(10 倍对 150 ms 的淡入不够,screencap 单次 0.5–3 s),测完 `settings delete global animator_duration_scale`。
- 注入按键之间留 ~0.4 s:零间隔连发会跑在 Compose 异步焦点效果前面。
- uiautomator 不报全透明节点(真待机时 `focused="true"` 为 0,焦点其实还在);TV 设置应用卡片的 content-desc 也是「Settings」,与齿轮同名 —— 脚本按 bounds 区分,且确定键之前先断言焦点文案,否则会启动卡片对应的应用。
- 设置外壳(R69)的胶囊在 uiautomator 里 `focused="true"` 的那个节点 `text` / `content-desc` 都是空的(文字在子节点上),脚本判「焦点在哪颗」按 bounds:1080p 上胶囊列 x = 1172–1708,单行胶囊高 110 px(2026-09-23 实测)。
- 一次指针事件会让窗口进入触摸模式,焦点脚本会把菜单 / 设置页读成「开着但焦点数 0」(2026-09-19 leftover-fixes Task 3 实证;冷启动按 MENU 无焦点的 most likely root cause:0/47 次复现于触摸模式之外,13/13 次复现于触摸模式内):`input tap`、以及 Android 14 上的 `input mouse tap`,都会把窗口切进触摸模式且跨冷启动保留;`GearMenu`、设置页这类用 foundation `clickable`(不是 tv-material `focusable()`)的行在触摸模式下 `FocusableInNonTouchMode` 直接拒绝 `requestFocus()`,要等第一下方向键才把焦点让给最上面那一项。焦点脚本开跑前先发一下 DPAD 键,或用 `dumpsys input | grep TouchMode` 确认是 0,不要直接数 `focused="true"` 就断言看门狗没生效。
- `unitedu-gtv` 上除了 user 0 还有一个 user 10,`adb install` 默认给**所有用户**装:之后在界面里卸载只卸掉 user 0,系统发 `PACKAGE_REMOVED` 不发 `PACKAGE_FULLY_REMOVED`(`pm list packages` 仍列出它,`dumpsys package` 里 User 0 `installed=false`)。要测「卸载 → 广播 → 清理」整条链,测试包用 `adb install --user 0` 装;测完 `pm uninstall <包>` 清全部用户(2026-09-27 应用页卸载实测)。
- 系统屏保(M5 `UnitedUDream`):`cmd dreams start-dreaming` 要 root,这台 AVD 是「user」build(`adb root` 报 `cannot run as root in production builds`),此路不通;`am start -n com.android.systemui/.Somnambulator` 会成功拉起且不报错,但屏保**没有真的进入**(`dumpsys dreams` 仍是 `mCurrentDream=null`)——是静默假成功,不能只看 `am start` 有没有报错,要用 `dumpsys dreams | grep mCurrentDream` 或 `dumpsys window | grep mCurrentFocus`(应为 `…/android.service.dreams.DreamActivity`)确认。实测可用的是「到点自动触发」,但比 `screensaver_activate_on_sleep 1` 多两个前提,少一个就直接 Asleep 跳过 Dreaming、或者永远不超时:`adb shell dumpsys battery set usb 1`(标记「已充电」)+ `settings put global stay_on_while_plugged_in 0`(默认 1,充电态会导致永不超时休眠)。全套:`settings put secure screensaver_components com.uniteduone.launcher/.UnitedUDream` + `screensaver_enabled 1` + `screensaver_activate_on_sleep 1` + `settings put system screen_off_timeout 15000` + 上面两条 battery/stay-awake 调整,发一次真实按键(建立新鲜的 last-user-activity 基线)后连续等 20–30s、**中途不要插入任何 adb shell 命令**(见下一条卡死),`mCurrentFocus` 会变成 DreamActivity;任意键结束屏保。另有一个独立的模拟器坑:系统会不定期把 `SCREEN_BRIGHT_WAKE_LOCK 'UndimDetectorWakeLock'`(uid=1000)卡在持有状态,卡住就永远不超时——`dumpsys power | grep -A3 "^Wake Locks:"` 看到它时,一次干净的 `KEYCODE_SLEEP` → `KEYCODE_WAKEUP` 能可靠解开(`Wake Locks: size=0`)。测完把三个 secure 键(`screensaver_components`/`screensaver_enabled`/`screensaver_activate_on_sleep`)、`screen_off_timeout`、`stay_on_while_plugged_in` 连同 `dumpsys battery reset` 一起还原(真机的屏保由 Gordon 在系统设置里开)。
- 音量对话框收起过程中做一次 `uiautomator dump` 会让 SystemUI 崩溃(`ViewRootImpl.setAccessibilityFocus` 空指针,重启次数超限被杀);这台 AVD 上 SystemUI 兼管 WM shell,它重启期间前台应用拿不到输入焦点,下一下按键卡 5 秒被系统记成对 UnitedU 的 ANR,应用被系统关掉、换回原厂桌面。按过音量键之后约 4 秒内不要做 UI dump(M4b Task 5 实证)。
- `idleAfterMs`(待机时长)只认 关/1/3/5/10 分(即 0/60000/180000/300000/600000 毫秒)这五档,写别的值读回来会被默默吃成 180000(3 分)。
- 冷启动用 `am start -n` 拉起之后,如果再发一次 HOME intent,会新建一个 home-type 的第二实例,不会走已有实例的 `onNewIntent`;要测同一实例的 `onNewIntent` 路径(例如「按 HOME 复原」),第一次进入也要走 HOME intent,不能用 `-n`。
- `screenrecord --output-format=frames --size <w>x<h> -`(隐藏选项)只在画面变化时吐原始 RGB888 帧,不认 `--time-limit`,`pkill -INT screenrecord` 停止;抓「有没有闪一下跳回旧状态再跳回来」这类比截图间隔还快的过程很好用。**但这个流的逐帧字节对齐没有文档、实测不可靠**(owner 反馈 R2 Fix 3 踩过):第一帧前有个约 2 字节的一次性前导偏移(1920×1080 时如此,960×540 时实测又不是稳定的一次性偏移,疑似还有周期性的每帧头部),不补偏移量会把画面解成错误的通道分布(蓝色卡片显示成绿/品红)且随帧数累积错位、画面看着像斜切;抓单帧对比(而不是判断颜色是否偏移)才勉强够用,凡是要在多帧上做像素级比色的场合,改用无参数 `adb exec-out screencap`(不带 `-p`)——单次往返 1.6s(1920×1080),但格式简单且有据可查:16 字节头(`width`/`height`/`format`/保留字段各 4 字节小端,`format=1` = `RGBA_8888`)+ `width*height*4` 字节的 RGBA8888,循环调用取多个时间点足够验证一次 focus 动画的渐变过程(`animator_duration_scale` 调到 30–60 拉长过渡窗口,配合这个 1.6s 粒度更容易采到中间帧)。`screencap -p`(PNG)在这台机型上单次往返约 9.7s,比 raw 慢 6 倍,像素级比色场合不要用。
- `unitedu-gtv` AVD(装了 Google 的 `launcherx` 供参照测量,来历见 `docs/gtv-line-kickoff.md`、实测见 `docs/research/2026-09-20-google-tv-launcherx-measurements.md`)上 `am force-stop` 我方包后紧跟着 `am start -n` 常常第一次会落到 `launcherx` 的 `HomeActivity`(HOME 角色被系统在 force-stop 后的过场里抢回去了,不是命令本身失败,`adb` 也不报错);再发一次同样的 `am start -n` 才会稳定落到我方 `MainActivity`。装脚本时默认 `am start -n` 发两次(或 `dumpsys window | grep mCurrentFocus` 断言一次、不对就重发),不要只发一次就假设前台是自己的界面。
- **模拟器上没有电视输入源**(`dumpsys tv_input` 的 inputMap 为空),输入源页只有一颗「返回」。要测输入源页:装一个只含 `TvInputService` 子类的测试 APK(服务带 `android.permission.BIND_TV_INPUT`、intent-filter `android.media.tv.TvInputService`、meta-data `android.media.tv.input` 指向一个空 `<tv-input/>` xml)就会冒出一个调谐器类输入(第三方服务一律 `TYPE_TUNER`,多个会被 `mergeTuners` 合成一颗,所以只能测一颗);确定键切换要另有一个接 `ACTION_VIEW` + `content://android.media.tv` 的 activity,否则 toast「切换不到」。要测所有应用页翻页:批量装无代码的占位 APK(`android:hasCode="false"`、activity 名直接写 `android.app.Activity`、带 `LEANBACK_LAUNCHER`),`aapt2 link` → `zipalign` → `apksigner`(debug.keystore)即可,不用 gradle(2026-09-27 顶栏线实测,30 个占位 + 1 个假调谐器 + 1 个假直播;测完全部卸载)。
- HDR 显示(2026-09-28 R122–R125 实证):**模拟器看不出 HDR**——显示器不报 HDR/SDR 比例(`dumpsys display` 里 `hdrSdrRatio not_available`),Android 14 会把 `COLOR_MODE_HDR` 窗口静默降级成 sRGB,增益图永远用不上;Android 14 的 `dumpsys SurfaceFlinger` 也没有 desired/current HDR 比例字段。验证增益图是否保留只能靠 `Bitmap.hasGainmap()` 日志 / 离屏探针。另:TV 模拟器上 `wm size 3840x2160` 不生效(被夹回 1920),4K 路径要用探针直接以 3840×2160 调函数验证。
- **A95L 真机同样 `hdrSdrRatio not_available`**(面板 `supportedHdrTypes=[1,2,3]` 只对视频通道):这台电视的应用界面显示不了 HDR 照片,内置 HDR 图在它上面等于 SDR,不是我们的 bug。
- `layout.json` 里同一行的重复包名会在读盘时被 `Layout.read` 的 `.distinct()` 静默去重(逐字理由见该函数注释:「重复包名会让列表 key 撞车」)——想在模拟器上人为造一行 2 张卡做纯横向焦点测试(不掺垂直换行),不能靠同一个包名写两遍,得挑两个不同的真实包名塞进同一行。

真机(Sony A95L)只在里程碑真机验收用;开发全程走模拟器。真机 adb 走「无线调试」(**不是** 5555),**配对会跨会话保留,装包前别先向 Gordon 要配对码**(2026-09-17 实证,见 WORKLOG 当日 M7 合并一节):先 `adb connect 192.168.1.22:38673`(连接端口以电视「无线调试」主页面显示的为准;IP 走 DHCP);报 `No route to host` 就 `adb kill-server` 后重连同一地址(本机 adb 后台进程的问题,不是电视);报 `Connection refused` 先等 adb 后台起来几秒、跑 `ADB_MDNS_OPENSCREEN=1 adb mdns services`,把列出的**每个**端口都 `adb connect` 一遍(2026-09-30:旧端口 43995 拒绝,mDNS 同时列着新端口 36205,连上即用——kill-server 后立刻查 mDNS 会是空表,别据此下结论),全部拒绝才请 Gordon 读电视页面上的新端口(`adb mdns services` 列不出时,macOS 自带的 `dns-sd -B _adb-tls-connect._tcp local.` 再 `dns-sd -L <名字> _adb-tls-connect._tcp local.` 能直接读出端口,2026-09-30 实证)(mDNS 广播的端口可能是休眠前的过期记录,端口扫描也扫不到真端口;mDNS 发现要 `ADB_MDNS_OPENSCREEN=1`);真机的 adb 序列号形如 `adb-…-1F8N2S (2)._adb-tls-connect._tcp`,**带空格**,脚本里 `adb devices` 要按 tab 切分、`-s` 参数加引号(2026-09-18 M8 装包时 awk 默认切分取到半截序列号报 device not found);只有连上后 `offline` / 认证失败才需重配(端口开着、`adb connect` 只报 `failed to connect` 时看 `$TMPDIR/adb.<uid>.log`:`SSLV3_ALERT_CERTIFICATE_UNKNOWN` = 电视不再认本机钥匙,就是这种情况):电视「使用配对码配对设备」拿码,`printf '<码>\n' | adb pair <IP:配对端口>`(管道喂码,参数形式会 protocol fault),再 connect。

**真机性能测量的两个坑**(2026-09-28 性能优化实测,报告 `docs/design/perf-2026-09-28.md`):
- `adb install` 的包在 A95L(Android 14)上是**未编译**的(`status=verify`),这台 ART 不读 APK 内嵌的 profile;按键帧 UI 线程 14.6 ms,编译后 6.4 ms。装完要测性能 / 让 Gordon 评手感前先编译(下次装包会重置)。**刚装完还没有运行画像时 `compile -m speed-profile -f` 会被 ART 降成 verify、等于没编**(2026-09-30 两次实测读回仍是 `status=verify`):直接 `adb shell cmd package compile -m speed -f com.uniteduone.launcher`,再用 `dumpsys package dexopt | grep -A3 "\[com.uniteduone.launcher\]"` 读回确认不是 verify(装完紧接着的一两次 `-m speed -f` 可能仍读回 verify,隔几秒再跑,直到读回 `speed-profile`——2026-09-30 深夜第三次才成);用过一阵、有了画像之后才用 speed-profile(等同电视夜间后台编译的状态)。
- gfxinfo 的「GPU 分位」在这台电视上不是真实 GPU 开销:GPU 一饱和就降频刚好跟上,读数粘在 ~16.6 ms。消融要用「每帧重复画 N 次」的临时构建让 GPU 满频,再从 framestats 取每帧 GPU 时间。

**推 GitHub 的坑**(2026-09-19 实证):本机 `github.com` 解析到 Surge fake-IP(198.18.x.x),流量走代理节点;带截图的大包(>1 MB)在默认设置下会在上传后被断(`unable to rewind rpc post data` / `remote end hung up`)。Surge 里 GitHub 策略改到稳定节点后,用 `git -c http.version=HTTP/1.1 -c http.postBuffer=157286400 push origin main` 可以推上去(1.18 MB 约 78 秒)。更大的包(2026-09-19 那次 5 MB 截图提交)仍会报 `curl 52 Empty reply from server`:把大提交单独先推(`git push origin <sha>:refs/heads/main`),失败隔 20 s 重试(实测第 3 次过),其余小提交再一次推完。zsh 里写 refspec 要 `"${sha}:refs/heads/main"`,否则 `:r` 被当成修饰符吃掉。2026-09-28 一次推 280 个提交(压缩后约 42 MB):沿 `git rev-list --reverse --first-parent origin/main..main` 按累计 `objectsize:disk` 约 5 MB 切批(单个大提交会让一批到 14 MB),逐批 `git -c http.version=HTTP/1.1 -c http.postBuffer=157286400 push origin "<sha>:refs/heads/main"`,5 批都一次成功、每批 3–5 s——当天代理节点下大包已不再被断,但积压多时仍先切批,失败隔 20 s 重试。

## 文档分流(每轮工作收尾前必查同步)

- `docs/DESIGN-*.md`:设计定稿,改设计先改它
- `docs/superpowers/plans/`:实施计划
- `docs/WORKLOG.md`:每轮工作记录(排查过程、结论、未验证项、决策)
- `docs/superpowers/specs/2026-09-20-gtv-line-design.md`:gtv 线(现行界面)的设计与 §12 裁定索引,新裁定按 R 号续写
- `docs/ui-pending.md`:1.0 之前的 UI 待决清单;`docs/design/settings-inventory.md`:设置页逐页清单(改设置页同步它)
- `docs/REVIEW-GUIDE.md`:外部代码评审入口(文件地图、不能当 bug 报的约束、已知问题),模块或约束变了同步它

## 改这份界面前必须知道的七条(都是真机代价换来的)

1. **绝不用任何可滚动容器**——`LazyRow`、`LazyColumn`、`horizontalScroll`、`verticalScroll` 一律不用。
   2026-09-11 实测:卡片放进 `LazyRow` 后,**按上/下键焦点会整棵树消失**,之后按什么都没反应,
   三行的桌面退化成只有第一行能用。日志形态是「卡片失焦 → Compose 根失焦 → View 重新拿到焦点
   但没有任何节点持有」,说明 Compose 没消费这个按键、平台的 View 级导航接手了。
   排除法试过五种改法(去 `focusProperties`、去 `unbounded`、行容器加 `focusGroup`、
   `LazyRow` 自己加 `focusGroup`、`Row`+`horizontalScroll`)**全部无效**,只有不可滚动的 `Row` 行。
   纵向早在 §6.28 就因为 `bringIntoView` 会在水平移动焦点时偷偷带动垂直滚动(按一次右键整体上移 158px)
   而改成自己算位移了 —— **横向是同一条规律的第二次应用:位移一律自己算,用 `Modifier.offset` + `animateDpAsState`。**
   **而且必须同时给那个 `Row` 加 `Modifier.wrapContentWidth(Alignment.Start, unbounded = true)`**,
   放在 `.offset(x = ...)` 之前(链上更外层)。少了它,父容器按屏幕宽给约束,`Modifier.size()` 会被
   `constrain`,**空间用完之后的条目被量成 0 宽**;更糟的是焦点搜索的右向判据是
   `focused.right < cand.right`(严格小于),这些 0 宽条目的 right 全部相等,**永远聚焦不到**。
   实测边界:第 7 格只剩 43% 宽,第 8 格起为 0;编辑界面里行尾的「＋」正是第一个被牺牲的,
   它一消失,那一行在界面内再也加不进应用。**自算位移救不了这件事——`offset` 发生在测量之后。**
   纵向的 `wrapContentHeight(unbounded = true)` 是同一招;2026-09-11 把纵向解法搬到横向时
   只搬了「自算位移」这一半、漏了「放开测量」这一半,当场造出上面这个回归。
   代价是所有条目都会被组合;行内条目很多时要自己盯住布局。

2. **焦点是否落下,只能由目标自报 `isFocused`**。`FocusRequester.requestFocus()` 返回 `Unit`,
   只有一个节点都没挂上时才抛异常,所以 `runCatching { requestFocus() }.isSuccess` **恒为真**,
   循环第一帧就退出、焦点其实没落下。每个新出现的界面都必须显式请求初始焦点,并且要等首帧合成之后
   (`withFrameNanos`),再靠目标自报 `isFocused` 来判断是否可以停止重试。

3. **焦点丢了要靠看门狗,不要靠「在猜得到的几个时刻补请求」**。卡片节点被销毁
   (某个应用后台更新 → `PACKAGE_*` → 那一行短一格)时焦点会消失,而那一刻不在任何
   一张「猜得到的时刻」清单里 —— 遥控器就此全死,按 HOME 也回不来。
   **推论:每个浮层必须自己负责焦点恢复,因为外层看门狗一定会为它让路。**
   「添加应用」列表就掉进过这个两不管地带:编辑界面的看门狗第一行是「浮层开着就返回」,
   而选择器自己的初始焦点循环落地后被 `landed` 闩死、永不重试 —— 在那里按一下左右键
   焦点消失,**没有任何人会把它捞回来**。画一张表:每个可聚焦的界面/浮层,谁负责它的恢复。
   **实现约束(同一个坑踩过两次)**:恢复用的 `FocusRequester` **必须逐项挂,不能只挂第 0 项**。
   `LazyColumn` 会把滚出视野的项回收,只挂第 0 项时它会变成悬空引用,`requestFocus()` 抛异常
   被 `runCatching` 静默吞掉,循环空转到上限后放弃。丢焦点本身不会滚动列表,
   所以「上次拿到焦点的那一项」一定还在组合窗口内 —— 挂它才安全。
   **这个洞的严重度还会被别处的修复推高**:候选列表从 38 项扩到 49 项之后,
   「滚到第 0 项被回收」从极端情况变成了常规操作。

   **画的表**——每个可聚焦的界面/浮层,谁负责它的焦点恢复:

   | 界面 / 浮层 | 恢复责任方 |
   |---|---|
   | 首页卡片 / 顶栏药丸组(gtv 线新顶栏 `GtvTopBar`,换皮自旧的 `TopPills`;~~设置 / 屏保两个按钮~~ → **R89 起设置 / 应用 / 输入源三颗**,账本里仍是 row = -1,col 0/1/2) | HomeScreen 看门狗 + 还原效果(选择器、设置外壳、应用页、输入源页等整屏浮层都叠在常驻首页上,`covered` 期间冻结 `tgtRow/tgtIdx/tgtPill`,关掉后按它还原;编辑页仍整体替换首页,回来落 (0,0))。**R69 起齿轮药丸 / MENU 键打开的是 MainActivity 那一层的设置外壳,不再是嵌在首页里的齿轮菜单**:`gearNonce` 删掉,「关掉回药丸 / 回那张卡」只靠冻结的目标。**R89 把布尔 `tgtGear` 扩成列号 `tgtPill`(-1 = 卡片,0/1/2 = 哪一颗)**:应用 / 输入源两颗会开浮层,关掉要回到打开它的那一颗——布尔量只记得「回顶栏」、一律送回设置那颗;还原效果的退出判据随之从「在顶栏上」收紧成「落在冻结的那一颗上」(Compose 抢先把焦点给设置那颗时不会提前退出)。设置外壳带预览的页会把首页整层 `graphicsLayer` 缩进预览框(R73)——首页此时仍是 `previewing`,不可聚焦、不进任何账本。**R129 换行进场淡入**(新焦点行晚一拍淡入)只是 `CategoryRow` 图层上多乘一个 alpha,在绘制阶段读:不改 `canFocus`、不挂 requester、不进任何效果的 key / 守卫,淡入中的行照常可聚焦;移动态下一律按 1 画。**R133 顶栏焦点名字**(R146 起写在那颗按钮正下方,不垫胶囊)同样只是显示:`GtvTopBar` 本地记「哪颗在焦点」,只读 `onFocusChange` 的上报再原样转给 HomeScreen,不挂 requester、不可聚焦、不进无障碍树。**R147 首页也会是残影**:进编辑页时首页在 `FadeSwitch` 里多画 `EDIT_SWAP_MS` 淡出,这期间 `previewing = overlayOpen || LocalPageGhost.current`(不可聚焦、不收键、看门狗让路),编辑页同帧照改前的流程落焦 |
   | 首页原地移动态(M4b) | 没有浮层:HomeScreen 还原效果(key 含 `moveTarget = moving.pos`)+ 看门狗,以被搬的卡为目标;期间 `tgtRow`/`tgtIdx`/`tgtPill` 冻结,结束时由 `MoveLanding` 一次写入(放下 = 新位置,取消 = 出发格);放下后 `revision++` 的重读落地前继续画搬好的那一份,不会闪回旧顺序再跳回来 |
   | 长按卡片菜单(R69 起齿轮菜单退役,换成下面的设置外壳第一层) | GearMenu 自己的初始焦点循环(nonce,退出判据是 `holder != null` 的自报——菜单里任意一项持有焦点即算落地,铁律 2)+ `holder == null` 看门狗(3 帧宽限后重请求 `focusedIdx`,每轮最多 60 帧封顶,守卫与 key 同为 `holder == null`,铁律 6);再次丢焦点时 key 翻转、看门狗重新武装,不是一次性闩(铁律 7)。**gtv 线 Task 8 换皮**:整屏 `GtvTokens.MenuBg` 底 + 左侧 banner/名字 + 右侧一列 268×55dp 起全圆角药丸(聚焦填主题 accent、按亮度选对比文字色),菜单项内容与上面这套焦点机制逐字未动;新增的 `app: AppEntry?` 参数只喂左半的图,不参与焦点账本。药丸 `MenuPill` R69 起抽到 `ShellCapsule.kt`,与设置外壳共用;这里三个调用点(长按卡片菜单、编辑页的两个菜单)只传标签,仍是精确 55dp 单行。R17 的 `showHints` 随齿轮菜单一起删掉(两行说明挪到外壳第一层的 `hint`)。**R135 / R136(外观轮)**:没有 banner 的调用点(行菜单、输入源菜单、内置图菜单、所有确认页)左边改用 `ShellTitle`(上方一行小字 `eyebrow` 写属于谁、下方 `body` 写后果),字号并入 `Type`;打开 / 关掉 / 换层由调用方的 `OverlayStack` 淡入淡出,菜单自己照 `LocalPageGhost` 让路——两个循环的守卫与 key 都多了 `ghost`(铁律 6)、每颗胶囊 `canFocus = false`、`BackHandler(enabled = !ghost)`、点击不回调。首页这一摞的状态是 `HomeOverlay`(Menu / Rename),菜单 →「修改标题」是同一摞里换层(300 ms 交叉淡化):菜单变残影,改名页全新组合、自己落焦 |
   | 改名页(`TitleDialog`,M4b 起通用化;R135 起整屏两栏) | 不再认卡片/行/输入源的种类,只按 `key` 记草稿、按调用方传入的 heading/hint/subtitle 渲染,nonce + focused,四向 Cancel;卡片改名、行改名(`key = "row-$ri"`)、输入源改名共用同一份实现。**R135**:整屏 `MenuBg` 两栏,右边一个胶囊形输入框(聚焦填主题色),空着时垫 `placeholder`(清空后会恢复成的名字);焦点账本未动——页上只有输入框一个可聚焦节点。**R136**:由调用方的 `OverlayStack` 淡入淡出;残影里输入框 `canFocus = false`(焦点一清输入法随之收起)、不收返回键、不回调 |
   | 设置外壳每一层(R69:第一层 / 四个分组页 / 选项层 / 默认桌面页 / 恢复默认确认页;R128 加子页「待机」`s:standby`;`SettingsShell`) | 每层一个 `CapsuleColumn`(R108 起 `FadeSwitch` 按 `(栈深, 页)` 换层:新层是全新组合、淡入,旧层变残影淡出,见下面「残影」一行;时长现为 `GtvLayout.SETTINGS_FADE_IN_MS / _OUT_MS / SETTINGS_LAYER_FADE_MS` = 500 / 400 / 300 ms,R113 起带轻微放大——下文各行里的 150 / 200 ms 是 R108 当时的值):逐项 requester;**目标 = MainActivity `shellStack` 栈顶帧的 `focus`(胶囊 id,不是下标)**,只在不还原时跟着自报的焦点走;定位效果 key `(focusNonce, covered, id 清单)`,退出判据是目标自报(铁律 2),末尾只在 RESUMED 时放开 `restoring`;`holder == null` 看门狗(3 帧宽限、60 帧封顶,守卫 `covered`/`restoring`/`holder == null` 全在 key 里);`ON_PAUSE` 起冻结。进下一层时父帧的目标原样留着 → 返回落回进入时那颗胶囊。**条件行**(动画缩放;~~恢复隐藏的输入源~~ R92 起挪到输入源页)出现 / 消失:目标按 id 跟着那一行走;那一行自己没了退回上一行,组合阶段发现 id 清单变了同步置 `restoring`(R128 起动画缩放行是通用组末行,模拟器实测:焦点在它上面时它消失 → 落上一行「时钟显示」;R128 前它在「恢复默认」之前,消失时落「恢复默认」)。**子页(R128)**是栈上普通的一层,同一个 `CapsuleColumn`、没有新账本:返回落回分组页的「待机」,选项层返回落回子页里那一行。**每页胶囊 ≤ 6**(R128,`SettingsPageLimitTest` 逐页数)。选择器 / 扫码页 / 关于页 / 引导盖在上面时 `covered` 让路,关掉的 `focusNonce++` 让它接回(R128:关于页从它的「恢复默认」推确认层时让开——`aboutPageShown` 为假,外壳这时不算被盖,确认层自己落焦);「编辑分栏」打开编辑页时外壳逻辑上关掉(栈留着;只剩残影淡出盖在编辑页上),退出编辑页后按栈重建(落在有预览的页时首页缩进预览框)、落回「编辑分栏」。上下到头 `Cancel`,左右恒 `Cancel`;滑块胶囊在 `onKeyEvent` 里吃左右键调值(R72),焦点不出列。实时预览(R73)是缩小的常驻首页,预览框本身不可聚焦、没有 requester。**R131 左侧说明区**(光标所在那一行的说明)按栈顶帧的 `focus` 取文字,纯展示、不可聚焦,不进定位效果 / 看门狗的 key |
   | 关于页(R74 起外壳样式) | AboutScreen 里的 `CapsuleColumn`(同上一套:nonce 初始循环、`holder == null` 看门狗、`ON_PAUSE` 冻结、上下到头 / 左右 Cancel);叠在外壳之上,外壳 `covered` 让路,关掉后外壳落回第一层「关于」。R108:打开淡入、关掉淡出(残影冻结在关掉前的状态;时长同外壳一行)。**R128 起两颗**(检查更新 / 恢复默认,表 `ABOUT_CAPSULES`):目标 id 住在 MainActivity 的 `aboutFocus`(同输入源页 `inputsFocus`),从第一层打开写 null(落「检查更新」)。「恢复默认」往外壳栈上推确认层,关于页让开:画不画看 `aboutPageShown(about, shellStack)`(确认层在栈顶时为假;`about` 仍为真、状态机不 reset),外壳不再 `covered`、确认层落「取消」;取消 / 返回 / 恢复都弹栈,关于页重新组合、按 `aboutFocus` 落回「恢复默认」。返回键兜底按 `aboutShown` 判。恢复默认改回跟随系统而 `recreate()` 时,`about` / `aboutFocus` 随外壳栈经 Bundle 种回(同一个 selfTriggeredRecreate 条件),重建后仍落「恢复默认」 |
   | 所有应用页(R90,顶栏「应用」;`AppsPage`) | 叠在常驻首页之上的整屏浮层(`overlayOpen` 成员,首页 `previewing` 让路)。网格**不用可滚动容器**(铁律 1):纵向位移自算 `appsPageScroll`,`wrapContentHeight(unbounded)` + `offset`,所有卡片都组合。逐项 requester;**目标 `focusedIdx` 与持有者 `holder` 分开**(铁律 5),目标只在不还原时跟着自报走;`restoring` 初值真、`ON_PAUSE` 起冻结(打开一个应用再按返回回来,Compose 会抢先把焦点给第一张),定位效果 key `(nonce, covered, requesters)`、退出判据是目标自报、前台落地后才放开;`holder == null` 看门狗(3 帧宽限、60 帧封顶,守卫全在 key 里)。长按 / MENU 的菜单(`GearMenu`,两层:打开 / 卸载(R106,能卸载的才有)/ 加到桌面… → 选一行)盖在上面时 `covered` 让路,菜单自己的循环 + 看门狗负责;关菜单 `focusNonce++` 后网格接回那张卡。返回 / HOME 关页(`closeApps`,`focusNonce++`),首页按 `tgtPill` 回到「应用」那颗。R108:打开淡入、关掉淡出(时长同外壳一行);残影读 `LocalPageGhost` 合进 `covered`、每张卡 `canFocus = false`、不收返回键、**不再上报焦点**(关掉又马上打开时,残影离场的 `onFocusedApp(null)` 不会盖掉新页的上报)。**数据来自进程级缓存 `AppsPageCache`(R105)**:打开即画缓存,后台刷新回来内容不同才换;包的清单变了(装 / 卸)时换数据的效果**先置 `restoring` 冻结目标、再按包名重算 `focusedIdx`**(`appsPageRetarget`:还在 → 新下标;被卸 → 同组补进那一格的下一张 → 同组上一张 → 0),requester 表按包的清单(不是张数)换新,定位效果随之重跑落地。卸载走系统确认页时本页 `ON_PAUSE` 冻结,换表多半发生在后台,`onResume` 的 nonce 接回 |
   | 输入源页(R91,顶栏「输入源」;`InputsPage`) | 外壳同款:一个 `CapsuleColumn`(同上面设置外壳那一行的整套账本);**目标 id 住在 MainActivity 的 `inputsFocus`**(同外壳栈帧的 `focus`),每次打开写 null(落「当前在用」那颗或第一颗)。**条件行**「恢复隐藏的输入源」与被隐藏的那一颗消失时,目标按 id 退回上一颗(模拟器实测:隐藏唯一一个输入源后落「恢复隐藏」,按下后落回那个输入源)。胶囊菜单(`GearMenu`:改名 / 隐藏)与改名对话框(`TitleDialog`)盖在上面时 `covered` 让路;长按 / MENU 在 MainActivity 按 `inputsFocus` 找焦点那一颗。一个输入都没有时给一颗「返回」,焦点总有地方落。切换成功 / 返回 / HOME 关页,首页回到「输入源」那颗。R108:淡入 / 淡出(时长同外壳一行),残影不回调(`onTarget`/`onLoaded`/`onSwitch` 都不接) |
   | **淡出中的残影**(R108:设置外壳、外壳里被换下去的那一层、关于页、应用页、输入源页关掉后还画一个淡出时长;`SettingsFade.kt`) | **没有责任方,因为它永远拿不到焦点**:逻辑状态当场关掉(`shellStack` / `about` / `appsPage` / `inputsPage` 立即变,`focusNonce++`、首页 `previewing` 立即为假),首页 / 活着的下一层**同一帧**照改前的流程落焦;残影只是同一棵子树多画几帧,外面包 `LocalPageGhost = true`:`CapsuleColumn` 与应用页网格把它合进 `covered`(定位效果、看门狗让路;守卫与 key 同一个量,铁律 6),每颗胶囊 / 卡片 `focusProperties { canFocus = false }`(方向键搜不进、`requestFocus` 落不下;关掉那一刻焦点若还在残影里,Compose 当场清掉,与改前节点被拆同一条路),`BackHandler(enabled = !ghost)`,回调一律不接。外壳的底色 / 首页那一层透明度 / 预览缩放由 `ShellMotion` 的 a / v / z 三个量驱动,只在绘制阶段读。模拟器实测(R108 时把三个时长临时改成 4 s):淡出 / 交叉淡化期间按方向键全部落在首页或活着的那一层,没有一次进残影;各种关法的落点与改前逐格一致。**R136 起所有浮层都有残影**(`OverlayStack`:外层管开 / 关、里层按 `layerKey` 管换层):长按菜单、确认页、改名页、行图标、添加应用、三个选图页、扫码页、图库全屏预览、引导都照同一条让路,清单写在 `OverlayStack` 的注释里——**新加页面先做「残影让路」再放进摞里**,漏了的后果是残影抢焦点 / 收返回键。残影画的是关掉前那一份:各摞的状态对象(`HomeOverlay` / `EditOverlay` / `PoolOverlay` / `InputOverlay` / `PickerShown`)把要显示的字与图带在身上,不在残影里按下标现查(删完一行后同一个行号已是另一行)。模拟器实测(1×,按键间隔 ≈ 150 ms):淡出期间的方向键都落在活着的那一层;同一条 `input keyevent A B` 里零间隔连发的第二下会被吞(设置外壳原本就这样,见上面「注入按键之间留 ~0.4 s」)。**R140(复审 Critical):残影不能靠帧时钟收尾**——Activity 掉出 STARTED 时 Compose 暂停帧时钟,淡出动画停在原地,残影连同它的副作用会一直挂到回前台;`FadeSwitch` 现在让淡出与「宿主低于 STARTED」赛跑,先掉出就当场拿掉残影。有资源的页面在**变成残影那一刻**就放手,不等离开组合:扫码页的上传服务在 `DisposableEffect(ghost)` 里停(关页即停,也不再占着 8090 让快速重开换端口) |
   | 应用内提示条(R139,`ToastHost`,`AppToast.kt`) | **没有责任方,因为它永远拿不到焦点**:画在整棵树最上层的一颗胶囊,不可聚焦、不收按键、不挂 requester,不进任何效果的 key / 守卫(同 R131 左侧说明区);显示与消失只看 `MainActivity.toastMessage`(新的一条顶掉旧的,到时只清仍是当前那条的 id,不是闩) |
   | 首次引导(R135 起外壳样式) | 每一步一份 `CapsuleColumn`(与设置外壳同一套账本:逐项 requester、目标按胶囊 id 记且与持有者分开、`ON_PAUSE` 起冻结、定位效果只信目标自报、`holder == null` 看门狗);此前自带的同构账本 `StepFocus` 已删。胶囊 id:语言步 `onbLanguageId(i)`(初始目标 = 已选的那种语言,确定键不会误改语言)、`ONB_FILL` / `ONB_SKIP`、`ONB_CHANGE_HOME` / `ONB_DONE`。**换步(R136)**= 页内 `FadeSwitch` 按步数换层:旧的一步变残影,新的一步全新组合、自己落到初始目标;返回键 = 上一步(`BackHandler(enabled = !ghost)`)。切语言触发 `recreate()` 后停在第 2 步(模拟器实测)。结束时整页由 MainActivity 的 `FadeSwitch` 淡出,首页同帧落焦 |
   | 编辑页 | EditScreen 看门狗 + 显式重定位。「换卡片图」的选择器**替换**编辑页(开着时 EditScreen 不在组合里,它没有 `covered` 让路开关);关掉后编辑页重建,由 MainActivity 的 `editTarget`(layout 行号, 包名)种子定位回同一张卡。**列号口径(R67)**:编辑页只画已装、可启动的包(与首页同口径,没有「未安装」占位),所有「第几格」——`focusTarget`、`retargetCol`、`carry.pos`、`acting` 的列——都是看得见的那份(`view()` / `visibleRows`)里的列号,不是 layout.json 下标;回调里一律现调 `view()`(不用组合期的 `viewRows`,搬运的方向键可能在两次组合之间连着来),种子按包名在 `view()` 里查;行内改动经 `applyView` / `withVisibleEdits` 合回整份 `rows`(看不见的包留原下标),重定位效果等完数据再按 `view()` 夹列号(查过没查到的包会在数据到位时从这一行消失)。**卡片菜单 `acting` 按包名认(R145)**:`EditActing(行, 列, 包名)`,此刻的列号按包名现查(`editActingCol`);菜单开着时那张卡的应用被卸载 → 收菜单、看门狗随 `overlayOpen` 翻回 false 接回焦点——原来只记坐标,卸载让后面的卡左移一格,菜单悄悄换成下一张卡。**纵向位移自算(M4b 去掉 `verticalScroll`)**:焦点行变化时整块平移(与图片网格 `keepInView` 同一规则,另加头部高度的特殊项)。**初始焦点(M4b)**:进页时冻结在一个待处理的 (0,0) 重定位,等首帧合成之后才开始请求循环,且只在目标格仍是「即将被换掉的占位卡」(`PendingCard`,数据未到位)时才继续等——「+」、Remove From Row、非空删行这类目标本来就在旧数据里渲染成真卡片,立即落地,不会被无关的重载拖住(Task 4 review 收窄后的判据,而不是等「数据是否已刷新」这种更宽的条件)。**搬运模式(M4b 补丁)不是浮层**:焦点始终在被搬的卡上,每一步走 `retarget`,返回 / `ON_PAUSE` / 离开编辑页取消并复原(`rows` 放回进入时那一份、不写盘、焦点回出发格;离开编辑页 = 整页离开组合,搬运随之作废)。目标 = `carry.pos`,搬运中焦点上报不改 `focusRow`/`focusTarget`(与 `retargeting` 冻结同写法);被搬的卡若是源行最后一张,它的节点随这一步摘掉,系统当场把焦点给左上角那张卡、`retarget` 下一帧接回——所以冻结不能省。按键由编辑页根节点的 `onPreviewKeyEvent` 截获;MainActivity 按 `editCarrying` 在搬运中不让 MENU 退出编辑页,并把确定键整下(含重复事件)原样交给编辑页认长按。**R138:编辑页自己也会是残影**——从卡片菜单打开「换卡片图」时,MainActivity 用 `FadeSwitch`(exit = 选择器淡入时长)让它多留一个淡入时长、跟着淡出(菜单在残影里原样开着,选择器在上面淡入);残影里卡片子树 `canFocus = !overlayOpen && !ghost`、看门狗与显式重定位两个效果的守卫与 key 都加了 `ghost`(铁律 6)、两个 `BackHandler` 都 `enabled = !ghost`、不向 MainActivity 报搬运状态(它离开组合晚一个淡入时长,补报的 false 会盖掉新一页的搬运)。选择器关掉时新的一份编辑页当场出现(enter 0),照 `editTarget` 落焦——替换语义与种子都没变。**R147:编辑页整页也在一个 `FadeSwitch` 里**(与首页那个配对):打开时淡入 `EDIT_SWAP_MS`(首页残影在底下淡出),退出时它变残影淡出、新的首页当场在底下;残影走的是 R138 那一套 ghost 让路,残影里不再画选择器(`PickerStack(if (ghost) null else pt)`,退出后从外壳打开的选择器归外面那一份) |
   | 编辑页的行菜单(M4b,行尾「+」) | 复用 GearMenu 的初始循环 + `holder == null` 看门狗(与上一行的齿轮菜单同形状);动作完成后由编辑页的 `retarget()` 落回具体位置——上/下移跟着被移动的行走(落它的「+」),新建落新行的「+」,BACK/`Add App`/`Rename Row`/`Change Row Icon` 都转交给下一个 overlay 或落回本行的「+」。**gtv 线 Task 8**:`compact` 参数已删——换皮后药丸是固定 55dp 高,最多 7 项(3 固定 + 上移/下移/新建/删除四个条件项)算下来 7×55dp + 6×16dp(`GtvLayout.MENU_ITEM_GAP`)= 481dp,1080p/320dpi 是 540dp 高的屏,`Box(contentAlignment = Center)` 整体居中放得下,不再需要旧版按内边距硬挤(2026-09-20 模拟器实测 7 项截图确认无裁切)。**R136**:编辑页的六种浮层(卡片菜单 / 行菜单 / 改名 / 行图标 / 删行确认 / 添加应用)是一摞 `OverlayStack`,状态 `EditOverlay`;换层时旧层变残影、新层全新组合自己落焦,关掉后仍由编辑页的 `retarget()` 落回。**例外**:「换卡片图」不在这一摞里——选择器**替换**编辑页,菜单的残影随编辑页一起离开组合,所以菜单 → 选图页是先切到深色底、选图页再淡入(不是交叉淡化);MainActivity 在编辑态下垫了一块 `MenuBg`,这几百毫秒里后面不会露出壁纸 |
   | 行图标选择器(`RowIconPicker`,M4b;R135 起整屏两栏) | 自己的 nonce 初始循环 + 逐项 requester + `holder == null` 看门狗(同 GearMenu 形状);四向边界用 `FocusRequester.Cancel` 钉死,不溢出网格;选定 / BACK 都 `retarget` 回该行的「+」。R135 只换了画法(4 × 3 图标格在右半屏,聚焦填主题色);R136 残影让路:两个循环的守卫与 key 加 `ghost`、每格 `canFocus = false`、不收返回键、点击不回调 |
   | 编辑页的删行确认页(M4b;R135 起是一页 `GearMenu`) | `ConfirmDialog` 现在只是薄封装:`GearMenu(items = [取消, 确定])`,焦点账本就是 GearMenu 那一套(初始 `focusedIdx = 0` = 「取消」,默认焦点仍在取消);原来自带的 nonce + `focusedBtn` 循环已删。「恢复默认」R74 起不用 ConfirmDialog,是设置外壳的一层。只有非空行会经过这一步(空行直接删,spec §0-4);Cancel/BACK → 回到该行的「+」,OK → 落到上一行的「+」(删的是第 1 行则落新第 1 行的「+」)。残影里的说明文字用状态里带的行名与应用数(`EditOverlay.Confirm`),不按行号现查 |
   | 添加应用列表 | AppPicker(逐项 requester + `(nonce, candidates)` 初始循环,判据是正反都报的 `focusedItem`)。R83 起每项画成小卡片,但**聚焦节点仍是整行、不是卡片**:LazyColumn 纵向按边界硬裁,bringIntoView 只保证聚焦节点的布局框可见,行内上下左右各留 `appFocusOverflow` 才让放大 + 描边不被裁——改成「卡片自己可聚焦」会把这条保证丢掉。**R135**:整屏两栏,右半屏一列 `GtvLayout.PICKER_LIST_WIDTH` 宽的 `LazyColumn`(仍是这一处获准的可滚动容器),候选少时整列垂直居中;**R136**:残影里定位循环让路、每行 `canFocus = false`、不收返回键、点击不回调 |
   | 图片选择器(壁纸 / 换卡片图) | `WallpaperPicker` 与 `IconPicker` 各自的初始焦点循环(nonce)。这两处(`WallpaperPicker`、`IconPicker` 函数体里)调用 `PickerGrid` 都不传 `covered`、用缺省 `false`,不涉及下一行屏保图库那整套机制;`PickerGrid` 还有第三个调用者——屏保图库 `ScreensaverPoolViewer`(同在 `ImagePicker.kt`),机制见下一行(2026-09-19 更正:此前这里写「仅有的两处」是错的,leftover-fixes Ruling R10;2026-09-29 起改按函数名引用,不再写行号)。同样整屏浮层的「导入图片」(`ImportScreen`)不经过 `PickerGrid`,另有一套(nonce 初始焦点循环);「默认桌面」卡 R74 起是设置外壳的一层(见上面外壳那一行)。**R63 起三个网格首格都是「＋ 从手机添加」**(格子下标 = 图片下标 + 1,换算只走 `cellOfImage`/`imageOfCell`/`clampCell`/`landingCell`,单测 `PickerCellsTest`),原来的两个空态(`EmptyState`/`PoolEmptyState`)删掉,没图时网格只剩「＋」一格。**R115 起三个网格分「内置 / 我的」两块,但仍是一个网格、一套焦点**:格子 = 内置 B 张 + 「＋」+ 我的,换算函数都带 `builtinCount`(`addCell`/`cellOfImage`/`imageOfCell`/`builtinOfCell`/`landingCell`,`PickerCells.kt`,单测 `PickerCellsTest`);分组标题「内置 / 我的」是不可聚焦的行(`pickerLines`),上下键从一块的最后一行直接走到另一块的第一行(左右到行头 / 行尾 `Cancel`,上下只在整个网格的首 / 末行 `Cancel`,两块之间交给默认二维搜索);内置清单为空时上块和两个标题都不画,与 R63 逐格相同。纵向位移按像素 + 只停在行顶(`revealScroll`,进一块的第一行时连标题一起露出;铁律 1:`wrapContentHeight(unbounded)` + `offset`,行高按 `lineKey` 逐行实测)。初始焦点 = 格 0(有内置图时是第一张内置图,没有时是「＋」)。**从「＋」打开扫码页走「替换 + 种子」**(同换卡片图替换编辑页 + `editTarget`):`pickerTarget` 换成 `VIEW_IMPORT`、网格离开组合;关扫码页(`closeImport`)把 `pickerTarget` 换回来源、网格重新挂载(重扫文件),挂载时读一次 `MainActivity.pickerLanding`(本次新传的文件名)算落点,没传 / 传的都不在了 → 「＋」。种子由 `PickerGrid.frozenTarget` 冻住(铁律 5):未落地前别的格得焦点不改写 `focusedIdx`,定位效果那一轮跑完解冻;**种子是格 0 时不冻**(扫码页一拆 Compose 抢着给的正是左上角那格——R115 前格 0 恒是「＋」,有内置图后是第一张内置图,「＋」种子因此也要冻)。网格要等内置清单到了才挂(`rememberBuiltins`,启动时已预热进缓存),种子才算得对格子号。**R135 / R136**:三个选图页是整屏 `MenuBg` 页(页头 + 网格:壁纸 / 图库一行 4 张、卡片图一行 5 张),列数变了但格子换算函数都按列数参数算,`PickerCellsTest` 照过;打开 / 关掉 / 网格 ↔ 扫码页由 MainActivity 的 `PickerStack`(`OverlayStack`,状态 `PickerShown(target, importCategory)`)淡入淡出——「替换 + 种子」的语义没变(网格照旧离开组合、回来重新挂载读 `pickerLanding`),只是旧的那一层多画一个淡出时长。残影:`PickerGrid` 把 `ghost` 合进 `covered`(定位效果、看门狗让路)、每格 `canFocus = false`、点击与焦点上报都不接;扫码页残影不收返回键、不回调(服务照旧随它离开组合而停,晚一个淡出时长) |
   | 屏保图库的图片网格(`PickerGrid`) | **R63 补的目标冻结 `frozenTarget`**:`covered` 翻 true 时冻住当时的 `focusedIdx`,冻结期间只有「夹紧后等于它」的那一格得焦点才改写目标——实测 6 次删图 2 次落错格,病因是确认框节点被拆、或删掉末张时那一格节点被拆,系统当场把焦点派给网格里另一格,那一下原来直接改写 `focusedIdx`;另外 `ScreensaverPoolViewer` 把「删后重扫还没回来」(`rescanning`)也算进 `covered`,新列表到了定位效果才跑。修后 8 + 8 + 3 次全对。初始定位效果以 `(nonce, covered, focusRequesters)` 为 key,退出判据是目标格自报 `holderIdx == i`(铁律 2,不信 `requestFocus()` 的返回值);另配一个只在 `holderIdx == null && !covered` 时才跑、每轮最多 60 帧封顶的看门狗兜底(3 帧宽限;再丢一次焦点 key 翻转、自动重新武装,铁律 7,写法与 GearMenu、SettingsScreen 的看门狗同形状)。两条效果读的都是 `rememberUpdatedState` 包过的 requesters,不怕定位效果之外的看门狗在循环跑到一半时 `focusRequesters` 整表换新。`covered = previewIndex >= 0 \|\| deleteTarget != null \|\| builtinMenu != null \|\| rescanning`,预览、删图确认框、内置图胶囊菜单(R117)任一在场就让路。长按 / MENU 的分流靠网格上报的 `PoolFocus`(`Mine` → 删除确认框,`Builtin` → 胶囊菜单;「＋」与失焦报 null → 长按不生效)。**教训**:只以 nonce 为 key 的循环落地之后,如果异步重扫(`produceState` 在 IO 线程跑)换了文件列表,`remember(items.size)` 会把 `focusRequesters` 整表换新——旧循环早已跑完退出,没人知道表换了;必须把 `focusRequesters` 本身也编进 key,表一换这里就重新跑一轮,retarget 到新表上 |
   | 屏保图库的删除确认页(M5;R135 起是一页 `GearMenu`) | `ConfirmDialog` = `GearMenu` 两颗胶囊(默认在取消),账本是 GearMenu 那一套;关掉后 `deleteTarget` 变 null 让 `PickerGrid` 的 `covered` 翻回 false,由图库网格的循环接回原位置;`focusedIdx` 经 `clampedFocusedIdx` 自动夹到新长度;R63 起删空后网格只剩「＋」一格,同一套定位效果夹到「＋」上。R136:预览 / 删除确认 / 内置图菜单是一摞 `OverlayStack`(状态 `PoolOverlay`),残影期间 `covered` 已翻回 false、网格同帧接回。模拟器实测(外观轮):删中间一张落上一张,删到只剩「＋」落「＋」 |
   | 屏保图库的内置图胶囊菜单(R117) | `GearMenu`(与长按卡片菜单同一套:nonce 初始循环 + `holder == null` 看门狗),只有一颗「不参与轮播 / 加入轮播」;状态 `MainActivity.poolBuiltinMenu`,长按或 MENU 打开(焦点须在内置格上),返回 / MENU 关(`closePoolBuiltinMenu`,`focusNonce++`),选了由 `toggleBuiltinScreensaver` 写完盘再收;开着时 `PickerGrid` 的 `covered` 让路、`frozenTarget` 冻住,关掉后网格接回同一张内置图。`openScreensaverPool` 与 HOME(`onNewIntent`)兜底清它(铁律 7)。R136:经 `PoolOverlay.Menu` 淡入淡出;残影画关掉前的那颗文字(状态里带 `off`),写盘后的新状态不会让残影上的字跳变 |
   | 屏保图库的全屏预览(M5 起可达) | 自己的焦点循环,以外层 nonce(`MainActivity.focusNonce`)为 key(回到前台会重落;判据是自报的 `focused`,得失都报,不是只增不减的 `landed`);开着时网格的循环让路——`PickerGrid` 的 `covered`(预览 / 删图确认框 / 内置图胶囊菜单任一在场即为 true)。R115 起预览翻的是「内置 + 我的」连成的一串(不含「＋」),内置图是伪路径文件、画面从 assets 解;关掉后 `covered` 翻回 false,网格循环重跑接回 `focusedIdx`。**R104 起视频项**(`PreviewVideo` → `VideoSurface`,TextureView)只是画面、不可聚焦,按键仍由预览 Box 收,焦点机制未变。R136:经 `PoolOverlay.Preview` 淡入淡出;残影不再请求焦点、不可聚焦、不收返回键 |

4. **「有没有焦点」只信控件自己上报,不要用根节点的 `onFocusChanged`。**
   曾经用根节点的 `hasFocus && !isFocused` 当判据,它在多数路径上是对的,
   但在「退到后台再回来」这条路上**不重发**,会停在过期的 `true`:日志说有焦点,
   截图里卡片却既没有放大也没有光晕(上边缘 777→812、光晕峰值 142→66)。
   现在由 `AppCard` / `GtvTopBar`(原 `TopPills`,该文件已随 Fix 5〔终审 2026-09-20〕删除)
   通过 `onFocusChange(Boolean)` 同时上报「得到」和「失去」。
   **推论:看门狗的账本必须覆盖它会去抢焦点的全部场合。**它不认识齿轮菜单的菜单项,
   菜单一开账本就变成「没有焦点」,于是每帧抢着请求、把菜单刚拿到的焦点搅掉 ——
   菜单开着时必须让路。

5. **要把焦点送回某个具体位置时,「目标」必须与「当前位置」分开 —— *坐标的每一个分量都要拆*,
   而且要从 `ON_PAUSE` 就冻结。**
   Compose 会抢在还原逻辑之前把焦点给第一张卡;若两者共用一个量,那次焦点事件会把目标
   改写成 0,**记忆在被用到之前就没了**。另外显式重定位不能和看门狗写在同一个效果里 ——
   看门狗第一行是「已经有焦点就什么都不做」,会把明确的请求整个吞掉。
   **「每一个分量」是字面意思**:编辑界面先把*列号*拆成了 `rowFocused` / `focusTarget`,
   却让*行号* `focusRow` 继续一身二职(既跟着焦点走给看门狗用、又当重定位的目标)。
   结果 `onDismiss` 设好目标行之后,Compose 抢先给出的那次焦点事件把行号改了回去,
   焦点静默跳到另一行 —— **而下一步的「移出」就打在那一行上,真的删错了应用。**
   还有:**退出条件不能只判「当前 == 目标」**,两者本来就相等的路径会一次请求都不发;
   必须同时要求「焦点真的落下了」。

6. **一个效果里,「守卫」和「依赖」必须成对出现。**凡是写了 `if (X) return@LaunchedEffect`,
   `X` 就**必须**同时出现在这个效果的 key 里。少了 key,X 从 true 变回 false 时效果不会重启,
   那条守卫就变成了单向阀门 —— 进得去出不来。
   实测代价:编辑界面的焦点看门狗漏了这一半(首页那份是对的,`menuOpen` 既是 key 又是 guard),
   于是按行尾加号弹出「没有可添加的应用了」再按返回,三个 key 一个都没变、看门狗永不重启,
   **那个界面从此只剩返回键能用**。反过来也要检查:key 里有的量,守卫里要不要也判一次。

7. **别用「一次性布尔闩」表达状态,用可自愈的判据(比如 nonce 比对)。**
   `returnToGear` 曾是个布尔闩,只有「看门狗跑完整个循环」这一条窄路能清掉;
   任何一次早退(菜单又开了、`restoring` 被置位、Compose 自己把焦点还给了第一张卡)
   都会把它留在 `true`。**一旦闩住,「从应用返回还原到离开前那张卡」永久失效,
   而且此后每一次丢焦点都被送到齿轮** —— 症状与病因隔得很远,极难联想。
   改成「记下当时的 `focusNonce`,用 `focusNonce == gearNonce` 判断」之后:来了新的 nonce
   比对自然不成立,不需要任何人去清;而且守卫读的量本身就是 key,天然满足第 6 条。
   **判据:凡是写了 `x = true`,数一数有几条路把它写回 `false`;只有一条就换写法。**


## 抄 Google 动效:先对照两边的静止终态(2026-09-29 R129b 回滚的教训)

照着 Google 录像抄一段过程之前,先把两边**动之前、动完之后的静止画面**摆在一起比。R129b 照 Google 让旧焦点行先淡掉、再和新行一起淡回:Google 那一行淡回后只是 0.06–0.2 的淡影(它焦点行上方的行静止时本来就暗),我们上一行静止全亮,于是「全亮 → 0 → 全亮」0.4 s 走完 = 电视上闪一下,已回滚。**不要再给旧焦点行加淡出**,除非连「上一行静止压暗」一起做(那是视觉决策,先出截图给 Gordon)。装电视前自己以观众身份看一遍 1× 录像,不只核对曲线与规格吻合。

## 内置图铁律(2026-09-28 Gordon:「你不能指望我记」)

- `app/src/main/assets/builtin/{wallpapers,screensavers}/` 里的图**必须先跑** `scripts/hdr-assets.py --in-place <目录>`:转成 Android 14(XMP `hdrgm`)+ Android 15+(ISO 21496-1)双写法 HDR JPEG,4K 不变、压到每张约 1–1.5 MB,原图自动备份到 `~/unitedu-assets-originals/<日期>/`。已处理的图脚本会跳过。
- Gordon 可能直接把新图放进这两个目录(或改名换顺序)。**构建前 / 发现这两个目录有变化时,先跑脚本再构建**;漏了也会被单测 `BuiltinHdrAssetsTest` 拦下(报错里就是命令)。
- 生图与转换规范:`docs/design/hdr-image-spec.md`;命名规则:`docs/design/builtin-assets.md`。写入工具是自编译的 libultrahdr 1.4.0(开 `UHDR_WRITE_XMP`),缺了照规范里的步骤编译。

## 落盘铁律(2026-09-23 卸载一个应用、整个首页被换成默认布局的事故)

- 有多个写者的状态文件(layout / titles / hidden-inputs / settings)一律走 `LockedFile`,新文件也一样;读取统一用 `store.load`。排查表见 `docs/design/persistence-audit.md`。
- 读 → 改 → 写要整段放在 `store.locked` 里(`update`);文件缺失时「写默认值」也在同一把锁里。整份快照写盘还要走串行调度器 `layoutWrites`,否则旧快照可能后落盘。
- 禁止用「rename 失败就删正式文件」这种兜底,除非先有备份(`.prev`);禁止两个写者共用一个固定的 `.tmp`。二进制文件用 `writeFileAtomically`。
