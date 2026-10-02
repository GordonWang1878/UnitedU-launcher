# 第三方电视桌面怎么接管 HOME 键(先例调研,2026-10-02)

起因:兼容性调研(`2026-10-02-tv-compatibility.md`)发现 Google TV / GMS 版 Android TV 与几家国产品牌上 UnitedU 抢不到 HOME 键。Gordon:「别重新发明轮子,先看 Projectivy 这些有名的第三方桌面是怎么做的。」

## 0 结论

- **业界只有三条路,成熟产品 A + B 都做**:
  - **A 截键**:无障碍服务开 `FLAG_REQUEST_FILTER_KEY_EVENTS`,`onKeyEvent` 看到 `KEYCODE_HOME` 就拉起自己、返回 true 吃掉(按下与松开都要吃,系统是在**松开**那一下才回桌面)。不闪;但只在 OEM 的按键策略把 HOME 送到过滤器时有效(AOSP / Google TV 送;Fire OS 8 在 PhoneWindowManager 里先吃掉,送不到)。
  - **B 盯窗口**:监听 `TYPE_WINDOW_STATE_CHANGED`,原厂桌面的主 Activity 一到前台就把自己拉上去。哪里都能跑,但原厂桌面**必然先闪 100–250 ms**(Projectivy 在 Google TV 上 100–150 ms,home-on-fire 在 Fire OS 8 上约 250 ms);原厂桌面已经在前台时按 HOME 不会再发事件,B 抓不到。
  - **C 停用原厂桌面**:adb `pm disable-user`(Google TV 还要连带停 `setupwraith`,否则它会把原厂桌面再启用)/ root / 漏洞。不闪、不要无障碍,但要电脑,会丢 OEM 功能(YouTube 键),Fire OS 一路打补丁封杀。
