# 代码评审入口(给外部评审者)

2026-09-29 写,对应 main `6549fa0` 之后;2026-09-30 外观轮(R134–R136:字号 / 版式 / 动效统一)后更新;同日五路独立复审 + 模拟器端到端测试、R137–R140 修复后再更新;同日晚「视觉高级感」R141–R144 与测试轮(R145:JVM 边界单测 + 九段新端到端旅程 + 六个修复)后再更新;2026-10-02 加了主页键接管(R162:无障碍服务 + 自我更新改走会话 API)后再更新。先读这一份,再按需要跳到它指的文件;这里只讲「是什么、在哪、哪些看起来像 bug 其实是刻意的」,不重复设计论证。

## 1. 这是什么

- **产品**:UnitedU,Android TV 桌面(launcher)。一句话定义:**零广告、零推荐,只有用户自己放上去的应用**。面向**国行、没有 GMS 的 Android TV**;外观照着 Google TV(`launcherx`)复刻,内容与功能是自己的。产品基线见 [`DESIGN-unitedu-open-source.md`](DESIGN-unitedu-open-source.md),现行界面见 gtv 线设计稿(§6)。
- **唯一的真机**:国行 Sony A95L(`XR-77A95L`,MT5897),Android 14,**32 位 armeabi-v7a 用户空间**,界面层 1920×1080 @ 320 dpi(面板 4K,界面由固件放大),无 Google 服务。开发全程在 Android 14 TV 模拟器(arm64)上验证,真机只做里程碑验收。
- **形态**:单 Activity(`MainActivity`)+ Jetpack Compose(`androidx.tv:tv-material` 1.0.0 只用叶子组件)。包名 `com.uniteduone.launcher`,minSdk 28,target / compile 35。
- **联网**:只有两处——用户按「检查更新」时拉一次 `latest.json`;用户打开「从手机添加」页(R130 前叫「手机传输」)时在局域网起一个 HTTP 上传服务(关页即停)。没有统计、没有崩溃上报。
- **状态**:内部版本 `1.0.0-beta`(versionCode 2),**还没发布过**;仓库暂为私有。1.0 前剩下的事见 [`ui-pending.md`](ui-pending.md) F 组。

## 2. 怎么构建、测试

- 需要 JDK 17、Android SDK(compileSdk 35、build-tools **35.0.0**,`app/build.gradle.kts` 里显式钉了)、Gradle 8.14.x。**仓库里没有 Gradle Wrapper**。作者机器上工具链刻意不进 PATH,用 `source scripts/env.sh` 注入(路径是作者本机的,仅供参考)。
- 构建:`gradle --no-daemon assembleRelease`;单测:`gradle --no-daemon testReleaseUnitTest`(`app/src/test/`,96 个文件;**没有仪器测试**,界面行为靠模拟器端到端脚本 `scripts/e2e/`(16 段旅程,跑法见那里的 README)+ 真机验收,记录在 `WORKLOG.md`)。
- 没有 `~/.unitedu/release.jks` 时 release 自动用 debug keystore 签名(`-PrequireReleaseKey=true` 时改为构建失败,`scripts/release.sh` 总带这个参数)。R8 开着(`proguard-rules.pro`),资源裁剪关着(理由见 `build.gradle.kts` 注释)。
- lint:`lintVitalRelease` 通过;完整 `lintRelease` 报 22 个 error,其中 21 个是误报(`ProduceStateDoesNotAssignValue` ×16、`dispatchKeyEvent` 上的 `RestrictedApi` ×5),1 个是刻意的(`QUERY_ALL_PACKAGES`,桌面必须列出全部应用)。详见同日体检报告 [`design/health-check-2026-09-29.md`](design/health-check-2026-09-29.md)。
- 内置壁纸 / 屏保图有一道构建前置:`app/src/main/assets/builtin/{wallpapers,screensavers}/` 里的图必须先经 `scripts/hdr-assets.py --in-place` 转成双写法 HDR JPEG,否则单测 `BuiltinHdrAssetsTest` 失败(见 §5)。

## 3. 运行时结构(先有个整体图)

- **一个 Activity,一棵 Compose 树**。首页(`HomeScreen`)常驻;设置外壳、关于页、所有应用页、输入源页、选图页、扫码页、引导、各种菜单都是叠在它上面的**浮层**,开关状态全部住在 `MainActivity`(`shellStack`、`about`、`appsPage`、`inputsPage`、`pickerTarget`、`editing`、`onboarding`……),按键分发也在 `MainActivity.dispatchKeyEvent`(长按按「持续满 `LONG_PRESS_MS` = 600 ms」识别,不按 `repeatCount`)。
- **数据都在外置应用目录** `/sdcard/Android/data/com.uniteduone.launcher/files/`(`Paths.kt`,刻意不回落 internal,理由见其 KDoc):`layout.json`(行与应用:每行只有图标 + 应用,R163 起行没有名字;老文件里的 `name` 读盘时只用来回落图标)、`settings.json`、`titles.json`(自定义标题 / 输入源改名)、`hidden-inputs.json`、`icons/<包名>.png`(换过的卡片图)、`library/{wallpapers,cards,screensavers}/`(用户上传)。壁纸处理缓存在外置 cache。内置图直接从 APK assets 读,不复制。
- **进程模型**:除 `:homekey` 外所有组件(`MainActivity`、清单里的 `PackageRemovedReceiver` / `RelaunchAfterUpdate` / `SelfUpdateResult`、系统屏保 `UnitedUDream`、上传服务的请求线程)同一进程,所以文件锁用进程内锁就够(`LockedFile`)。**主页键接管的无障碍服务 `HomeKeyService` 跑在独立进程 `:homekey`**(R162:每一下按键都先经过它,不与首页抢主线程;桌面进程照常可回收),它**不碰任何状态文件**——`LockedFile` 的锁不跨进程——只写自己的心跳文件 `homekey.state`(单写者、原子写),主进程只读。
- **纯函数 / Android 分文件**:凡是能在 JVM 上测的规则都拆成不碰 Android 的文件(`*Pure.kt`、`*Model.kt`、`*Math.kt`、`PickerCells.kt`、`StandbySchedule.kt`、`UpdateChecker.kt`……),Compose / IO 那一半只接线。评审「规则对不对」看纯函数和它的单测,评审「接线 / 生命周期对不对」看 Compose 文件。

## 4. 文件地图(`app/src/main/java/com/uniteduone/launcher/`,100 个文件)

**入口与全局状态**
- `MainActivity.kt`(2100+ 行):浮层状态机、按键分发、待机计时、Bundle 保存 / 还原、包变动广播、语言切换 `recreate()`。评审重点文件。
- `LocaleOverride.kt`:应用内语言(不用 AppCompat per-app locale);`SettingsRestorePolicy.kt`:重建时要不要把设置外壳从 Bundle 种回。
- `AppToast.kt`:应用内提示条(R139:`ToastHost` 画在整棵树最上层,页面经 `LocalToast` 发,MainActivity 的 `toast()` 也走它)。
- `RelaunchAfterUpdate.kt` / `RelaunchPolicy.kt` / `RelaunchMarks.kt`:更新本应用会删掉首页任务;更新后要不要要求一次 HOME 把桌面拉回来(R151:仍是默认桌面〔R162:或主页键接管开着,此时 intent 点名自己〕+ 有「显示在其他应用上层」权限 + 被杀那一刻桌面 / 屏保在屏幕上或刚从关于页发起更新;别的应用在前台时不拉)。
- `Ambient.kt`:整屏页的氛围底(R142:当前壁纸的模糊影子,`pageBackdrop()`;纯计算 `ambientPixels` 有 JVM 单测)。

**首页、行、卡片**
- `HomeScreen.kt`:首页(壁纸层 + 卡片行 + 顶栏)、纵向 / 横向位移自算、焦点看门狗与还原、R129 换行淡入;`CategoryRow` 是一行。
- `AppCard.kt`:卡片(tv-material `Card`)、卡片淡化 `gtvCardFade`;`GtvFocusStroke.kt`:焦点描边 + 柔光;`CardColor.kt`:图标回落底色 / 横幅判据。
- `GtvTopBar.kt`:顶栏三颗胶囊(设置 / 应用 / 输入源)+ 右侧时钟字标;`Clock.kt`:时钟格式与跳变监听。
- `GtvLayout.kt`(几何与动效常量,1100+ 行,KDoc 里是每个数的出处)、`GtvTokens.kt`(颜色)、`Type.kt`(R134:全应用的字号七档 `Type` 与文字色四档 `Ink`,界面代码不许再写死字号)、`PageChrome.kt`(R135:整屏页两种版式里「页头 + 网格」那一种的页头 `PageHeader`、「＋」图标)、`Theme.kt`(动画规格、`CardMetrics`;旧的文字色名字都指到 `Ink`)、`ThemePresets.kt` / `ThemeResolve.kt` / `UnitedUTheme.kt`(主题色)、`HomeLayout.kt`(旧 main 线几何,只剩少量引用)。
- `HomeBackdrop.kt` + `Wallpapers.kt` + `WallpaperMath.kt`:壁纸解码 / 模糊亮度处理 / 缓存 / 两张缓存图层。
- `Move.kt`:首页原地移动态;`CardMenu.kt`:长按菜单项;`GearMenu.kt`:菜单浮层(名字是历史遗留,现在给长按卡片菜单、编辑页菜单等用)。
- `Apps.kt`(枚举可启动应用、选卡片图)、`Model.kt`、`PickerGroups.kt`(「应用 / 系统工具」分组、「新」应用计数)。
- 频道行(R164):`ChannelModel.kt`(纯模型:TvProvider 列名、解析、按 key / 名字匹配、排序截断、元数据、选频道页的列表与重定位)、`ChannelSource.kt`(读 TvProvider:显式投影、`?package=`、按频道取节目)、`ChannelCache.kt`(进程级频道缓存:首页 / 编辑页 / 选频道页共用一份,`channelsRevision` 驱动刷新、内容相同不通知)、`PosterLoader.kt`(海报:ContentResolver / https 5 s 总期限、宽高比 0.5–2.5、输出宽 ≤ 440 px、16 MB LRU、失败一分钟内不重试)、`ChannelLaunch.kt`(启动节目:去掉 URI 授权位、只许发布方自己的包)、`ChannelEnv.kt`(`LocalChannelEnv`)、`ChannelInit.kt`(`INITIALIZE_PROGRAMS` + `channel-init.json`)、`HomeVertical.kt`(首页纵向几何逐行累计)、`HomeChannels.kt` + `HomeChannelRow.kt`(首页频道行)、`ChannelPicker.kt`(选频道页)。
- `AppsPage.kt`:所有应用页;`Inputs.kt` + `InputPrefs.kt` + `InputsPage.kt`:输入源枚举、CEC 去重、调谐器合并、改名 / 隐藏、输入源页。