- **谁用哪条**:Projectivy A + B(「Override current launcher」);ATV Launcher Pro A + B;LauncherHijack A + B + 两种广播;FTVLaunchX / LtvLauncher 只 A;home-on-fire 只 B(因为 Fire OS 8 没有 A);FLauncher 不做、让用户装 Button Mapper 或走 C;Launcher Manager 走 C(读 logcat + 本机 adb,已被 Amazon 按包名封);HALauncher / Sideload Launcher 明说不做桌面接管。
- **闪不闪还取决于任务类型**(Projectivy issue #605 实测):用普通 intent 拉起自己,得到的是叠在原厂 `type=home` 任务之上的 `type=standard` 任务,从应用按返回会先落到原厂桌面再被拉回(闪);用 `ACTION_MAIN + CATEGORY_HOME` 拉起(可带显式组件),任务就是 `type=home`,返回直接落到自己、不闪。
- **开机是弱点**:重启后 HOME 仍解析到原厂桌面;TCL 的「安全守卫」、系统 / 应用更新、force-stop、进程崩溃都会让服务悄悄解绑,而系统设置里的开关仍显示开着。成熟做法:`onServiceConnected` 时拉起一次 + 应用内显示「服务是否真的在跑」(不信系统开关)。
- **无障碍的税**:① Android 13+ 的「受限设置」——用系统安装器装的本地 / 下载 APK 会被标 `OP_ACCESS_RESTRICTED_SETTINGS`,无障碍开关打不开(Google TV 14 上一拨就弹回),而电视设置里常常没有「允许受限设置」入口;`adb install` 走 `PACKAGE_SOURCE_OTHER`,不受限。② 只要有一个服务开着按键过滤,别的应用行为会变(Fire OS 上 Prime Video 播放控件不再自动隐藏;小米 Mi TV Stick Android 14 上 Key Mapper 只是开着就改了 DPAD 行为,issue #2210)。③ 屏幕关着时按键不带 `FLAG_PASS_TO_USER`,不送无障碍——待机唤醒那一下 HOME 截不到。
- **Projectivy 的按键路径到底吃不吃 HOME,两个来源打架**:作者 spocky 在 issue #338 说「按 Home 时无障碍服务立刻拦下这颗键并发命令启动 Projectivy」;4.71 反汇编里 `onKeyEvent` 只对映射表里的键返回 true、没有 HOME 专门分支——映射表是运行时填的,代码上定不了默认表里有没有 HOME。对我们无所谓:我们自己写时 HOME 一定吃掉(按下 + 松开)。

## 1 Projectivy 4.71(`com.spocky.projengmenu`,从 A95L 只读拉下的 APK,`aapt2` + `dexdump` 反汇编,2026-10-02)

### 1.1 清单与无障碍配置

- 服务 `com.spocky.projengmenu.services.ProjectivyAccessibilityService`,`BIND_ACCESSIBILITY_SERVICE`,`exported=true`,**没有 `android:process`**(与桌面同进程)。
- `res/xml/accessibilityservice.xml` 只写了四项:`description`、`settingsActivity`(指向它自己的设置页)、`canRetrieveWindowContent=true`、`canRequestFilterKeyEvents=true`。事件类型与 flags 都在运行时用 `setServiceInfo` 设。
- 系统无障碍页里显示的说明(`service_accessibility_description`,五条):开机执行动作(HDMI 输入或应用)/ 自定义遥控器按键 / 换输入源时自动套显示配置 / 加强家长控制 / 用户空闲检测改善电源控制。**「接管 HOME」没写在里面。**
- `StartUpBootReceiver` 收 `BOOT_COMPLETED`(对应「开机执行动作」);无障碍服务本身由系统在开机时自动绑定,不靠这个广播。

### 1.2 运行时 `setServiceInfo`

```
eventTypes = 0x28  = TYPE_WINDOW_STATE_CHANGED | TYPE_VIEW_FOCUSED
flags      = 66    = FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | FLAG_RETRIEVE_INTERACTIVE_WINDOWS
           = 98    = 上面两个 + FLAG_REQUEST_FILTER_KEY_EVENTS   // 只在「Enable button remapping」开着时
notificationTimeout = 0
```

「Enable button remapping」的说明原文:*"The accessibility service intercepts keypress events. Disabling it will prevent long-press volume issues and system killing the service, but stock launcher override, parental control and idle detection might be less effective."* ——按键过滤是可关的,关了 HOME 接管仍在、只是「不那么灵」。

### 1.3 「Override current launcher」怎么做的

设置项文案:*"Override current launcher — Intercept calls to the current launcher (useful if you can't change/uninstall the stock one)"*。

1. 服务初始化时算一个「原厂桌面」集合:`resolveActivity(MAIN + HOME + DEFAULT)` 的结果 + `queryIntentActivities(MAIN + HOME)` 的全部(清单里也声明了 `<queries>` HOME intent)。
2. `onAccessibilityEvent` 只认 `TYPE_WINDOW_STATE_CHANGED`(32)与 `TYPE_VIEW_FOCUSED`(8):取事件的 `packageName` + `className`,先过一张**忽略表**(对话框 `android.app.dialog`、`com.android.systemui`、`.volumedialog`、输入法 `android.inputmethodservice`、`android.view` / `android.material` / `.widget` 这类非 Activity 的类名,以及一串直播 / 输入源应用——`.sony.dtv.tvlin`、`com.tcl.tv`、`com.mitv.livetv`、`skyworth.skyworthlivetv`、`com.heytap.tv.livetv`、`org.droidtv.zapster`、`youtube.tv` 等),不在表里的才算「前台换了应用」,写进一个 `StateFlow`。
3. 前台落到原厂桌面集合里时,往主线程 `Handler` 投一个 Runnable,调 `startActivity` 拉自己的主 Activity:flags = `NEW_TASK | RESET_TASK_IF_NEEDED | REORDER_TO_FRONT`,并放 `android.intent.extra.FROM_HOME_KEY = true`(模仿系统按 HOME 时给桌面的 intent);`startActivity` 包在 try/catch 里,**失败会连试三次**。
4. 这条路里没有 `performGlobalAction`;`GLOBAL_ACTION_POWER_DIALOG`(6)/ `DPAD_CENTER`(20)只用在「电源控制」功能。

**推论**:事件在原厂桌面窗口已经画出来之后才到,所以路径天然会闪一下原厂桌面;再加上 Handler 投递 + startActivity 的开销,闪的时长 = 一次冷 / 热启动桌面 Activity 的时间。

### 1.4 按键过滤(`onKeyEvent`)干了什么

```
onKeyEvent(e):
  if (remapEngine.handle(e)) return true        // 用户映射过的键(短按 / 长按 / 双击,300 / 400 ms 定时)
  if (前台是 ParentalControlCheckActivity && 家长锁开着 && 该键在屏蔽表里) return true
  return super.onKeyEvent(e)                     // false
```

`remapEngine.handle` 只查用户配置的映射表(keycode → 动作),表里没有就不消费;**代码里没有对 `KEYCODE_HOME`(3)的特殊分支**。也就是说 Projectivy **不**靠吃掉 HOME 来做接管;用户要是自己把 HOME 映射成「打开 Projectivy」,那是按键引擎的通用功能。

### 1.5 「Freeze launchers」(路②)

设置里另有「Change default launcher」与「Freeze launchers — This will allow you to (un)freeze a launcher to set another one as default」,配套「Freeze stock apps / Freeze package manually / Freeze suggested packages」。这是用 root / Shizuku 之类的高权限把原厂桌面停用,不是无障碍能做的事(我们没有这个条件,1.0 只能写进 README 让会 adb 的用户自己 `pm disable-user`)。

### 1.6 公开资料里的 Projectivy(后台代理,一手来源为主)

- 作者说法(GitHub `spocky/miproja1`):#24(2023-09)「用无障碍服务……没装"真"桌面、或勾了 override stock launcher 时,按 HOME **或**检测到原厂桌面成为当前应用,就强制拉起 Projectivy;override 还会在 Projectivy 主屏已显示时拦返回键」;#338(2025-08,Fire OS 7.7)「按 Home 时无障碍服务立刻拦下这颗键并发命令启动 Projectivy;Amazon 改了系统,这种情况下启动应用有可见延迟(5 s)」。更新日志:4.60「可以关掉无障碍服务的按键拦截」;4.63 / 4.64 修「别的桌面 / 所有声明 launcher activity 的应用被误接管」→ 现在只认具体的原厂桌面 Activity。
- Play 商店对无障碍的说明只写「仅用于遥控器快捷动作」;没有 wiki / FAQ,全部细节只在 issue 里。
- #605(2026-09,Google TV Streamer):Projectivy 已是 HOME 角色持有者,`resolve-activity -c HOME` 仍是 `launcherx/.home.HomeActivity`;override 拉起的 MainActivity 没带 `CATEGORY_HOME`,是 `type=standard` 任务,从应用按返回时 LauncherX 先到前台、100–150 ms 后 Projectivy 才上来。用 `am start -a MAIN -c HOME -n …/.ui.home.MainActivity` 拉一次后任务变 `type=home`,返回不再闪。
- 开机:#489 / #415(TCL Android 12 / 14)开机后无障碍显示开着却没在跑,根因 TCL「Safety Guard」,修法 `appops set <包> AUTO_START allow` + `dumpsys deviceidle whitelist +<包>`;#155 / #143 作者:「别信系统设置里的开关,它可能显示已启用但没在跑」(系统 / 应用更新之后);#591「force-stop 会杀掉无障碍服务,得关了再开」;#608 进程崩溃后 ActivityManager 拒绝重新绑定、设置仍显示开着。2026-03 一次 Google TV 更新又让部分用户的 override 失效(Android Authority,二手)。
- 受限设置:#252 / #225——Google TV 14 模拟器与 Streamer 上侧载包的开关一拨就弹回,`adb install`(或「ADB TV」应用)装的就能开;作者归因于 Android 13 起的受限设置。应用内没有提示。
- 副作用(XDA 原帖):FengOS 上只要开着任何无障碍服务(Projectivy、Button Mapper),音量长按就失效。

## 2 其他桌面与工具

| 工具 | 机制 | 原厂桌面闪? | 要 adb? | 开机 | 来源 |
|---|---|---|---|---|---|
| LauncherHijack(BaronKiko,GPL,Fire 平板 / Fire TV,已弃) | A `onKeyEvent` HOME **只吃按下**(松开返回 false)+ B 盯 `com.amazon.firelauncher`(平板包名,Fire TV 上这条根本不匹配)+ `ACTION_CLOSE_SYSTEM_DIALOGS reason=homekey` 广播 + 0×0 系统悬浮窗的 `onCloseSystemDialogs`;200 ms 去抖;`PendingIntent` 拉目标桌面(`MAIN + LAUNCHER`,`NEW_TASK | EXCLUDE_FROM_RECENTS | CLEAR_TOP | REORDER_TO_FRONT`) | 是(README 承认,建议「弄坏」原厂桌面) | Fire TV 要 `settings put secure enabled_accessibility_services …` | `BOOT_COMPLETED` + `onServiceConnected` 末尾拉一次;README「重启后先等 10 s 再按」 | github.com/BaronKiko/LauncherHijack |
| FTVLaunchX(codefaktor,2020,Apache) | 只 A:HOME 按下拉起目标包,按下与松开都返回 true;`SCREEN_ON` 再拉一次 | 否 | 要 `pm grant WRITE_SECURE_SETTINGS` | 「老设备最多等一分钟」;Fire OS 6.2.7.2 起失效 | github.com/codefaktor/FTVLaunchX |
| LtvLauncher(FLauncher 分支) | 只 A:HOME 按下 `startActivity(MainActivity, NEW_TASK | CLEAR_TOP)`,返回 true | 否(HOME 送得到的地方) | 否(受限设置除外) | 服务绑着就行;README 说支持 Fire TV——UNVERIFIED(Fire OS 8 不送 HOME) | github.com/leanbitlab-org/LtvLauncher |
| home-on-fire(toolicious,2026,GPL,Fire OS 7 / 8) | 只 B:盯 `com.amazon.tv.launcher/.ui.HomeActivity_vNext`(按 Activity 类名,不按包名)。源码注释:「Fire OS 8 在 FireTVKeyPolicyManager(PhoneWindowManager)里拦下 KEYCODE_HOME,用户态看不到;`amazon.intent.action.HOME_PRESSED` 广播要系统签名」 | 是,约 250 ms;Fire OS 7 有 5 s「应用切换锁」 | 多数 Fire OS 8 的无障碍页是空的,要 adb 授 `WRITE_SECURE_SETTINGS` | 可选「开机拉起」 | github.com/toolicious/home-on-fire |
| Launcher Manager(SweenWolf;不是 Tech Doctor UK) | C:读 logcat(`READ_LOGS`)看原厂桌面何时打开 + 本机 adb 发命令;「forever loop」每 5 分钟 `am force-stop com.amazon.tv.launcher`;Mini 版 `READ_LOGS` + 悬浮窗 appop;System User 版靠漏洞拿系统用户权限 `pm disable` | 是(「会短暂看到 Fire TV 主屏」;Mini 用加载页盖住) | 是 | 有「开机执行」;漏洞版权限重启即丢;2023-03 被 Amazon 按包名封,2025-09 补丁堵死漏洞 | XDA 4176349;aftvnews 2023-03-21 / 03-29 / 2024-04-17 |
| FLauncher(GitLab) | 不做。README:①装 Button Mapper 把 HOME 映射成打开 FLauncher;②(自担风险)`pm disable-user --user 0 com.google.android.apps.tv.launcherx` + `com.google.android.tungsten.setupwraith`(后者会当「兜底」把原厂桌面再启用),然后按 HOME 在系统选择框里选 | ②否 | ②是 | ②持久;YouTube 遥控键失效 | gitlab.com/flauncher/flauncher |
| Wolf Launcher(SweenWolf) | 自己不做,全靠 Launcher Manager | 同 LM | 同 LM | 同 LM | troypoint(二手) |
| ATV Launcher Pro(DStudio) | Play 文案:「Home Button Redirect」无障碍服务,看前台应用 + 按键,「只对 Home 键动作,其他键原样放过,不读屏幕内容」= A + B | UNVERIFIED(闭源) | 否 | UNVERIFIED | Play `ca.dstudio.atvlauncher.pro` |
| HALauncher / Sideload Launcher | 明说不是桌面 / 「能选桌面的电视上可当桌面」,零权限 | — | — | — | Amazon Appstore / Play |
| Button Mapper(flar2)/ Key Mapper(开源) | 通用 A:无障碍按键过滤。Button Mapper 列明支持「实体 home / back / recent 键、电视遥控」,屏幕关着不行;Key Mapper 文档没有 HOME 专门说明,「有的电视没有无障碍设置页,只能 adb」,受限设置页教用户去应用信息里「允许受限设置」;#2210 Mi TV Stick Android 14 上只是开着服务就改了 DPAD_CENTER 行为 | 否 | 看情况 | 服务绑着就行 | Play `flar2.homebutton`;keymapper.app |

### 2.1 平台事实(AOSP,代理核对过行号)

- `AccessibilityService.onKeyEvent` 文档:「事件先送到这里,再送给设备策略、输入法、应用……返回 true 则消费、不再送给应用」;必须成对处理按下 / 松开,否则事件流畸形。
- `FLAG_REQUEST_FILTER_KEY_EVENTS` 必须配 xml 里 `canRequestFilterKeyEvents=true`,否则忽略(`AccessibilityManagerService.updateFilterKeyEventsLocked`)。
- **顺序**:`InputDispatcher::notifyKey` → `interceptKeyBeforeQueueing`(只处理电源 / 唤醒类,HOME 不在这里)→ 过滤器 `filterInputEvent`(被消费就 return)→ 入队 → 派发时才 `interceptKeyBeforeDispatching`,`PhoneWindowManager` 在这里处理 HOME(「First we always handle the home key here, so applications can never break it」)。所以 AOSP 上过滤器先于 HOME 处理;OEM 策略(Fire OS)可以更早吃掉。
- **系统在松开那一下回桌面**(`DisplayHomeButtonHandler.handleHomeButton`:`if (!down) … handleShortPressOnHome`),且不要求见过按下 → 只吃按下不吃松开(LauncherHijack)在 AOSP 上照样回原厂桌面。
- `KeyEventDispatcher`:服务 500 ms 内不回话就当没消费、原样放行。
- 不带 `FLAG_PASS_TO_USER` 的事件(屏幕非交互态)直接放行、不给服务。
- **后台启动豁免**:`AccessibilityServiceConnection.bindLocked` 用 `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS | BIND_FOREGROUND_SERVICE_WHILE_AWAKE | BIND_INCLUDE_CAPABILITIES` 绑定,并 `setAllowAppSwitches`——无障碍服务进程可以从后台 `startActivity`,不用悬浮窗权限(官方文档「被系统绑定且允许后台启动的服务」一条)。
- **受限设置**:`InstallPackageHelper` 对 `PACKAGE_SOURCE_LOCAL_FILE / DOWNLOADED_FILE` 的安装调 `enableRestrictedSettings`(appop `OP_ACCESS_RESTRICTED_SETTINGS = MODE_ERRORED`);`pm install`(adb)默认 `PACKAGE_SOURCE_OTHER`,免。开服务时 `AccessibilityManagerService.isAccessibilityTargetAllowed` 查这个 appop,被拦则弹「For your security, this setting is currently unavailable」;Google 帮助页的解法是 应用信息 → 更多 → 允许受限设置,电视设置界面常没有这个菜单。adb 侧路:`appops set <包> ACCESS_RESTRICTED_SETTINGS allow`(二手,未测)。

## 2.2 探针实测(2026-10-02,`unitedu-gtv` = Google TV 14 + launcherx;`unitedu-tv` = GMS Android TV 14 + tvlauncher)

一次性探针 `test.homeprobe`(无障碍服务,`canRequestFilterKeyEvents` + 运行时 `FLAG_REQUEST_FILTER_KEY_EVENTS`,只听 `TYPE_WINDOW_STATE_CHANGED`,不读窗口内容),在另一个包里拉起 UnitedU;源码与脚本在会话 scratchpad,不进仓库。

| # | 测什么 | 结果 |
|---|---|---|
| 1 | HOME 能不能送到过滤器 | **能**,两台都能:按下、松开各一次,`flags=0x8`,原厂桌面的窗口事件在其后约 300 ms。**注意**:`adb shell input keyevent 3` 是注入事件,**不经过**无障碍过滤器(探针一条都收不到);测试必须走真实输入设备——用 `/system/bin/hid` 造一个 USB 消费类遥控(uhid,AC Home 0x0223 → `KEY_HOMEPAGE` → `KEYCODE_HOME`),`adb emu event send` 与 `sendevent` 在这两台上都到不了 |
| 2 | 吃掉 HOME + 拉起 UnitedU | 原厂桌面**一次都没出现**(没有它的窗口事件);后台拉起成功(探针与 UnitedU 不同包、不同进程,无障碍豁免照样生效) |
| 3 | 任务类型 | `MAIN + HOME` + 显式组件 → `type=home`;同一个组件用普通 intent → `type=standard`(先 force-stop 再测,对照干净) |
| 4 | 从应用按返回 | 落在 UnitedU,中间没有原厂桌面的窗口事件(= Projectivy #605 的闪烁不会出现) |
| 5 | 只靠盯窗口 | 原厂桌面先出来、再被拉走:Google TV 约 180 ms,Android TV 约 330 ms(第一个原厂窗口事件 → UnitedU 窗口事件) |
| 6 | 屏幕关着按 HOME | 按键不送过滤器(不带 `FLAG_PASS_TO_USER`),只唤醒,回到原来的应用——不需要处理 |
| 7 | 开机 | 服务在开机后约 27–31 s 连上;连上时拉一次 → 最后停在 UnitedU。连上之前原厂桌面已经在屏幕上(约 1.5 s),这段躲不掉 |
| 8 | 屏保开着按 HOME(我们的屏保) | **吃掉 HOME 时屏保不退出**,UnitedU 在屏保后面被拉起,用户卡在屏保里。AOSP `handleShortPressOnHome`:「有屏保在跑就用 home 退出屏保,但不真的回桌面」——不吃(放行)就是这个原生行为:屏保退出、回到屏保下面的应用。→ **屏保在前台时放行 HOME** |
| 9 | 受限设置:安装方式 | 模仿文件管理器(`ACTION_VIEW` + `content://` 交给系统安装器)装的包:`packageSource=3`(LOCAL_FILE),`ACCESS_RESTRICTED_SETTINGS: deny`。`adb install`:未设、默认 allow |
| 10 | 受限设置:用户能不能自己打开 | 不能。电视设置 → 无障碍 → 服务 → 启用 → 系统警告框「确定」→ 开关**静默停在关**(日志 `Skipping enabling service disallowed by device admin policy`),没有任何解释;`settings put secure enabled_accessibility_services` 也一样被系统清掉。应用信息页只有 卸载 / 存储 / 清数据 / 清缓存 / 清默认 / 通知 / 权限,**没有「允许受限设置」**(试过一次之后也没有)。只有 adb:`appops set <包> ACCESS_RESTRICTED_SETTINGS allow` 后立即可开 |
| 11 | **adb 装好之后,经系统安装器更新** | **会被重新锁上**:`packageSource` 变 3、appop 变 deny,**正在运行的服务被系统当场停掉、开关被清**。→ 我们的应用内更新(`ApkInstaller` 走 `ACTION_VIEW`)每更新一次就会悄悄关掉接管 |
| 12 | 经 PackageInstaller **会话 API** 更新 | `packageSource=0`,appop 保持 allow,服务更新后**自动重连**(不用用户再开)。→ 应用内自我更新必须改走会话 API |

## 3 对 UnitedU 的启示(进设计)

1. **A + B 双路**,与 Projectivy / ATV Launcher 同构:A 截 HOME(按下 + 松开都吃)不闪;B 兜开机、待机唤醒(屏幕关着时 HOME 送不到 A)、A 被 OEM 吃掉的机型。原厂桌面已在前台时 B 抓不到——这正是 A 存在的理由之一。
2. **拉起自己用 `ACTION_MAIN + CATEGORY_HOME` + 显式组件**,让任务成 `type=home`(#605 的教训;与 `RelaunchAfterUpdate` 里「只有隐式 HOME 意图会被建成 type=home 任务」同一条规律,区别是这里 HOME 解析不到我们、要点名)。应用 uid 下点名 HOME intent 是否仍成 `type=home`,进探针。
3. **服务存活要自己验**:设置页按 `AccessibilityManager.getEnabledAccessibilityServiceList` + 服务进程自报「已连接」显示,不信系统开关;`onServiceConnected` 时若原厂桌面在前台就拉一次。TCL 守卫之类写进 README。
4. **受限设置写进设置页文案**:开关打不开时提示「这台电视要用 adb 安装才能打开,或在应用信息里允许受限设置」;README 的侧载说明同步。
5. **只在 UnitedU 不是默认桌面时才开按键过滤**(A95L 不受影响);`onKeyEvent` 只看 HOME、其余第一行返回 false,把对别的应用的副作用压到最低。
6. **路 C 写进 README**:会 adb 的用户 `pm disable-user` 原厂桌面(Google TV 连带 `setupwraith`),比任何无障碍方案都干净;产品内不做。
7. Projectivy 单进程、我们分进程:它整个桌面进程常驻;我们把服务放小进程,桌面进程照常可回收。
8. (探针 #8)**屏保播放中放行 HOME**——按系统广播 `ACTION_DREAMING_STARTED / STOPPED` 记(实现时在 API 34 的两台模拟器上观察到:屏保窗口的无障碍事件类名是 `android.widget.FrameLayout`、不是 `DreamActivity`,尽管 `dumpsys window` 的 `mCurrentFocus` 写的是 `…/android.service.dreams.DreamActivity`;原因没有深究,总之按窗口类名认不出屏保);放行后系统按原生规则退出屏保。已知边角:服务刚连上时若屏保已在播放,`dreaming` 要等下一次广播才对(没有公开的「现在是不是在做梦」查询),那一次 HOME 会被吃掉,接受。
9. (探针 #11 / #12)**应用内自我更新改走 PackageInstaller 会话 API**,否则每次更新都会把接管关掉;传 APK 装别的应用照旧。
10. (探针 #9 / #10)Android 13+ 上侧载装的 UnitedU 打不开服务,电视设置也没有解锁入口:设置页要能认出这种状态,直接告诉用户那一条 adb 命令。**应用读不到自己的 `ACCESS_RESTRICTED_SETTINGS` appop**(要 `MANAGE_APPOPS`;实现时在 API 34 模拟器上四种读法全抛 `SecurityException`),所以改按安装来源推断(API ≥ 33 且 `getInstallSourceInfo(..).packageSource` 是 3 / 4),并且**见过一次就记住**——会话更新(#12)会把来源改回 0 而锁(deny)还在,只看当前来源就瞎了;Android 12 及以下(国产电视多数)没有这道锁。