**编辑与数据**
- 编辑桌面(R165「货架」):`EditScreen.kt`(焦点账本、按键截获、浮层摞、写盘、拿起)、`EditShelves.kt`(纯逻辑:货架模型、上下键固定顺序、落点、焦点线位移,单测)、`EditShelfParts.kt`(架子 / 胶囊 / 选择卡等零件)、`EditPress.kt`(确定键长短判定)、`EditBackdrop.kt`(壁纸模糊底,算一次缓存位图)、`AppPicker.kt`(添加应用列表);`LayoutOps.kt`:行的增删改纯函数;`RowIcons.kt` / `RowIcon.kt` / `RowIconPicker.kt`:行图标。
- `Layout.kt`(`layout.json` 读写、`layoutWrites` 串行调度器)、`Titles.kt`、`Settings.kt`(`Settings` 数据类、合法值表、`SettingsStore`)、`LockedFile.kt`(多写者文件锁 + 原子写)、`Paths.kt`。
- `PackagePruning.kt`(卸载后清理 + `PackageRemovedReceiver`)、`PrunePure.kt`(回到前台清理未安装包的判据,含「缺得太多就不清」的保护)。

**设置外壳**
- `SettingsModel.kt`(四组、每行、写入函数,纯数据)、`ShellModel.kt`(导航栈、页 id、每页缺省焦点、`MAX_CAPSULES_PER_PAGE`、预览状态机,纯模型)、`SettingsShell.kt`(`CapsuleColumn`:外壳每一层的焦点账本)、`ShellCapsule.kt`(`MenuPill` 胶囊渲染)、`SettingsFade.kt`(淡入淡出 `FadeSwitch`、残影 `LocalPageGhost`、`ShellMotion`;R136 起所有浮层经 `OverlayStack` 走同一套)。
- `AboutScreen.kt` + `Update.kt` + `UpdateChecker.kt`:关于页与手动检查更新(判定全在 `UpdateChecker.kt`,纯函数;装更新交给 `SelfUpdate.kt`);`ApkInstaller.kt`:传 APK 装**别的**应用的 `ACTION_VIEW` 安装器(扫码页上传 + FileProvider;传的恰是 UnitedU 自己时 `UploadServer` 按包名分流、改走 `SelfUpdate`);`SelfUpdate` 复用它的权限引导(`requestInstallPermission`)与 `Result`;`HomeSettingsCard.kt`:当前默认桌面;`SystemStatus.kt` + `SystemStatusReader.kt`:只读系统屏保 / 动画缩放 / `sleep_timeout` 快照;`TitleDialog.kt`(改名页:卡片改名、输入源改名;R163 起行没有名字,没有行改名)/ `ConfirmDialog.kt`(R135 起只是 `GearMenu` 的薄封装:两颗胶囊「取消 / 确定」)。
- `UpdateChannels.kt`(R166):稳定版 / Beta 双通道的判定,纯函数 `resolveChannel`(Beta 读 `latest.json` + `beta.json` 取 versionCode 大的;稳定通道已装版本更大时改读 `rollback.json`);`Update.kt` 的 `checkChannel` 按设置里的 `updateChannel` 取清单,`scripts/release.sh` 的 Beta 模式负责发布与回退包。
- `Onboarding.kt` + `OnboardingPure.kt`:首次引导三步(R135 起每步一份 `CapsuleColumn`,不再自带焦点账本)。

**选图、导入、上传**
- `ImagePicker.kt`(1100 行):壁纸 / 卡片图选择器、屏保图库(`PickerGrid` 一个网格一套焦点,内置 + 「＋」+ 我的);`PickerCells.kt`:格子号换算。
- `BuiltinCatalog.kt`(内置图命名规则、三语名字表 `assets/builtin/names.txt` 的解析与取名,纯函数;R137;卡片对应应用表 `assets/builtin/card-apps.txt` 的解析与按包名筛选,R159)+ `BuiltinImages.kt`(assets 发现与读取、伪路径 `/android_asset/…` 解码)。
- `ImportScreen.kt`(扫码页)、`UploadServer.kt`(NanoHTTPD,端口 8090–8099,只在扫码页开着时运行)、`UploadPure.kt`(类型 / 文件名清洗 / 上限)、网页在 `app/src/main/assets/web/index.html`(R130 起:服务端注入 `__STRINGS__` 文案表 `WEB_STRING_KEYS` / `WEB_PLURALS`、`__DEFAULT_TAB__`、`__ACCENT__` 主题色、`__LANG__`;网页每 6 秒拉一次列表兼做连接检测)。文案防线在 `CopyTest`:三语 key 与占位符一致、网页用到的每个 `S.xxx` 都已注入、设置每一行都有说明。

**屏保、视频、HDR**
- `StandbySchedule.kt`(待机 / 屏保两个时刻,纯函数)、`Screensaver.kt`(桌面屏保层)、`ScreensaverPlayer.kt`(进程级播放器单例:扫描、下标、换图计时、引用计数)、`ScreensaverMotion.kt`(推拉摇移)、`ScreensaverMedia.kt`(照片 / 视频判定与上限)、`UnitedUDream.kt`(系统屏保 `DreamService`,自己管 Lifecycle / SavedState)。
- `VideoPlayback.kt`:进程内同一时刻最多一个 `MediaPlayer`(`VideoGate`)、`TextureView` 播放、首帧缩略图。**资源泄漏类评审的重点**。
- `HdrImageMath.kt`(解码尺寸、增益图几何,纯函数)+ `HdrGainmaps.kt`(API 34+ `Gainmap`、HDR/SDR 比例档位监听)。

**主页键接管、自我更新(R162)**
- `HomeKeyService.kt`:无障碍服务,**独立进程 `:homekey`**。`onKeyEvent` 截 HOME(按下拉起首页,按下 + 松开都吃——系统在松开那一下才回桌面;长按等于短按)+ `onAccessibilityEvent` 盯别的桌面的 HOME Activity 到前台就拉回(开机、系统别的路径回桌面的兜底,会先闪一下原厂桌面);按键去重与配对见 `HomeKeyPresses`(同一个 `downTime` 的重复 DOWN 只拉一次,松开跟着按下走);拉起后 1 s 去抖内丢掉的原厂桌面窗口事件,去抖结束后补查一次(`recheck`);拉起一律 `MAIN + HOME` + 显式组件(任务才是 `type=home`,从应用按返回不闪);只在 UnitedU 不是默认桌面时生效;屏保播放中放行 HOME(按系统 `ACTION_DREAMING_*` 广播记,屏保窗口的无障碍事件类名认不出屏保)。只写自己的心跳文件。
- `HomeKeyPolicy.kt`:上面所有判定的纯函数(`onHomeKey` / `HomeKeyPresses` / `onWindowChanged` / `debouncedOnly` / `recheckDelayMs` / `shouldLaunchOnConnect` / `stockHomes`),一行 Android 都不碰,单测 `HomeKeyPolicyTest`。
- `HomeKeyState.kt`:状态合成与读写——心跳文件(服务写、主进程读)、「开关开着」(系统已启用服务列表含本包)、「在运行」(心跳 + 系统此刻真的绑定着)、「受限」(Android 13+,按安装来源推断,加一个「见过一次」的记号 `restrictedSeen`,主进程的 SharedPreferences `homekey`);`HomeKeyStatus` 四态与设置页 / 引导的文案取用函数。单测 `HomeKeyStateTest`。
- `SelfUpdate.kt`:自我更新走 PackageInstaller **会话** API + 结果接收器 `SelfUpdateResult`(替系统打开确认页;非取消的会话失败弹一条系统 Toast,带状态码)。两个调用方:关于页「下载并安装」,和手机传 APK 时传的恰是 UnitedU 自己(`UploadServer.serveApk` 按包名分流;此时字节在会话里,文件立即释放)。不是 `ApkInstaller` 的 `ACTION_VIEW`:那会把本包标成受限、把正在跑的接管服务当场停掉(调研 §2.2 #11 / #12)。

**更新后自启**:`RelaunchAfterUpdate.kt` + `RelaunchPolicy.kt`(`MY_PACKAGE_REPLACED` 后在满足条件时把自己拉回前台)。

## 5. 报 bug 之前必须知道的约束(看起来怪、但是刻意的)

焦点相关的七条铁律全文在 [`../CLAUDE.md`](../CLAUDE.md)「改这份界面前必须知道的七条」,每个浮层谁负责焦点恢复有一张表;这里只列要点:

1. **不用任何可滚动容器**(`LazyRow` / `LazyColumn` / `verticalScroll` / `horizontalScroll`):真机上会让上下键把焦点整棵树丢掉。所以首页、编辑页、所有应用页、图片网格的位移都是**自己算**的(`Modifier.offset` + 动画),容器必须配 `wrapContentWidth/Height(unbounded = true)`,否则超出屏幕的条目被量成 0 宽、永远聚焦不到。「为什么不用 LazyRow」「为什么手算 offset」都不是 bug。唯一例外:编辑页「添加应用」列表 `AppPicker` 是一个单列 `LazyColumn`(纵向单列没踩到那个坑),它的焦点恢复要逐项挂 requester(铁律 3)。
2. **焦点是否落下只信目标自报的 `isFocused`**:`requestFocus()` 返回 Unit,`runCatching { … }.isSuccess` 恒真。所以到处都是「等首帧 → 请求 → 看自报 → 重试」的循环。
3. **丢焦点靠看门狗**(`holder == null` 时 3 帧宽限后重请求,最多 60 帧),每个浮层自己负责自己的恢复,外层看门狗会为浮层让路(`covered`)。
4. **「有没有焦点」只信控件自报**,不信根节点的 `onFocusChanged`(退到后台再回来时它不重发)。
5. **目标与当前位置分开,每个坐标分量都拆,且从 `ON_PAUSE` 起冻结**:Compose 会抢先把焦点给第一张卡,共用一个量会把记忆改写成 0。所以有 `tgtLayoutRow` / `tgtCol` / `tgtPill`(首页按 layout.json 行号认行,画出来的行号会因频道行出现 / 消失而挪动)、`frozenTarget`、`restoring` 这些看似重复的量。
6. **`LaunchedEffect` 的守卫与 key 成对出现**:写了 `if (X) return@LaunchedEffect`,X 就在 key 里。评审时反过来检查这一条很有价值。
7. **不用一次性布尔闩**,用 nonce 比对(`focusNonce`)这类可自愈的判据。

其他:
- **落盘**:有多个写者的状态文件(layout / titles / hidden-inputs / settings)一律经 `LockedFile`;读 → 改 → 写整段在锁里(`update`);整份快照写盘还要走串行调度器 `layoutWrites`;rename 失败不删正式文件(先有 `.prev`)。排查表 [`design/persistence-audit.md`](design/persistence-audit.md)。起因是一次真机事故:卸载一个应用,整个首页被换成默认布局。
- **外置存储没挂时不写盘**(`Paths.baseOrNull` 为 null → 内存默认值、不落盘),不是漏写。
- **没有 `ModulateAlpha`、行图层四边撑大 `APP_FOCUS_GLOW_DP`**:alpha < 1 的 `graphicsLayer` 会把内容画进以图层尺寸为界的离屏层,焦点卡的放大 / 描边 / 柔光越界会被裁(R129f)。
- **字号与文字色只有 `Type.kt` 一处**(R134):界面代码里出现 `fontSize = 数字.sp`、Bold、`Theme.Sans` 以外的字体都会被单测 `TypeScaleTest` 拦下(它扫源码)。字重只用 Normal / Medium 是刻意的:中文回落到系统 Noto Sans CJK(只有 Regular),Bold 会被合成加粗。
- **放进 `OverlayStack` / `FadeSwitch` 的页面必须照 `LocalPageGhost` 让路**(R108 / R136):关掉后同一棵子树还要画一个淡出时长,这期间它不可聚焦、不请求焦点、不收返回键、不回调;状态对象把要显示的内容带在身上(残影不按下标现查)。看到 `if (ghost) …` 到处都是,不是重复代码。
- **残影不靠帧时钟收尾**(R140):宿主掉出 STARTED 时 Compose 暂停帧时钟,`FadeSwitch` 让淡出与之赛跑、先掉出就当场拿掉残影;持有资源的页面在变成残影那一刻就放手(扫码页的上传服务在 `DisposableEffect(ghost)` 里停)。评审「关页后服务 / 播放器还活着」类问题时按这两条看。
- **提示一律走应用内提示条**(R139,`AppToast.kt` 的 `ToastHost` / `LocalToast`):只有 Activity 不在前台、以及紧接着就离开本应用的那一条(打开原厂桌面)仍用系统 Toast——这是刻意的,不是漏改。
- **整屏页的底一律 `Modifier.pageBackdrop()`**(R142),不要再写 `.background(GtvTokens.MenuBg)`——否则那一页会是一块纯黑,与其他页、与交叉淡化的前后两层对不上。未聚焦的表面用半透明的 `GtvTokens.SurfaceIdle` / `SurfacePlaceholder`(R144),`MenuItemIdle` 只留给对比度单测。
- **设置页每页胶囊 ≤ 6**(R128),由 `SettingsPageLimitTest` 按界面用的同一份表逐页数;新加条件行要把触发它的系统状态加进测试。
- **只读系统设置,从不写**:系统屏保、动画缩放、`sleep_timeout` 都只显示并跳系统页;需要 `WRITE_SECURE_SETTINGS` 的做法被刻意否掉。
- **HDR**:内置图是 Ultra HDR(XMP `hdrgm` + ISO 21496-1 双写法),解码保留增益图、处理链单独处理增益图(R122–R125)。模拟器与 A95L 的显示器都不报 HDR/SDR 比例,Android 14 会把 HDR 窗口静默降成 sRGB——**在这两处看不到 HDR 是预期**,验证只能靠 `Bitmap.hasGainmap()` 日志。
- **性能**:A95L 上 `adb install` 的包是未编译的(`status=verify`),按键帧 UI 线程约 15 ms,`cmd package compile -m speed-profile` 后约 5 ms;评估卡顿前先确认编译状态(`design/perf-2026-09-28.md`)。
- **TvProvider 没授权时返回 0 行、不抛异常**:「需要重新授权」以 `checkSelfPermission(READ_TV_LISTINGS)` 判,`SecurityException` 只是兜底。查询一律显式投影、不带 selection(带了 TvProvider 抛 `SecurityException`)。不看 `browsable`(国行与 Google TV 上没人审批,永远是 0)。读失败(异常 / 游标为 null)时快照整体返回 null、缓存留上一份;设备根本没有 TvProvider 才算「空」。
- **节目的 `intent_uri` 是别的应用写的**:`ChannelLaunch` 去掉一切 URI 授权位、只启动解析到发布方自己包的 Activity(并把 `intent.component` 钉到审过的组件),不合规就退回打开那个应用。海报同样来自第三方,解码按宽高双向约束、像素数先查再解。不要「简化」掉这些。
- 频道行在 layout.json 里是带 `channel` 字段的空应用行;**旧版本**读到它是空应用行,第一次写盘就把它变成真的空应用行(版本只升不降,接受),新版本读回时应用行可能多于 5 行——这是合法状态(不能再加应用行),不是损坏。

## 6. 已知问题与接受的限制(不必再报)

- **局域网上传服务没有鉴权 / token**(M6 设计时接受):只在扫码页开着时运行、关页即停,端口 8090–8099。若评审认为风险需要重估,欢迎给具体建议。
- **指针输入(飞鼠 / 触摸)会让窗口进触摸模式**:用 foundation `clickable` 的菜单 / 设置行在触摸模式下拒绝 `requestFocus()`,要等第一下方向键才恢复焦点;看门狗对此无能为力。目标设备的遥控器没有指针,未修(WORKLOG「遗留修复批」Ruling R7)。
- **`MainActivity` 同时挂 `LEANBACK_LAUNCHER` 与 `HOME`**:API 29+ 上可能出现两个实例(例如先 `am start -n` 再发 HOME intent);建议过跳板 Activity,未做。
- **「自动关屏」行(R132 前叫「关闭屏幕」)确定键只能开系统设置首页**:那一页(TvSettings 的 `EnergySaverFragment`)在 AOSP 与索尼上都没有外部 intent 入口;行下小字从电视自己的设置应用里读真实菜单名(R127c)。
- **R129 换行淡入按几何只在下键触发**:我们的焦点线在屏幕下部,上一行静止时全亮,单按上键不满足「换行前看不见」的条件(连按时才会);这是规则的结果,不是漏写。
- **A95L 界面层显示不了 HDR**(见 §5),内置 HDR 图在它上面等于 SDR。
- **主线程 / 组合期的文件 IO**:有几处小文件读取在主线程或组合期里(`MainActivity.kt` 约 468 / 1449 / 1498 / 1822 行、`EditScreen.kt` 里 `rows` 的初值 `Layout.read`),体检报告里列了;目前文件都很小,未改。
- **超大 composable**:`EditScreen.kt` 约 890 行,其中 `EditScreen` 这个 composable 约 550 行(R165 已拆出纯逻辑与零件)、`HomeScreen` 约 800 行、`MainActivity.onCreate` 约 580 行。拆分是已知的技术债,不是本轮目标。
- **遗留代码**:`Theme.cardMetrics` / `HomeLayout` 是旧 main 线几何,只剩测试与少量常量引用;`GtvLayout.SETTINGS_TYPE_STEP` / `settingsSp` 是 R109 的记录,界面代码不再读(`Clock.kt` 的 `HeroClock` 已在 R134 删掉)。
- **代码注释里的少量过时描述**:例如 `GtvLayout.kt` 约 981 行 `ROW_ENTER_*` 的 KDoc 仍写 `FastOutSlowIn`(R129e 起实际是 `LinearEasing`,见 `Theme.homeRowEnterSpec` 与同处 R129e 注释);`Settings.kt` 里 `wallpaperBlur` 注释写「0–100,步 10」(R119 起 0–50、步 5,以 `WALLPAPER_BLUR_MAX` / `_STEP` 为准);`MainActivity` 顶部 KDoc 仍提「齿轮菜单入口」(R69 起是设置外壳)。以代码为准。
- **测试轮留着没修的两处**(`docs/design/test-round-2026-09-30.md`):`standbyPlan(1, Long.MAX_VALUE)` 溢出成负数——设置只允许固定档位,实际走不到,单测里是 `@Ignore` 用例;`sanitizeUploadName` 不去 C1 控制字符(U+0080–009F),它们在 ext4 上是合法文件名,不影响落盘与删除。
- **主页键接管(R162)的接受限制**:开机时服务连上之前(约 1.5 s)原厂桌面会先露面;开机后 30–60 s 服务连上时会把 UnitedU 拉到前面一次(开机 3 分钟内连上就拉),设成「开机进上次信号源」的电视上会盖住 HDMI 画面;待机唤醒那一下 HOME 截不到(屏幕关着时按键不送无障碍,只唤醒);服务刚连上时屏保若已在播放,`dreaming` 要等下一次广播才对(没有公开查询,那一次 HOME 被吃);Android 13+ 用系统安装器侧载的包受「受限设置」锁,电视设置里没有解锁入口,只能 adb(设置页直接写出命令;`adb install -r` 覆盖装不清已有的锁,要先卸载再装,2026-10-02 模拟器实测);Fire OS 不支持(Fire OS 8 的 HOME 在系统按键策略里先被吃掉,送不到无障碍过滤器)。依据与探针见 `research/2026-10-02-home-key-takeover-prior-art.md`。
- **「这台电视不允许」是推断,不是读锁**:应用读不到那个 appop(要 `MANAGE_APPOPS`),按安装来源(`packageSource` 为本地 / 下载文件)加「见过一次」记号推断;会话更新会把来源归 0 而锁还在(靠记号);已用 adb 解锁但还没开过服务时仍报「不允许」(按钮照样带去无障碍页);安装来源仍是文件(3 / 4)时,关掉开关后记号会被重新点亮、小字回到「不允许」;`pm clear` 清记号而 appop 不清、Auto Backup 可能把记号带到别的机器——都只影响小字。**自我更新的 16 MB 拷贝 + fsync 在主线程**(`SelfUpdate.install`,确认前台与 `commit` 要在同一个主线程回合;电视上若有卡顿再挪到 IO;手机传来的恰是 UnitedU 自己时走同一处,上限是传 APK 的 100 MB,实际约 16 MB)。复审时登记、没修的两处:`HomeKeyService.launch()` 失败(厂商拦后台启动)时 HOME 照吃,成了死键;自我更新的非取消类失败(存储不足、签名不符、被策略拦下)只弹一条系统 Toast(带状态码),关于页仍停在「安装中」。
- **数据格式向下兼容(R166,约束不是 bug)**:从 Beta 切回稳定版装的是旧代码(回退包),它要读 Beta 写下的 layout / titles / hidden-inputs / settings,所以 Beta 只能**新增**字段、不改既有字段的含义与类型(CLAUDE.md「数据格式向下兼容铁律」,`ForwardCompatTest` 逐个读盘函数钉住)。评审时看到“只加不改”的落盘改动别当过度设计。
- **更新通道顺序(Gordon 2026-10-09 定)**:R2 优先、GitHub 兜底、先到先得;清单读到后下载失败不换通道重试,不改。

- 频道海报是 https 时会联网下载(除「检查更新」「上传资料」外唯一的联网点,只在用户自己加了频道行时);`http://` 明文海报按网络安全配置画成无图卡。
- 频道数据一变(TvProvider 去抖 500 ms)首页就重读频道内容;只有内容真的变了才冻结焦点目标重定位一次。
- 冷启动时第一行是频道行而缓存尚未就绪:焦点落第一个应用行,频道行出现后不跳回(铁律 5 优先于「首屏落最上面」)。
- 选频道页在授权窗开着时 Activity 重建会丢回调(页面停在「询问中」,按返回即可);没有横幅的应用在选频道页卡片上回落成整句「应用 · 频道」文字,略挤。
- 国行 A95L 上有没有频道数据、Kodi 型「不 browsable 就不写节目」的应用会怎样,只能随正式版在电视上看。

## 7. 最有价值的评审方向

1. **正确性**:纯函数与它的单测是否真覆盖了边界(`LayoutOps`、`Move`、`PrunePure`、`PickerCells`、`StandbySchedule`、`UpdateChecker`、`ShellModel`)。
2. **焦点 / 生命周期竞态**:对照 CLAUDE.md 焦点责任表逐个浮层看——守卫与 key 是否成对、目标是否在 `ON_PAUSE` 冻结、浮层关掉后谁接回焦点、`recreate()` 与 Bundle 还原(`onSaveInstanceState` / `SettingsRestorePolicy`)、`onNewIntent`(= HOME)时每个浮层是否都收干净。
3. **落盘竞态**:任何绕过 `LockedFile` / `layoutWrites` 的写;卸载广播(动态接收器 + 清单接收器)与编辑页 / 引导同时写 `layout.json` 的交错。
4. **资源泄漏**:`VideoPlayback`(`MediaPlayer` 获取 / 释放、`TextureView` surface、`VideoGate` 交接)、`ScreensaverPlayer` 引用计数、`UnitedUDream` 的 Lifecycle、`UploadServer` 的启停与临时文件、大位图(4K 壁纸 + 增益图)的持有时长。
5. **线程**:哪些 IO 在主线程 / 组合期,协程作用域是否随界面取消,`AboutController` 的下载在关页 / 重建时是否正确取消。
6. **安全**:上传服务的输入校验(`UploadPure.sanitizeUploadName`、大小上限、multipart 临时文件)、更新包校验链(SHA-256 + 包名 + 版本号 + 签名证书,`UpdateChecker.kt` / `Update.kt`)、`FileProvider` 暴露范围(`res/xml/file_paths.xml`)。

## 8. 设计决策在哪查

- **gtv 线设计稿** [`superpowers/specs/2026-09-20-gtv-line-design.md`](superpowers/specs/2026-09-20-gtv-line-design.md):现行界面的规格,**§12 裁定索引**每条 R 号一行「内容 + 现状」(现行 / 被取代 / 回滚 / 定案)。改动或疑问先查这里。
- **R 号有好几个命名空间,同号不同义**:
  - **gtv spec 的 R 号**:§12 索引从 **R29** 起;更早的(R9 前后到 R28)散在该文件前面各节与 `WORKLOG.md` 2026-09-20 / 21 各节(gtv 线整分支终审 + owner 真机走查 Round 1–7)。代码注释里写 `Ruling R18`、`R23`、`R95`、`R129` 这类,指的都是这一套。
  - **`M4b-R1…R17`**:M4b(行管理 / 原地移动 / 输入源)执行台账的裁定,摘要在 `WORKLOG.md`「M4b」一节,决定表在 `superpowers/specs/2026-09-19-m4b-rows-move-inputs-design.md` §0。
  - **「owner 反馈 R2」「R2 Fix 3」**:指 owner 真机走查的第几轮(Round 2),不是裁定号,见 `WORKLOG.md` 2026-09-21 各节。
  - **`Controller ruling R4`**(如 `UpdateChecker.kt`)、**「leftover-fixes Ruling R10」「Ruling R7」**:各个实施计划执行时的裁定,只在那一期的计划 / WORKLOG 小节里有效——对应 [`superpowers/plans/`](superpowers/plans/) 里的计划(M7、2026-09-19 遗留修复批等)和 `WORKLOG.md` 同日一节。
  - 还有 spec 各节里的 **B1–B9、§0-n**:gtv 对照报告与 M4b spec 的条目号。
- **产品基线** [`DESIGN-unitedu-open-source.md`](DESIGN-unitedu-open-source.md):§1 产品定义、§9 不做什么;被 gtv 线取代的句子已划掉并写了现状。
- **各里程碑 spec / plan**:[`superpowers/specs/`](superpowers/specs/)、[`superpowers/plans/`](superpowers/plans/)(M1–M8、M4b、M5、设置外壳、顶栏、视频屏保)。
- **设置页逐页清单**:[`design/settings-inventory.md`](design/settings-inventory.md)。**持久化排查表**:[`design/persistence-audit.md`](design/persistence-audit.md)。**性能报告**:[`design/perf-2026-09-28.md`](design/perf-2026-09-28.md)。**HDR 生图与转换**:[`design/hdr-image-spec.md`](design/hdr-image-spec.md)。**内置图放法**:[`design/builtin-assets.md`](design/builtin-assets.md)。**动效实测**:[`design/vertical-motion/`](design/vertical-motion/)。**体检报告**:[`design/health-check-2026-09-29.md`](design/health-check-2026-09-29.md)。
- **工作日志** [`WORKLOG.md`](WORKLOG.md):按日期的排查过程、实测数字、未验证项与决策;真机上看到过什么、Gordon 原话是什么,都在这里。
- **作者给 AI 助手的手册** [`../CLAUDE.md`](../CLAUDE.md):构建、模拟器与真机的坑、焦点七条与责任表、落盘铁律、内置图铁律。作者本机环境(参考真机的无线调试地址、装包注意事项、代理)在不入库的 `CLAUDE.local.md`,外部评审看不到也不需要。
- **用户文档双语**:`README.md`(英文主版)与 `README.zh-CN.md`(中文)内容对齐,改一份要同步另一份;Release 说明、`NOTICE`、Issue 表单也是中英双语。`docs/` 下的开发记录只有中文。`TVHOME-README-focus-rules.md` 是前身项目 TvHome 的 README(焦点铁律的来源),铁律已搬进 CLAUDE.md,该文件留作历史。
