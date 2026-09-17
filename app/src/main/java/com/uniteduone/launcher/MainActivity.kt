package com.uniteduone.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.provider.Settings
import android.view.SoundEffectConstants
import android.widget.Toast
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面主界面。这里只管三件事:待机计时、返回键不退出、齿轮菜单的入口。
 * 视觉全在 HomeScreen / AppCard / Clock 里,规格见 docs/DESIGN-custom-launcher.md §4。
 */
private const val PICK_WALLPAPER = "__wallpaper__"
private const val VIEW_SCREENSAVER_POOL = "__screensaver_pool__"
private const val VIEW_HOME_SETTINGS = "__home_settings__"
private const val VIEW_IMPORT = "__import__"

/**
 * `onSaveInstanceState` 里设置页那几个键(T8,spec §5)。切语言 `recreate()` 会把整个
 * Activity —— 连同 `settings`/`settingsPos` 这两个普通字段 —— 一起销毁重建,唯一能跨过
 * 这一趟的是 Bundle。四个键各自独立、命名成组:T10 加引导步骤要存的 `onbStep` 照此追加
 * 一个同构的常量,不要挤进这几个已有的键里。
 */
private const val KEY_SETTINGS_OPEN = "settingsOpen"
private const val KEY_SETTINGS_PANE = "pane"
private const val KEY_SETTINGS_GROUP = "group"
private const val KEY_SETTINGS_ROW = "row"
/** 见 [MainActivity.selfTriggeredRecreate] 的 KDoc、`SettingsRestorePolicy.kt`。 */
private const val KEY_SELF_RECREATE = "selfTriggeredRecreate"
/** 首次引导停在第几步(T10)。只在引导开着时写;还原规则见 `onCreate` 里引导那一段。 */
private const val KEY_ONB_STEP = "onbStep"

class MainActivity : ComponentActivity() {

    private var lastInput by mutableStateOf(System.currentTimeMillis())
    /** 焦点自救计数:界面报告「整棵树都没有焦点」时 +1,让它重新请求。 */
    private var focusNonce by mutableStateOf(0)
    private var menuOpen by mutableStateOf(false)
    private var editing by mutableStateOf(false)
    /** UnitedU 设置页浮层是否打开。**M7 T5 起它叠在常驻首页之上**(不再替换),首页在底下做实时预览。 */
    private var settings by mutableStateOf(false)
    /**
     * 设置页上次停在哪一格(pane/group/row)。切语言要 `recreate()`(spec §5),
     * 那一趟整个 Activity —— 连同这个字段本身 —— 都会被销毁重建;真正跨得过去的是
     * `onSaveInstanceState` 写进 Bundle 的那一份副本(T8):`onCreate(savedInstanceState)`
     * 收到后原样种回这里,再喂给 `SettingsScreen(initialPos = …)` 当 `remember` 的初值——
     * 种子只在挂载那一刻被消费一次,不留一次性布尔闩(rule 7),目标(种到哪一格)与
     * 「当前焦点真的在哪」全程分开(rule 5)。
     * **不是 `mutableStateOf`**:它只在设置页挂载的那一刻被读一次(喂给 `remember` 的初值),
     * 做成状态只会让每次上报都触发一次无谓的重组。
     */
    private var settingsPos: SettingsPos? = null
    /**
     * 即将调用的 `recreate()` 是不是我们自己在 [applyLanguage] 里主动喊的——调用前置真,
     * `onSaveInstanceState` 把它一起写进 Bundle,新实例的 `onCreate` 读回来决定要不要
     * 信任 Bundle 里的 `settingsOpen`(见 `shouldRestoreSettingsFromBundle` 的 KDoc,
     * 2026-09-17 review round 2:`recreate()` 会原样沿用创建这个实例时的旧 intent,
     * 真机上那几乎总是 HOME——不能拿 intent 本身去分辨「这趟重建是不是语言切换」,
     * 只能靠我们自己显式做个记号)。**不是 `mutableStateOf`**:只在「置真 → 马上被
     * `onSaveInstanceState` 读走」这一小段同步窗口内活着,不参与任何组合;这个实例
     * 随 `recreate()` 销毁后这个字段也跟着作废,不需要写回 false(rule 7:一次性标记
     * 挂在「即将销毁的旧实例」上,天生没有「卡在 true」的路)。
     */
    private var selfTriggeredRecreate = false
    /** 换过图/改过布局后 +1,用来强制界面重新读取 */
    private var revision by mutableStateOf(0)
    /**
     * 只重读 settings.json、**不重建首页行**的计数器。壁纸选图 / 轮播 / 设置页每一次改动走它:
     * 这些事很频繁,若走 revision 会连 layout.json 与全部卡片图一起重读一遍。
     */
    private var settingsRevision by mutableStateOf(0)
    /**
     * **壁纸渲染参数(模糊 / 亮度)的落地计数**,与 [settingsRevision] 分开的第二颗计数器。
     *
     * 两件事频率差一个数量级:改一格滑块要让设置页与首页立刻看到新数值(settingsRevision,每格一次,
     * 只是重读一个几百字节的 json),但**解码 + 模糊一张 1920×1080 是几十到几百毫秒的 IO 活**,
     * 一格一次会把按住方向键变成一串全量重处理。所以壁纸管线只认这颗:设置页里模糊 / 亮度
     * 停手 300ms 后(`onWallpaperParamsChanged`)才 ++ 一次,中途那些档位一格都不进管线。
     * 见 `wallpaperSpec` 的 remember key(M7 T5 复审 Important #1)。
     */
    private var wallpaperParams by mutableStateOf(0)
    /** 内置图片选择器:null=隐藏, [PICK_WALLPAPER]=选壁纸, 其他=选该包的卡片图。
     *  X-plore 的 GET_CONTENT 不响应 D-pad(2026-09-11 真机确认),所以换壁纸/换图标
     *  改为用内置选择器,图片通过 adb push 到 files/library/ 预先放好。 */
    private var pickerTarget by mutableStateOf<String?>(null)
    /** 待机(超时淡出)。**必须住在 Activity 里**,因为唤醒发生在 dispatchKeyEvent。 */
    private var idle by mutableStateOf(false)
    /** 菜单是从齿轮按钮打开的(true)还是从遥控器三条杠键打开的(false)。
     *  关闭菜单时 HomeScreen 据此决定焦点恢复到齿轮还是原来的卡片。 */
    private var menuFromGear = true
    /**
     * 唤醒那一下按键的 downTime:整下(down/repeat/up)都要吞掉,别让它落到界面上。
     * 用 downTime 不用 keyCode:同一次按压的三种事件 downTime 相同,是唯一标识;
     * 按 keyCode 匹配时,若那次的 UP 没落到本 Activity(被系统层截走、或期间切了前台),
     * 这个值会一直留着,下次按同一个键会被**再吞一次**,症状是「刚醒来第一下没反应」。
     */
    private var wakeDownTime = -1L
    /**
     * 「恢复默认」确认框(spec §4)是否开着。只从设置页「其他→恢复默认」这一行打开,
     * 叠在设置页之上——`covered = confirmRestore || pickerTarget != null` 让设置页让路(铁律 3),
     * 对话框自己的 nonce 初始焦点循环负责自己的焦点(见 `ConfirmDialog`)。
     * **不是一次性布尔闩**(铁律 7):OK/取消/返回/三条杠键各自把它写回 false,
     * `leaveSettings()` 里还兜底清一次(HOME 键那条路径不会漏),不存在「卡在 true」的路。
     */
    private var confirmRestore by mutableStateOf(false)
    /**
     * 让 `SettingsScreen` 绕开自己重读一次 settings.json(T7 复审 Important #1)。
     * **只有 `confirmRestoreDefaults()` 在写盘落地之后才 `++`**——不能挂 `confirmRestore`
     * 或 `covered`:那两者有五条路径能把 `confirmRestore` 写回 `false`(取消 / 返回 / MENU 键 /
     * 返回键兜底 / `leaveSettings`),其中任意一条若抢在写盘完成前跑完,`confirmRestore` 早已是
     * `false`,写盘完成后再赋一次 `false` 对 Compose 是无操作、不会触发任何依赖它的效果重跑——
     * `SettingsScreen` 会永远停在恢复前的旧值上,直到退出设置页重进。也不能复用
     * `settingsRevision`:那颗计数器本页自己每次改动都会间接 `++`,若 `SettingsScreen` 挂在它
     * 上面重读,会在写失败(外置存储没挂)时把 `update()` 特地留的内存态改动立刻冲掉。
     */
    private var settingsReloadNonce by mutableStateOf(0)
    /** 首页当前聚焦的卡(HomeScreen 上报);长按确定键时据此弹菜单。 */
    private var focusedCard by mutableStateOf<CardRef?>(null)
    /** 长按菜单开着的那张卡;null = 没开。 */
    private var cardMenu by mutableStateOf<CardRef?>(null)
    /** 「修改标题」对话框(Task 5 接线)。 */
    private var renameTarget by mutableStateOf<CardRef?>(null)
    /** 编辑页该定位到哪张卡:「移动位置」进编辑页时,以及编辑页里「换卡片图」的选择器关掉、
     *  编辑页重建时(M7 终审 C1)。**(layout.json 行号, 包名)** ——
     *  列号不能带:编辑页按 layout.json 排,里面还有装不到的包占位,渲染列号对不上。
     *  只有 `leaveEdit()` 清它;编辑期间留着无害(编辑页按「已应用的目标」比对,同一个实例只应用一次)。 */
    private var editTarget by mutableStateOf<Pair<Int, String>?>(null)
    /** 「关于」浮层(齿轮菜单第四项,spec §7):版本号 + 手动检查更新,见 AboutScreen.kt。 */
    private var about by mutableStateOf(false)
    /**
     * 关于页的状态机(检查 / 下载 / 校验 / 安装)。页面关掉时 [closeAbout] 调 `reset()`
     * 取消进行中的一切,所以它的寿命可以跟 Activity 走——构造时只存引用,不碰 Context。
     */
    private val aboutFlow = AboutController(this, BuildConfig.VERSION_CODE, Update.configuredUrls())
    /**
     * 首次引导浮层(T10,spec §8)开着没有。**只在 `onCreate` 里由 settings.json 派生**
     * (`resolveOnboarding`:`onboardingDone == false` 才开),关掉只有 [endOnboarding] 一条路,
     * 而那条路先把 `onboardingDone = true` 写下去——所以不管 Activity 怎么重建,结束了的引导都不会回来,
     * 没结束的引导每次重建都会被重新判出来。它是 [overlayOpen] 的成员:首页让路、待机冻结、长按不识别。
     */
    private var onboarding by mutableStateOf(false)
    /** 引导当前第几步(1..3)。跨 `recreate()` 靠 Bundle 的 [KEY_ONB_STEP]。 */
    private var onbStep by mutableStateOf(1)
    /**
     * 待机演示(spec §3.2):设置页「待机内容」行拿着焦点时,`SettingsScreen` 经 `onDemoIdle`
     * 上报当前选中值;离开该行 → null。**只在 [settings] 开着期间才有意义**——`SettingsScreen`
     * 整页收掉之后不会再有机会把它冲回 null,所以下面用到它的每一处都读派生量
     * `if (settings) demoIdle else null`,不直接读这个字段(铁律 7:靠派生自愈,
     * 不指望某一条窄路把状态清干净)。`settings` 置真 / 置假的两条路上也顺手清一次,
     * 免得下次打开设置页时残留上一次的值在派生生效前的那一帧里闪一下。
     */
    private var demoIdle by mutableStateOf<IdleContent?>(null)
    /** 长按识别:记下那次按压的 downTime,同一次按压之后的事件(含 UP)全吞——clickable 在 UP 才触发,不会顺带启动应用。 */
    private var longPressDownTime = -1L
    private val LONG_PRESS_MS = 600L

    /**
     * **首页之上盖着整屏浮层没有**(M7 T4 分层叠加)。写成派生属性而不是各处重算:
     * 这个判据有三个消费者 —— 首页的 `previewing`、长按识别的 `homeBare`、待机效果的
     * key 与守卫 —— 少判一个成员就是一处「浮层开着时底下的首页还在抢焦点 / 还在计待机」。
     * 引导([onboarding],T10)正是靠这一处同时拿到「首页让路 / 不进待机 / 长按无效」三件事。
     * 读的全是 `mutableStateOf` 字段,在 `setContent` 里读它照样是响应式的。
     */
    private val overlayOpen: Boolean
        get() = settings || pickerTarget != null || about || onboarding

    /**
     * 装了新应用或卸载了应用后,桌面和「添加应用」列表都要能跟上。
     * 真正卸载(`PACKAGE_FULLY_REMOVED`,更新不会发它)还要把包从 layout.json / titles.json 里清掉,
     * **清完再** `revision++`——先 bump 的话编辑页按旧文件重载,僵尸卡要等下一次变动才消失。
     */
    private val packageChanges = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            val pkg = i?.data?.schemeSpecificPart
            if (i?.action == Intent.ACTION_PACKAGE_FULLY_REMOVED && pkg != null) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { pruneUninstalled(this@MainActivity, pkg) }
                    revision++
                }
                return
            }
            revision++
        }
    }

    /**
     * 应用内语言切换的接线点:Activity(重)建时读一次 settings.json 里的 `language`,
     * 用它派生出的 Context 包住 base——之后 `getString`/`stringResource` 等全部读到目标语言的资源
     * (机制见 `LocaleOverride.kt`)。[AppLocale.current] 也在此同步写入,供 [Clock] 这类
     * 直接用 `Locale` 而非走资源系统的代码读取。
     * 这里必须用 `base` 而不是 `this` 去读设置——此时 Activity 自己的 Context 链还没建好。
     */
    override fun attachBaseContext(base: android.content.Context) {
        val lang = SettingsStore.read(base).language
        AppLocale.current = localeFor(lang)
        super.attachBaseContext(base.withLanguage(lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.colorMode = ActivityInfo.COLOR_MODE_HDR
        // T10 引导三态判定(spec §8)。**必须排在最前**:它靠「layout.json 在不在」分辨老用户与新装,
        // 而首页(setContent 之后)一读布局,缺失的 layout.json 就会被写成默认值。放在注册广播之前
        // 也不是多余——包变动的回调要等 onCreate 返回才轮得到主线程,但这样读起来不必再想这一层。
        onboarding = resolveOnboarding(this)
        registerReceiver(
            packageChanges,
            android.content.IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
        )
        // 「新应用」基线:首启把 newAppsSeenAt 写成现在,之前装的都不算新(design §2)。
        // **先读再判、只在真要改时才 update**:SettingsStore.update 无论闭包返不返回同一个对象
        // 都会走一遍写盘,挂在 onCreate 上就等于每次冷启动重写一次 settings.json。
        if (SettingsStore.read(this).newAppsSeenAt == 0L) {
            SettingsStore.update(this) { it.copy(newAppsSeenAt = System.currentTimeMillis()) }
        }
        // 上一条命留下的更新包 / 半截下载(装成功后进程被替换、或下载中途被杀)。
        // 本进程登记在案的文件(另一个 MainActivity 实例正在用的)一律跳过,见 UpdateFiles。
        lifecycleScope.launch(Dispatchers.IO) { Update.sweepStale(this@MainActivity) }
        // T8:上一趟若是切语言(或恢复默认连带切语言)触发的 recreate(),把「设置页开着」
        // 和当时停在哪一格从 Bundle 种回来(spec §5)。必须在这里、`setContent` 之前赋值——
        // 两个都是普通字段,`setContent` 首次组合时读到的就是当下的值,不需要额外触发重组。
        //
        // **只信「这趟重建是不是我们自己要的」(2026-09-16 review Important #1,
        // 2026-09-17 两轮修正后的最终判据)**:两版靠 `intent.categories` 分辨 HOME/BACK
        // 的尝试都被实测推翻——`recreate()`、以及进程死后由外部请求重建,新实例的
        // `intent` 都是**创建这个 Activity 实例时的原始 intent**(真机上这个 Activity
        // 几乎总是被 HOME 启动的),`onCreate` 阶段根本看不出这次具体是被 HOME 还是 BACK
        // 带回来的(详见 shouldRestoreSettingsFromBundle 的 KDoc,含两轮复现记录)。
        // 现在只种「我们自己在 applyLanguage 里主动喊的 recreate()」这一种情况
        // (`selfTriggeredRecreate`,调用 `recreate()` 前置真、随 Bundle 带过来);
        // 其它任何导致重建的原因——包括进程被系统杀掉——都不种,统一落在桌面,
        // 不区分后续是 HOME 还是 BACK。
        // **引导开着时也不种**(终审 I2):上面 resolveOnboarding 刚按盘判出要引导的话,
        // 设置页再种回来就是两层整屏浮层同时在场、两套焦点账本互相抢(见该函数 KDoc)。
        val bundleSaysOpen = savedInstanceState?.getBoolean(KEY_SETTINGS_OPEN) == true
        val wasSelfTriggered = savedInstanceState?.getBoolean(KEY_SELF_RECREATE) == true
        if (savedInstanceState != null &&
            shouldRestoreSettingsFromBundle(bundleSaysOpen, wasSelfTriggered, onboardingOpen = onboarding)
        ) {
            settings = true
            settingsPos = SettingsPos(
                pane = savedInstanceState.getInt(KEY_SETTINGS_PANE),
                group = savedInstanceState.getInt(KEY_SETTINGS_GROUP),
                row = savedInstanceState.getInt(KEY_SETTINGS_ROW),
            )
        }
        // T10:引导**开没开不从 Bundle 读**(上面 resolveOnboarding 已经按 settings.json 判过),Bundle
        // 只回答「开着的话停在第几步」。**不像设置页那样卡 selfTriggeredRecreate**:设置页要卡,是因为
        // HOME 应该关掉它、而 onCreate 分不出这趟重建之后跟着来的是 HOME 还是 BACK(T8);引导不一样——
        // HOME 不关它(onNewIntent 不碰它),它在不在只由 settings.json 决定,任何一种重建(切语言、
        // 进程被杀后回来、清单没声明的配置变化)之后它都照样在,唯一合理的落点就是用户离开时那一步。
        // 也不会把结束了的引导种回来:结束的每条路都先写 onboardingDone = true,那时 onboarding 已是 false。
        if (onboarding && savedInstanceState != null) {
            onbStep = restoredOnboardingStep(savedInstanceState.getInt(KEY_ONB_STEP))
        }
        installBackHandler()
        setContent {
            // 菜单项列表不必每次重组都新建,否则整棵树都不可跳过
            val menu = remember { menuItems() }
            // 设置页里那几条「只有 Activity 做得了」的动作(spec §2.2 的动作行 + 切语言)。
            // 四个子界面复用现成的入口函数 —— 它们只置 `pickerTarget`,**不碰 `settings`**,
            // 于是选择器叠在设置页之上(`covered = pickerTarget != null`),关掉后焦点由设置页接回同一行。
            val settingsActions = remember {
                SettingsActions(
                    pickWallpaper = { pickWallpaper() },
                    openImport = { openImport() },
                    setDefaultHome = { openHomeSettings() },
                    // 只开确认框(spec §4),真正的写盘在用户按下「恢复」之后 —— 见 confirmRestoreDefaults()。
                    restoreDefaults = { confirmRestore = true },
                    // T8:接回真正的 applyLanguage()——写盘,且只在 Locale 真的变了才 recreate()。
                    // 重建后的位置由 onSaveInstanceState/onCreate 经 Bundle 还原(见 settingsPos 的
                    // KDoc),SettingsScreen 拿 initialPos 当 remember 的种子把焦点落回同一行。
                    applyLanguage = ::applyLanguage,
                )
            }
            // 设置页关闭时 leaveSettings() 会让 revision++,壁纸选图 / 轮播 / 滑块预览走的是
            // 专用的 settingsRevision(见其字段 KDoc,只重读 settings、不重建首页行)——
            // 两颗计数器都能让这里重读 settings.json,首页拿到的就是最新设置。注意:
            // 这里不能显式写 Settings 类型名,本文件已经 `import android.provider.Settings`,
            // 裸写 Settings 会撞上那个系统类;靠类型推断绕开,只取用到的字段(showDate)。
            val homeSettings = remember(revision, settingsRevision) { SettingsStore.read(this@MainActivity) }
            // 待机演示派生量(见 [demoIdle] 的 KDoc):`settings` 一关就自动变 null,
            // 不依赖 SettingsScreen 在它自己最后一帧里主动上报 null(铁律 7)。
            val activeDemoIdle = if (settings) demoIdle else null
            // 主题色:选中预设的 accent + highlight,经下面的 LocalThemeColors 供给**每个界面**(2026-09-16 全面接线)。
            // followWallpaperColor 打开时,accent 改从当前壁纸主色提取、highlight 由它混白推得
            // (与非金预设同一算法);解不出色或没壁纸就回落到预设。壁纸解码放 IO 线程,
            // key 带上 followWallpaperColor、wallpaperFile 与 revision:换壁纸(handlePick 只
            // settingsRevision++,不再 recreate)会让 homeSettings.wallpaperFile 变、这里跟着重跑;
            // 开关跟随、回到设置页同样触发。preset 路径是纯内存查表,直接同步解析。
            val presetColors = remember(homeSettings.themePresetId) {
                ThemePresets.byId(homeSettings.themePresetId).colors()
            }
            val wallpaperColors by produceState<ThemeColors?>(
                null, homeSettings.followWallpaperColor, homeSettings.wallpaperFile, revision,
            ) {
                value = if (!homeSettings.followWallpaperColor) null
                else withContext(Dispatchers.IO) {
                    wallpaperThemeColors(this@MainActivity, homeSettings.wallpaperFile)
                }
            }
            val themeColors =
                if (homeSettings.followWallpaperColor) wallpaperColors ?: presetColors
                else presetColors
            // 壁纸渲染输入:文件名 + 模糊 + 亮度,**不带主题色**(「主题化壁纸」2026-09-16 整个删掉,
            // 壁纸不再染色)。所以换预设、开关跟随、壁纸取色落地都不会让 spec 变,壁纸不会被无谓地重处理。
            //
            // **key 里故意没有模糊 / 亮度**(M7 T5 复审 Important #1)。写成 `remember(homeSettings)`
            // 时,设置页每动一格滑块都 `settingsRevision++` → homeSettings 换新 → spec 换新 →
            // `Wallpaper` 的 produceState 以新 key 重启 → 解码 + 模糊整跑一趟:**按住方向键就是每格一次全量重处理**,
            // 而 300ms 防抖那一下 300ms 后才到、那时缓存早已被逐格填满,防抖形同虚设。
            // 现在 spec 只在三种情况下重算:①`revision`(重扫);②`wallpaperFile` 变(选图 / 轮播);
            // ③`wallpaperParams`——**只有防抖后的那一下**才 ++。滑到中途的那些档位一格都不会进管线。
            // 重算时读的是**当时最新的** homeSettings(逐格的 settingsRevision 已经把它更到了终值),
            // 所以防抖落地时拿到的就是用户松手时的那个值。
            val wallpaperSpec = remember(revision, wallpaperParams, homeSettings.wallpaperFile) {
                wallpaperSpecOf(homeSettings)
            }
            val touched = lastInput
            // **编辑界面和菜单开着时不进入待机。**淡出只做在首页那一层,而吞掉唤醒键是
            // Activity 级的 —— 两头不占的结果是:编辑界面画面全亮(看着醒着),
            // 第一下按键却被当唤醒吃掉,症状就是「按了没反应」。
            // 长时间停在这两个界面由电视自己的系统屏保接管(实测存在 DreamActivity)。
            // **整屏浮层开着时不进入待机**(M7 T4:原来只挡了导入页)。选择器现在叠在常驻首页之上,
            // 底下那层照旧在计时;不挡的话在「换壁纸」里挑图挑够三分钟,首页会在选择器的半透明
            // 蒙版底下淡出、屏保渐入,而下一个按键还要被 dispatchKeyEvent 当唤醒吞掉。
            // 与 menuOpen 同一处理:既是 key 也是守卫(铁律 6)。
            val overlay = overlayOpen
            // 长按卡片菜单与「修改标题」对话框同理(终审 Important #3):输入法显示着时每个按键都被它先吃掉,
            // 根本到不了 dispatchKeyEvent,lastInput 在打字期间不会刷新;不让路的话三分钟后卡片淡出、
            // 屏保从蒙版后面渐入,下一个按键还被当唤醒吞掉。与 menuOpen 完全同一处理:既是 key 也是守卫(铁律 6)。
            val homeOverlay = cardMenu != null || renameTarget != null
            // 待机时长/内容改由设置页驱动(Task 3):idleAfterMs 既是 key 也是守卫(铁律 6)——
            // 用户把它从「关」改成别的值(或反过来)时,这条 effect 必须以新 key 重启,
            // 否则「关」之后再打开待机,要等到下一次别的 key 变化才会生效。
            val idleAfterMs = homeSettings.idleAfterMs
            LaunchedEffect(touched, editing, menuOpen, overlay, homeOverlay, idleAfterMs) {
                idle = false
                if (editing || menuOpen || overlay || homeOverlay) return@LaunchedEffect
                // 0 = 关,永不待机。
                if (idleAfterMs == 0L) return@LaunchedEffect
                delay(idleAfterMs)
                idle = true
            }
            // 壁纸轮播。守卫读的两个量就是 key(铁律 6):rotate() 写盘后 settingsRevision++ 重读 settings,
            // rotatedAt 变 → 本 effect 以新 key 重启、再等一个间隔;重启 app 后按剩余时间续等。
            val rotateMs = homeSettings.wallpaperRotateMs
            val rotatedAt = homeSettings.wallpaperRotatedAt
            LaunchedEffect(rotateMs, rotatedAt, settings) {
                // 设置页开着时不轮播:它持有整份 Settings 快照、每次改动整对象回写,后台轮播写进去的
                // wallpaperFile/rotatedAt 会被下一次按键覆盖(壁纸来回翻)。leaveSettings() 会 revision++,
                // 重读后 settings=false → 本 effect 重启,过期的那一拍在退出时补上。守卫读的量同时是 key(铁律 6)。
                if (rotateMs == 0L || settings) return@LaunchedEffect
                delay(rotationDelayMs(rotatedAt, rotateMs, System.currentTimeMillis()))
                val wrote = withContext(Dispatchers.IO) { Wallpapers.rotate(this@MainActivity) }
                if (wrote) settingsRevision++
            }
            // 不用 key(revision) 强制重建:那会连壁纸和焦点一起推倒,
            // 后台应用自动更新时屏幕会黑一下、焦点被打回第一张卡。
            // revision 只喂给读数据的 produceState,新数据到达前旧画面原样留着。
            // 壁纸与黑底常驻在这一层:进出编辑界面只换上面那一层,
            // 壁纸不会被重建,也就不会每次退出编辑都重新解码 + 黑闪一下。
            // 主题色只此一条线:这里提供一次,下面每个界面都读 LocalThemeColors.current(见 ThemePresets.kt)。
            CompositionLocalProvider(LocalThemeColors provides themeColors) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black)
            ) {
            // prepare() 在首启/升级那一趟会往 settings.json 写 wallpaperFile,而 homeSettings
            // 是在此之前读的;不重读的话,从 M2 升上来、开着「跟随壁纸主色」的用户整个首次会话
            // 都看不到壁纸主色(见 Wallpapers.prepare 的 KDoc)。
            Wallpaper(this@MainActivity, wallpaperSpec, onSettingsChanged = { settingsRevision++ })
            // NO_FADE(Task 3):干脆不组合 Screensaver——M5 之前待机不淡出时就是「什么都不发生」
            // (spec §6),屏保图片一张都不该解码,不只是不显示。
            if (homeSettings.idleContent != IdleContent.NO_FADE) {
                Screensaver(this@MainActivity, idle)
            }
            // BLACK(Task 3):在屏保之上叠一层纯黑,随 idle 淡入淡出;配合 HomeScreen 里
            // 时钟自己的 clockAlpha 一起淡出,才是「整屏全黑」而不是黑底衬着屏保/时钟。
            // Box 只要选了 BLACK 就常驻组合(不额外拿 idle 当 if 条件),这样 animateFloatAsState
            // 才能从上一次的值平滑动画过去;若只在 idle 时才组合它,首帧会直接从目标值起跳,
            // 表现为黑屏瞬间弹出而不是 1200ms 淡入。
            // **待机演示(spec §3.2)也要能让这层变黑**:同一个 Box 常驻组合的条件因此加上
            // `activeDemoIdle == BLACK`——只判 `homeSettings.idleContent`(真实设置)的话,
            // 设置页把「待机内容」演示切到「全黑」时,若真实设置并非全黑,这层压根没被组合,
            // 演示就只剩卡片淡出、看不到「整屏全黑」这一半。
            if (homeSettings.idleContent == IdleContent.BLACK || activeDemoIdle == IdleContent.BLACK) {
                val demoActive = activeDemoIdle != null
                val blackIdle = idle || demoActive
                val blackContent = activeDemoIdle ?: homeSettings.idleContent
                val blackAlpha by animateFloatAsState(
                    targetValue = if (blackIdle && blackContent == IdleContent.BLACK) 1f else 0f,
                    animationSpec = tween(if (blackIdle) 1200 else 400),
                    label = "blackAlpha",
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = blackAlpha))
                )
            }
            // **分层叠加**(M7 T4,plan §Architecture)。选择器 / 导入页 / 默认桌面卡不再「替换」
            // 首页,而是**叠在常驻的首页之上**:首页留在组合里 = `tgtRow`/`tgtIdx` 那份焦点记忆
            // 天然保留,不必再由 MainActivity 用种子(`homeInitialTarget`,已删)种回去;
            // 而且上面那层的半透明蒙版底下就是真正的首页,M7 的「实时预览」靠的正是这一点。
            // 代价:底下那棵树继续被组合,所以它必须彻底让路 —— `previewing = overlayOpen`
            // 让首页不可聚焦、不收按键、冻结焦点记忆(见 HomeScreen.previewing 的 KDoc)。
            //
            // 唯一的例外:**编辑页仍然独占那一层** —— 它是首页的编辑态(同一批卡片的另一种摆法),
            // 不是盖在首页上的浮层。设置页 M7 T5 起也是叠加,见下面。
            val pt = pickerTarget
            if (editing) {
                // **编辑页开着时,选择器替换它,不叠加**(M7 终审 C1)。EditScreen 没有 `covered`
                // 这个让路开关:叠在它上面的话,它的看门狗与重定位会跟选择器抢焦点。所以回到 M7 之前
                // 的替换语义——选择器开着时 EditScreen 整个不在组合里,关掉后重建,由 `editTarget`
                // (layout 行号, 包名)这颗种子把焦点送回刚才那张卡(铁律 5:目标在打开选择器那一刻
                // 就定下,重建期间 Compose 抢先给出的焦点事件改写不了它;见 onPickIcon)。
                // 这一支曾被 T4 的伪代码整个挪进了下面的 else:编辑页里按「换卡片图」画面毫无变化,
                // MENU 被吞,按返回退出编辑页后选择器才出现在首页上。
                if (pt == null) {
                    EditScreen(
                        // 选择器真的打开了(存储就绪)才记种子:打不开时编辑页留在原地,
                        // 它自己在调用前安排的重定位已经会把焦点放回这张卡。
                        onPickIcon = { row, pkg -> if (pickIcon(pkg)) editTarget = row to pkg },
                        onExit = ::leaveEdit,
                        focusNonce = focusNonce,
                        revision = revision,
                        cardsPerRow = homeSettings.cardsPerRow,
                        showTitles = homeSettings.showTitles,
                        initialTarget = editTarget,
                    )
                } else {
                    // 编辑页里能打开的只有「换卡片图」(pt = 包名);其余几种选择器只能从首页 / 设置页
                    // 打开,编辑态下不会出现。仍然整段复用 PickerLayer,不在这里另写一份只认包名的分支。
                    PickerLayer(pt)
                }
            } else {
                HomeScreen(
                    previewing = overlayOpen,
                    idle = idle,
                    idleContent = homeSettings.idleContent,
                    demoIdle = activeDemoIdle,
                    menuItems = menu,
                    menuOpen = menuOpen,
                    onMenuOpenChange = { if (it) { menuFromGear = true; menuOpen = true } else closeMenu() },
                    focusNonce = focusNonce,
                    revision = revision,
                    menuFromGear = menuFromGear,
                    showDate = homeSettings.showDate,
                    cardsPerRow = homeSettings.cardsPerRow,
                    showTitles = homeSettings.showTitles,
                    showInputRow = homeSettings.showInputRow,
                    themedCards = homeSettings.themedCards,
                    newAppsSeenAt = homeSettings.newAppsSeenAt,
                    onFocusedCard = { focusedCard = it },
                    cardMenu = cardMenu,
                    cardMenuItems = remember(cardMenu) { cardMenu?.let { cardMenuItems(it) } ?: emptyList() },
                    onCardMenuDismiss = ::closeCardMenu,
                    renameTarget = renameTarget,
                    onRenameSave = ::onRenameSave,
                    onRenameCancel = { renameTarget = null; focusNonce++ },
                )
                // **设置页叠在首页之上**(M7 T5,spec §3.1):首页留在底下继续组合,
                // 半透明渐变遮罩底下看到的就是真正的首页 —— 改卡片大小 / 标题 / 主题色当场可见。
                // 每次改动 `settingsRevision++`(不防抖):首页据此重读 settings.json,
                // 而模糊 / 亮度另走设置页里 300 ms 防抖的那条,壁纸不必每按一下就重处理一遍。
                // 写在选择器层(PickerLayer)**之前**:从设置页里打开的换壁纸 / 导入图片 / 默认桌面卡要盖在它上面,
                // 同时设置页收到 `covered` 让路(焦点归那一层管,铁律 3)。
                if (settings) {
                    SettingsScreen(
                        onExit = ::leaveSettings,
                        actions = settingsActions,
                        focusNonce = focusNonce,
                        // 确认框叠在设置页上时同样让路(铁律 3)——不加的话它自己的初始焦点循环
                        // 会跟设置页的看门狗抢同一帧的焦点请求。
                        // `onboarding` 是兜底(终审 I2):onCreate 已保证引导在场时不把设置页种回来,
                        // 万一两者同时为真,画在最上层的引导负责焦点,这一页让路而不是跟它抢。
                        covered = confirmRestore || pt != null || onboarding,
                        // 每次改动:只重读 settings.json,首页当场按新值重组(布局 / 主题 / 时钟都靠它)。
                        onSettingsChanged = { settingsRevision++ },
                        // 停手 300ms 之后的那一下:**壁纸管线的唯一入口**,见 wallpaperParams 的 KDoc。
                        onWallpaperParamsChanged = { wallpaperParams++ },
                        // 待机演示(spec §3.2):焦点停在「待机内容」行时上报选中值,写回 [demoIdle]。
                        // 派生量 activeDemoIdle 已经把「settings 关了就是 null」这半覆盖了,
                        // 这里只管「本页开着期间」的实时上报。
                        onDemoIdle = { demoIdle = it },
                        initialPos = settingsPos,
                        onPosChanged = { settingsPos = it },
                        // 恢复默认写盘落地之后才 ++ 一次(见其 KDoc,T7 复审 Important #1)——
                        // 与 confirmRestore/covered 解耦,不受「dismiss 抢在写盘完成前跑完」影响。
                        reloadNonce = settingsReloadNonce,
                    )
                }
                // 「恢复默认」确认框(spec §4)。叠在设置页之上,与选择器同属「设置页的子界面」——
                // 画在 PickerLayer 之前只是顺序习惯,两者不会同时出现(它只能从设置页那一行打开,
                // 打开的瞬间 pickerTarget 必为 null),谁在前不影响层叠结果。
                if (confirmRestore) {
                    ConfirmDialog(
                        title = stringResource(R.string.restore_title),
                        body = stringResource(R.string.restore_body),
                        okLabel = stringResource(R.string.restore_ok),
                        cancelLabel = stringResource(R.string.dialog_cancel),
                        nonce = focusNonce,
                        onOk = ::confirmRestoreDefaults,
                        onCancel = { confirmRestore = false },
                    )
                }
                // 叠在首页 / 设置页之上的那一层(编辑态下同一个 PickerLayer 改为替换编辑页,见上)。
                if (pt != null) PickerLayer(pt)
                // 齿轮菜单第四项「关于」(spec §1、§7)。画在最后 = 叠在设置页 / 选择器之上,
                // 与它们同属 [overlayOpen] 的整屏浮层家族,首页早已因 previewing 让路;
                // 焦点由页面自己的请求循环负责(铁律 3)。状态机在 aboutFlow 里,这里只接线。
                if (about) {
                    AboutScreen(
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                        state = aboutFlow.state,
                        onCheck = aboutFlow::check,
                        onDownload = aboutFlow::downloadAndInstall,
                        onInstall = aboutFlow::installReady,
                        onBack = ::onAboutBack,
                        nonce = focusNonce,
                    )
                }
            }
            // 首次引导(T10,spec §8)。画在最上层,而且**放在 editing 的 if/else 之外**:引导开着时
            // 本来就进不了编辑页(菜单、长按都被挡),但万一两者同时为真,也绝不能出现「引导状态是开的、
            // 画面上却没有它」——那会是一个吞掉全部焦点的黑洞。其余整屏浮层在引导期间都打不开。
            if (onboarding) {
                Onboarding(
                    step = onbStep,
                    language = homeSettings.language,
                    revision = revision,
                    nonce = focusNonce,
                    onLanguage = ::chooseOnboardingLanguage,
                    onStep = { onbStep = it },
                    onFill = { fillFromOnboarding(fill = true) },
                    onSkipFill = { fillFromOnboarding(fill = false) },
                    // 先结束(写 onboardingDone = true)再跳系统页:从系统页回来落在普通首页,
                    // 即使进程在系统页里被杀,引导也不会再出现。
                    onOpenHomeSettings = { endOnboarding(); switchHome() },
                    onFinish = ::endOnboarding,
                    onBack = ::stepBackInOnboarding,
                )
            }
            }
            }
        }
    }

    /**
     * 选择器那一层:[pickerTarget] 的每一种取值对应一个整屏子界面。用 `when` 而不是 if/else 链——
     * 分支是同一个量的取值,新增一种取值时不会悄悄落进别的分支。
     *
     * **两个调用点**(M7 终审 C1):首页 / 设置页开着时它**叠在上面**(底下那层靠 `previewing` /
     * `covered` 让路);编辑页开着时它**替换**编辑页(EditScreen 没有让路开关,见 setContent 里那一支)。
     * 每个子界面自己负责自己的焦点(nonce 初始焦点循环,铁律 3);关掉时 `focusNonce++`,
     * 让底下那一层——或重建出来的编辑页——把焦点接回离开前那一格。
     */
    @Composable
    private fun PickerLayer(target: String) {
        when (target) {
            PICK_WALLPAPER -> WallpaperPicker(
                directory = Paths.wallpaperLibrary(this),
                title = stringResource(R.string.picker_wallpaper_title),
                nonce = focusNonce,
                onSelect = { file -> handlePick(file) },
                onDismiss = { pickerTarget = null; focusNonce++ },
            )
            VIEW_SCREENSAVER_POOL -> ScreensaverPoolViewer(
                directory = Paths.screensaverLibrary(this),
                nonce = focusNonce,
                onDismiss = { pickerTarget = null; focusNonce++ },
            )
            VIEW_IMPORT -> ImportScreen(
                onExit = { pickerTarget = null; focusNonce++ },
                focusNonce = focusNonce,
            )
            // 「当前默认桌面」的解析与首次引导第 3 步共用(rememberCurrentHome,T10 抽出)。
            VIEW_HOME_SETTINGS -> HomeSettingsCard(
                home = rememberCurrentHome(revision),
                onOpenSystem = { switchHome() },
                onDismiss = { pickerTarget = null; focusNonce++ },
                nonce = focusNonce,
            )
            // 其余取值都是包名 = 换这张卡的图。
            else -> IconPicker(
                directory = Paths.cardLibrary(this),
                originalIcon = remember(target) { Apps.originalIcon(this, target) },
                nonce = focusNonce,
                onSelect = { file -> handlePick(file) },
                onRestoreOriginal = { restoreOriginalIcon(target) },
                onDismiss = { pickerTarget = null; focusNonce++ },
            )
        }
    }

    /**
     * 与 `onCreate` 的还原半成对(T8,spec §5)。只存「设置页开没开」与当时停在哪一格,
     * 外加 [selfTriggeredRecreate] 这个一次性记号(见其 KDoc)——其余临时态(`confirmRestore`、
     * `pickerTarget` 之类)recreate 后归零才是对的:它们各自只服务自己那一次交互
     * (确认框、选择器),没有一条规则说它们要跨越一次 Activity 重建续命,白存它们只会在
     * 恢复默认的确认框场景下凭空变出一层不该在的浮层。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SETTINGS_OPEN, settings)
        outState.putBoolean(KEY_SELF_RECREATE, selfTriggeredRecreate)
        settingsPos?.let { pos ->
            outState.putInt(KEY_SETTINGS_PANE, pos.pane)
            outState.putInt(KEY_SETTINGS_GROUP, pos.group)
            outState.putInt(KEY_SETTINGS_ROW, pos.row)
        }
        // T10:引导的步骤号。第 1 步选语言时 chooseOnboardingLanguage 先把它改成 2 再 recreate(),
        // 这里写下的就是 2——重建后直接落在第 2 步、已是新语言。
        if (onboarding) outState.putInt(KEY_ONB_STEP, onbStep)
    }

    /**
     * 任何按键都算「有人在用」,唤醒待机。
     *
     * 焦点丢失的兜底**不放在这里**:曾试过「按键时若无焦点就补请求」,但
     * `decorView.findFocus()` 查的是 View 焦点(整棵 Compose 树住在一个可聚焦的 View 里,
     * 恒非 null),是死代码;换成 Compose 侧上报后又因为根 Box 的 focusGroup 吞焦点而失效。
     * 现在改为在**焦点确实会丢的那几个时刻**显式补请求:菜单关闭、退出编辑、从别的应用返回。
     *
     * 待机的唤醒也在这里:**不能**靠给卡片加 `canFocus = !idle` 来防止「醒来第一下直接启动
     * 应用」——Compose 的 FocusTargetNode 在自己是 Active 且 canFocus 转 false 时会调
     * `clearFocus(force=true)` 清掉整棵树的焦点,而 DPAD_CENTER 不参与框架的焦点恢复,
     * 于是醒来后按确定永远没反应。改成在这里吞掉唤醒的那一整下按键:焦点全程没动过,
     * 用户回到的正是他离开时那张卡。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInput = System.currentTimeMillis()
        if (idle) {
            idle = false
            wakeDownTime = event.downTime
            return true
        }
        if (wakeDownTime != -1L && event.downTime == wakeDownTime) {
            if (event.action == KeyEvent.ACTION_UP) wakeDownTime = -1L
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
            && event.keyCode == KeyEvent.KEYCODE_MENU
        ) {
            // 首次引导期间 MENU 键无效(spec §8):不出声、不关引导、不在它底下叠出齿轮菜单。
            // 排在最前:下面每一支都会改某个浮层的状态,引导开着时它们一个都不该发生。
            if (onboarding) return true
            // 选择器开着(叠在首页 / 设置页上,或替换了编辑页)时 MENU 什么都不做:排在 editing /
            // settings 之前,否则会把底下那页收掉、选择器留在首页上(终审 C1)。关掉选择器之后
            // pickerTarget 回到 null,MENU 在编辑页上照常 = 退出编辑。
            if (pickerTarget != null) return true
            // 「恢复默认」确认框开着时同理:三条杠键只关它自己,不连带关掉整个设置页——
            // 放在 `settings` 判断之前,否则会摸到下面那一支把整页一起收掉。
            if (confirmRestore) { confirmRestore = false; return true }
            if (editing) { leaveEdit(); return true }
            if (settings) { leaveSettings(); return true }
            // 「关于」页开着时同理:三条杠键只负责关它(下载中也一并取消),不能在它底下叠出齿轮菜单——
            // 不判的话会摸到下面 `menuOpen` 那一支,在关于页蒙版后面悄悄开出一层齿轮菜单。
            if (about) { closeAbout(); return true }
            window.decorView.playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN)
            // 「修改标题」对话框开着时同理:三条杠键只负责取消它,不能在它底下叠出齿轮菜单——
            // 不判的话 menuOpen 会被悄悄置 true,对话框仍在最上层挡着,直到它关掉才会露出
            // 一个其实早就"开着"的齿轮菜单(T5 review Important #2)。
            if (renameTarget != null) { renameTarget = null; focusNonce++; return true }
            // 长按菜单开着时,三条杠键只负责**收掉它**,绝不再叠一层齿轮菜单:
            // 两层 GearMenu 会同时在场,上面那层拿走焦点、下面那层的蒙版仍然盖着,
            // 而 cardMenu 永远不会被清 —— 看上去是「菜单花屏且怎么按都出不去」。
            // 放在音效之后、toggle 之前:按键照样有声音反馈,只是改成「关掉当前这层」。
            if (cardMenu != null) { closeCardMenu(); return true }
            if (menuOpen) closeMenu() else { menuFromGear = false; menuOpen = true }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (longPressDownTime != -1L && event.downTime == longPressDownTime) {
                if (event.action == KeyEvent.ACTION_UP) longPressDownTime = -1L
                return true
            }
            // 长按 = 同一次按压持续满 LONG_PRESS_MS 后的第一个重复事件。只在首页无任何浮层时识别。
            // 不再用 `repeatCount == 1`(≈0.4 s):首次重复延迟与重复频率都由固件定,按时长判才跨设备一致;
            // Gordon 2026-09-16 A95L 真机试过 0.4 s 后定为 0.6 s。未满时长的重复事件走下面的「照吞」分支,
            // 松手仍是一次普通点击。
            // 「首页光着」= 上面什么都没盖着。整屏浮层一律走 [overlayOpen](M7 T4:原来只列了
            // settings / pickerTarget,about 与 onboarding 接线后会漏掉),内嵌的两层单列。
            val homeBare = !editing && !overlayOpen && !menuOpen && cardMenu == null && renameTarget == null
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && homeBare
                && event.eventTime - event.downTime >= LONG_PRESS_MS
            ) {
                val ref = focusedCard
                if (ref != null) {
                    // **只要站在卡片上,长按就整下吞掉**(design §1:输入源卡「按压照常吞掉、不启动」)。
                    // 没有这一半的话,输入源卡上长按会走到下面「重复事件照吞」、可 UP 仍然落到界面上,
                    // 而 clickable 正是在 UP 触发 —— 用户长按只想看看有没有菜单,结果切了信号源。
                    longPressDownTime = event.downTime
                    window.decorView.playSoundEffect(SoundEffectConstants.CLICK)
                    // 有菜单的种类才弹菜单;输入源行的菜单归 M4b,现在只是「什么都不发生」。
                    if (cardMenuActions(ref.kind).isNotEmpty()) cardMenu = ref
                    return true
                }
            }
        }
        // 长按确认键不应重复点击:电视 UI 里没有连按同一按钮的场景,
        // 重复事件只会让点击音一直响、可能反复启动同一个应用。
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            return true
        }
        val result = super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            val sfx = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> SoundEffectConstants.NAVIGATION_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> SoundEffectConstants.NAVIGATION_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> SoundEffectConstants.NAVIGATION_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> SoundEffectConstants.NAVIGATION_RIGHT
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                    SoundEffectConstants.CLICK
                else -> -1
            }
            if (sfx >= 0) window.decorView.playSoundEffect(sfx)
        }
        return result
    }

    /**
     * HOME 键的语义是「回到桌面初始状态」,所以要把编辑界面和菜单都收掉。
     * 导入页额外收一次:它拿着一个无密码的局域网 HTTP 服务,按 HOME 离开时必须一并关掉
     * (其余选择器不持有任何资源,不用管)。
     * **首次引导故意不收**(T10):spec §8 只给了「跳过 / 走完 / 第 1 步返回」三个出口;
     * 引导期间其余浮层都打不开,下面这几个收尾调用全是空操作,也不碰 focusNonce。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        leaveEdit()
        leaveSettings()
        closeMenu()
        closeCardMenu()
        closeAbout()
        renameTarget = null
        if (pickerTarget == VIEW_IMPORT) { pickerTarget = null; focusNonce++ }
    }

    /**
     * 关菜单**只有这一条路**。菜单项随节点销毁时焦点会一并消失,所以必须补请求;
     * 曾经 Compose 侧的回调补了、返回键这条路没补,按返回关掉菜单后整棵树没有焦点。
     */
    private fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        focusNonce++
    }

    private fun leaveEdit() {
        if (editing) { editing = false; revision++; editTarget = null }
    }

    /**
     * 关「关于」页**只有这一条路**(与 closeMenu 同构):返回键([onAboutBack])、MENU 键分支、
     * HOME(onNewIntent)都调它。`aboutFlow.reset()` 取消进行中的检查 / 下载(半截文件随之删掉)
     * 以及「等用户按安装」的那一份(文件删掉,之后绝不会自己弹出安装器),
     * 下次打开从「检查更新」重新开始;`focusNonce++` 让常驻的首页按它在 previewing 期间冻结的
     * 目标把焦点还原(与其它整屏浮层的 onDismiss 同理;T9 模拟器实测:三条杠键打开的关于页,
     * 返回 / 三条杠 / HOME 关掉后焦点都回到原来那张卡)。
     */
    private fun closeAbout() {
        if (!about) return
        aboutFlow.reset()
        about = false
        focusNonce++
    }

    /**
     * 关于页的返回键(页面自己的 BackHandler 与 [installBackHandler] 的兜底共用这一处):
     * 下载 / 校验进行中 → 只取消这次下载、页面留着(spec §7:下载中 BACK = 取消下载并删文件);
     * 其它状态 → 关页。
     */
    private fun onAboutBack() {
        if (!aboutFlow.cancelIfBusy()) closeAbout()
    }

    /**
     * 关设置页**只有这一条路**。关掉后 focusNonce++ 让首页把焦点还原到进入设置前那一格
     * (与各选择器 onDismiss 同理;首页常驻,`previewing` 期间目标是冻着的)。
     *
     * **不再 `revision++`**(M7 T5):设置页每改一下就 `settingsRevision++`,首页早就读到新值了;
     * `revision` 那颗计数器会连 layout.json 与全部卡片图一起重读一遍(冷启动级别的重活),
     * 而这里没有任何东西需要重扫 —— 唯一会改变行数据的「输入源行」开关,本身就是首页
     * 那个 `produceState` 的 key,开关一变它自己就重建了。
     */
    private fun leaveSettings() {
        // 位置记忆只为 `recreate()`(切语言)那一趟服务 —— 那时页面**还开着**,重建后必须回到同一行。
        // 用户自己按返回关掉页面则是另一回事:下次再进应该落在左栏第一组(spec §2.1「默认布局」),
        // 所以这条路上把种子清掉。两条路各自清楚,种子不会变成一份「永远过期不掉」的状态(铁律 7)。
        if (!settings) return
        settings = false
        settingsPos = null
        // 兜底清掉确认框(铁律 7):HOME 键(onNewIntent)只调这一个函数就把整页收掉,
        // 若确认框还开着,不清的话它会跟着 `settings` 一起消失、下次进设置页却莫名其妙
        // 蹦出上次那个对话框——三处会调到这里的路(MENU 键 / 返回键兜底 / HOME)因此全覆盖。
        confirmRestore = false
        // 待机演示的显式收口(见 [demoIdle] 的 KDoc):`activeDemoIdle` 的派生已经保证它
        // 关了就读不到,这里顺手把源头也清掉,免得下次打开设置页时,在 SettingsScreen
        // 重新报告真实值之前的那一帧,还读得到上一次会话残留的旧值。
        demoIdle = null
        focusNonce++
        // **防抖的补课**:滑块的 300ms 防抖住在设置页的效果里,「动一格就立刻按返回」会把那次
        // 通知连同协程一起取消掉,壁纸就会停在旧参数上,直到下次换图/重扫才追上。这里补一次。
        // 参数没变时也没有代价:`WallpaperSpec` 是 data class,重算出来的新实例与旧的相等,
        // `Wallpaper` 的 produceState 按 key 的 equals 比对,不会重启、不会重新解码。
        wallpaperParams++
    }

    /**
     * 语言切换入口。写盘后只在 Locale 真的变了才 `recreate()`——比如从 `zh-CN` 切到
     * `zh-TW`(都不是 `localeFor` 所在的等价类)才需要重建,避免同语言重复选择也整个
     * 重建一次(黑闪 + 焦点打回第一张卡)。`recreate()` 会重新触发 [attachBaseContext],
     * 新语言由那里接管。
     *
     * 重建前的位置不必在这里现抓:设置页每次账本变动都会经 `onPosChanged` 把当下这一格
     * 写进 [settingsPos](调用这个函数时用户一定已经站在语言行上,该字段早已是最新值)——
     * `onSaveInstanceState`(T8)把它连同 `settings` 一起写进 Bundle,新 Activity 的
     * `onCreate` 收到后原样种回来,`SettingsScreen` 拿 `initialPos` 当 `remember` 的种子
     * 把焦点落回同一行(rule 5:目标与当前分开;rule 7:种子只喂一次,不留闩)。
     * 恢复默认(`confirmRestoreDefaults`)把语言改回 `system` 时走的是同一个函数、同一条路。
     *
     * `selfTriggeredRecreate = true` 必须在 `recreate()` **之前**这一行做(2026-09-17
     * review 定稿):新实例的 `onCreate` 只有靠这个记号才能相信 Bundle 里的
     * `settingsOpen`——`intent` 本身不可信(`recreate()` 沿用创建实例时的旧 intent,
     * 真机上几乎总是 HOME,`onCreate` 阶段分不出这趟重建是不是语言切换),
     * 漏了这一步会把这次合法的语言切换重开也当成「外部原因导致的重建」而拦掉
     * (详见 SettingsRestorePolicy.kt 的 KDoc,含两轮复现记录)。
     */
    private fun applyLanguage(language: String) {
        SettingsStore.update(this) { it.copy(language = language) }
        if (localeFor(language) != AppLocale.current) {
            selfTriggeredRecreate = true
            recreate()
        }
    }

    /**
     * 引导第 1 步选了一个语言(T10)。**先把步骤号改成 2,再调 [applyLanguage]**:Locale 真变了的话
     * 它会 `recreate()`,`onSaveInstanceState` 写下的正是 2,新实例直接落在第 2 步、已是新语言;
     * 选的就是当前生效的语言时不重建,这一行本身就把界面带到了第 2 步。
     * `settingsRevision++` 只为没重建的那条路:让 `homeSettings.language` 跟上盘上的新值,
     * 从第 2 步按返回回来时「已选」标记在对的按钮上(重建的那条路上它是对一个即将销毁的实例赋值,无害)。
     */
    private fun chooseOnboardingLanguage(language: String) {
        onbStep = 2
        applyLanguage(language)
        settingsRevision++
    }

    /**
     * 引导第 2 步的「继续」(fill)/「跳过」。界面立刻进第 3 步,写盘在串行 IO 上做
     * (见 `onboardingLayoutWrites`:「继续 → 返回 → 跳过」两次写盘不会交叠,最后落盘的是最后一次选择);
     * 写完 `revision++`,底下常驻的首页按新 layout.json 重读。
     */
    private fun fillFromOnboarding(fill: Boolean) {
        onbStep = 3
        lifecycleScope.launch {
            val ok = withContext(onboardingLayoutWrites) { writeOnboardingLayout(this@MainActivity, fill) }
            if (ok) revision++ else toast(getString(R.string.toast_storage_not_ready))
        }
    }

    /** 引导里的返回键(页面自己的 BackHandler 与 [installBackHandler] 的兜底共用):上一步;第 1 步 = 结束。 */
    private fun stepBackInOnboarding() {
        val previous = onboardingBack(onbStep)
        if (previous == null) endOnboarding() else onbStep = previous
    }

    /**
     * 结束引导**只有这一条路**(第 3 步的「继续」/「跳过」、第 3 步去系统设置、第 1 步按返回)。
     * 顺序是死的:先写 `onboardingDone = true`(同步写完——第 3 步紧接着就要离开本应用,进程可能在
     * 系统页里被杀),再收浮层,再 `focusNonce++` 让首页从冻结的目标(冷启动即第一张卡,空桌面即齿轮)
     * 把焦点接回来。写盘失败也照样收起:用户明确结束了,本次会话不再打扰(盘上仍是 false,
     * 下次启动引导还会出现——那时存储多半已经恢复)。
     * 开头的判断读的是活状态,不是闩(铁律 7):同一帧里按两下只会结束一次。
     */
    private fun endOnboarding() {
        if (!onboarding) return
        SettingsStore.update(this) { it.copy(onboardingDone = true) }
        onboarding = false
        focusNonce++
    }

    /**
     * 「恢复默认」确认框按下「恢复」(spec §4)。表格划的界线是 settings.json 与
     * cache/ 两处:[restoredDefaults] 只动前者,library/titles.json/icons/ 一个字节都不碰
     * (纯函数,T1 已单测);壁纸缓存另调 [Wallpapers.clearCache],都在 IO 线程做。
     *
     * `confirmRestore = false` 特地等 `withContext(IO)` 写完盘**之后**才做,不学
     * `onRenameSave`/`closeCardMenu` 那种「先关浮层再异步写」——但这**保护不了**用户自己
     * 提前把确认框关掉的路径(取消 / 返回 / MENU 键,各自独立把 `confirmRestore` 写回
     * `false`,不受这里的顺序约束),所以 `SettingsScreen` 的重读**不能**挂在 `confirmRestore`
     * 或 `covered` 上(T7 复审 Important #1 修正)——那样的话,若用户在写盘完成前就关掉了
     * 确认框,`covered` 提前翻转触发一次读到旧值的重读,而写盘真正完成后 `confirmRestore`
     * 再赋一次同样的 `false` 对 Compose 是无操作,不会再触发一次。改用专用的
     * `settingsReloadNonce`(见其 KDoc):只在这里、写盘落地之后才 `++`,不管确认框走的是
     * 哪条 dismiss 路径,这一次递增必定发生、且必定在正确的时间点。
     *
     * `settingsRevision++`(首页重读)与 `wallpaperParams++`(壁纸管线,见其 KDoc)双双 bump:
     * 少 bump 后者的话,壁纸文件名虽然换回默认,但模糊/亮度还停在恢复前的处理结果上——
     * `wallpaperSpec` 的 remember key 特地不含 blur/brightness,只认这颗计数器。
     *
     * 语言字段固定被恢复成 `"system"`([restoredDefaults] 见 [Settings] 默认值),不必再读盘
     * 确认;真正需要判断的是它是否与**当前生效**的 Locale 不同 —— 用户本来就跟随系统时
     * 两者相等,不必 `recreate()`。走既有的 [applyLanguage]:T8 会在它上面接位置还原,
     * 现在调用它、之后 T8 落地不用改这里一行。
     */
    private fun confirmRestoreDefaults() {
        val now = System.currentTimeMillis()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                SettingsStore.update(this@MainActivity) { restoredDefaults(it, now) }
                Wallpapers.clearCache(this@MainActivity)
            }
            confirmRestore = false
            settingsRevision++
            wallpaperParams++
            settingsReloadNonce++
            toast(getString(R.string.toast_restored))
            if (localeFor("system") != AppLocale.current) applyLanguage("system")
        }
    }

    override fun onResume() {
        super.onResume()
        lastInput = System.currentTimeMillis()
        // 从别的应用回来时焦点是空的(实测停了 7 秒仍然没有任何节点持有,
        // 第一下按键才建立、而且落在第一张卡)。所以这里必须补一次请求;
        // 界面那边现在会把它送回**离开前那张卡**,不再是第一行第一张。
        focusNonce++
    }

    override fun onPause() {
        super.onPause()
        // 唤醒键的 UP 可能落不到本 Activity,离开时清掉,免得下次多吞一整下
        wakeDownTime = -1L
        // 长按那一下同理:「打开应用」会在 UP 之前就切走前台,那个 UP 落不回来。
        // (两者都按 downTime 匹配,留着也不会误吞后面的按键;清掉是为了不留悬空状态。)
        longPressDownTime = -1L
    }

    /**
     * 齿轮菜单四项,顺序固定(spec §1):编辑分栏 · UnitedU 设置 · 系统设置 · 关于。
     * 「导入图片 / 换壁纸 / 屏保图库 / 设置默认桌面」四项搬进设置页对应分组(SettingsModel.kt),
     * 不再是菜单项;`openImport()`/`pickWallpaper()`/`openHomeSettings()` 三个函数还在,
     * 只是改由那边的动作行调用(见 `settingsActions`)。
     */
    private fun menuItems() = listOf(
        MenuItem(getString(R.string.menu_edit), getString(R.string.menu_edit_desc)) { editing = true },
        MenuItem(getString(R.string.menu_settings), getString(R.string.menu_settings_desc)) {
            // 待机演示清零(见 [demoIdle] 的 KDoc):打开设置页那一刻先冲掉上一次会话的残留值,
            // 免得 SettingsScreen 报告真实焦点之前的那一帧里,底层首页读到一个过期的演示态。
            demoIdle = null
            settings = true
        },
        MenuItem(getString(R.string.menu_system_settings), getString(R.string.menu_system_settings_desc)) {
            // `open()` 失败时已经会 toast(`toast_open_failed`,带异常信息),复用它就不必
            // 再声明一个专门的 `toast_open_settings_failed` 只为了包一层同样的文案。
            open(Intent(Settings.ACTION_SETTINGS))
        },
        MenuItem(getString(R.string.menu_about), getString(R.string.menu_about_desc)) { about = true },
    )

    /**
     * 关长按菜单**只有这一条路**(与 closeMenu 同构)。菜单项随节点销毁时焦点会一并消失,
     * 所以必须 focusNonce++ —— 首页的还原效果据此把焦点送回「记住的那一格」,也就是那张卡。
     */
    private fun closeCardMenu() { if (cardMenu != null) { cardMenu = null; focusNonce++ } }

    /**
     * 「修改标题」保存**只有这一条路**,而且幂等:对话框里确定键(KeyUp)与 IME Done 是两条触发路径,
     * 极端时序下可能各来一次(输入法收起的同一帧里 Done 与抬起先后到达)。以 `renameTarget` 是否还在为准——
     * 第一次进来先把它清掉再去写盘,第二次直接返回。**不在对话框里放「已提交」布尔闩**(铁律 7):
     * 那种闩只有一条路能清,而这里的判据每次打开对话框天然重置。`focusNonce++` 让首页把焦点送回那张卡;
     * 写盘放 IO 线程,与 EditScreen.persist 同构。
     */
    private fun onRenameSave(ref: CardRef, text: String) {
        if (renameTarget == null) return
        renameTarget = null; focusNonce++
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { Titles.set(this@MainActivity, ref.pkg, text) }
            toast(getString(if (ok) R.string.toast_title_saved else R.string.toast_title_not_saved))
            if (ok) revision++
        }
    }

    /** 长按菜单项。顺序与文案见 design §2;RENAME 在 Task 5 接对话框,本任务先不列出。 */
    private fun cardMenuItems(ref: CardRef): List<MenuItem> = cardMenuActions(ref.kind).mapNotNull { action ->
        when (action) {
            CardAction.OPEN -> MenuItem(getString(R.string.card_menu_open), getString(R.string.card_menu_open_desc)) {
                closeCardMenu()
                if (!Apps.launch(this, ref.pkg)) toast(getString(R.string.toast_cant_open_app, ref.label))
            }
            CardAction.UNINSTALL -> MenuItem(getString(R.string.card_menu_uninstall), getString(R.string.card_menu_uninstall_desc)) {
                closeCardMenu()
                val ok = runCatching {
                    startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${ref.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (!ok) toast(getString(R.string.toast_uninstall_failed))
            }
            CardAction.RENAME -> MenuItem(getString(R.string.card_menu_rename), getString(R.string.card_menu_rename_desc)) {
                closeCardMenu(); renameTarget = ref
            }
            CardAction.CHANGE_ICON -> MenuItem(getString(R.string.card_menu_icon), getString(R.string.card_menu_icon_desc)) {
                closeCardMenu()
                // 不再记落点(M7 T4):选择器改成叠在常驻首页之上,首页那棵树不会被移除,
                // `tgtRow`/`tgtIdx` 在 `previewing` 期间冻着,关掉选择器就原样还原到这张卡。
                pickIcon(ref.pkg)
            }
            CardAction.MOVE -> MenuItem(getString(R.string.card_menu_move), getString(R.string.card_menu_move_desc)) {
                // 带**包名**而不是列号:编辑页按 layout.json 排,里面还留着装不到的包,
                // 渲染列号在那边会对到另一张卡上。行号用 layout 行号,同理。
                closeCardMenu(); editTarget = ref.layoutRow to ref.pkg; editing = true
            }
            CardAction.REMOVE -> MenuItem(getString(R.string.card_menu_remove), getString(R.string.card_menu_remove_desc)) {
                closeCardMenu()
                // 写盘(tmp → fsync → rename)放 IO 线程,与 onRenameSave / EditScreen.persist 同构——
                // 主线程上 fd.sync() 会卡住那一帧(终审 Minor #4)。先关菜单再写,时序是安全的:
                // 关菜单 nonce++ 让还原效果先把焦点落回那张卡(它此刻还在);写完 revision++ → stale 冻结目标
                // → 新行数据落地后再按夹过的列号送到同行邻卡(design §1「行变短时索引夹取」)。
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { Layout.removeFromRow(this@MainActivity, ref.layoutRow, ref.pkg) }
                    if (ok) revision++
                    // 失败只有一种原因:那一行/那个包已经不在盘上了(别处刚改过 layout.json)。
                    // 不能再报「顺序没能存下来」—— 那是写盘失败的文案,会把人引到错误的方向。
                    else toast(getString(R.string.toast_remove_failed))
                }
            }
        }
    }

    /** @return 选择器是否真的打开了;false = 存储没就绪(已 toast),调用方不要留任何「回来时用」的状态。 */
    private fun pickIcon(pkg: String): Boolean {
        if (Paths.baseOrNull(this) == null) { toast(getString(R.string.toast_storage_not_ready)); return false }
        pickerTarget = pkg
        return true
    }

    private fun open(intent: Intent) {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast(getString(R.string.toast_open_failed, it.message)) }
    }

    private fun pickWallpaper() {
        if (Paths.baseOrNull(this) == null) { toast(getString(R.string.toast_storage_not_ready)); return }
        closeMenu()
        pickerTarget = PICK_WALLPAPER
    }

    private fun openImport() {
        if (Paths.baseOrNull(this) == null) { toast(getString(R.string.toast_storage_not_ready)); return }
        closeMenu()
        pickerTarget = VIEW_IMPORT
    }

    /**
     * 屏保图库查看器的入口。**齿轮菜单 / 设置页本轮都没有按钮调它**(spec §1:四项菜单里
     * 「屏保图库」被移除;§2.2 待机组的屏保子项要等 M5)——保留函数与 [VIEW_SCREENSAVER_POOL]
     * 只是不删掉这条已经写好、M5 会直接复用的路径,不是死代码判断失误。
     */
    @Suppress("unused")
    private fun openScreensaverPool() {
        if (Paths.baseOrNull(this) == null) { toast(getString(R.string.toast_storage_not_ready)); return }
        closeMenu()
        pickerTarget = VIEW_SCREENSAVER_POOL
    }

    private fun openHomeSettings() {
        closeMenu()
        pickerTarget = VIEW_HOME_SETTINGS
    }

    private fun handlePick(file: java.io.File) {
        val target = pickerTarget ?: return
        pickerTarget = null
        if (target == PICK_WALLPAPER) {
            // 只记文件名,不复制、不 recreate(recreate 会把焦点打回第一张卡、屏幕黑一下)
            val ok = Wallpapers.select(this, file)
            toast(getString(if (ok) R.string.toast_wallpaper_changed else R.string.toast_invalid_image))
            if (ok) settingsRevision++
        } else {
            val dest = Paths.iconFor(this, target)
            val tmp = java.io.File(dest.parentFile, "$target.tmp")
            val ok = runCatching {
                file.inputStream().use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out); out.flush(); out.fd.sync() }
                }
                check(Apps.isDecodableImage(tmp.absolutePath))
                if (!tmp.renameTo(dest)) { dest.delete(); check(tmp.renameTo(dest)) }
            }.isSuccess
            tmp.delete()
            toast(getString(if (ok) R.string.toast_card_image_changed else R.string.toast_invalid_image))
            if (ok) revision++
        }
    }

    private fun restoreOriginalIcon(pkg: String) {
        pickerTarget = null
        val custom = Paths.iconFor(this, pkg)
        if (custom.exists()) custom.delete()
        toast(getString(R.string.toast_icon_restored))
        revision++
        focusNonce++
    }

    /**
     * 设置默认桌面。应用自己没有权限改 HOME 角色,所以走系统的「主屏幕应用」设置页;
     * 那个页面不存在时,退而求其次直接启动原厂桌面。
     */
    private fun switchHome() {
        val home = Intent("android.settings.HOME_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (home.resolveActivity(packageManager) != null &&
            runCatching { startActivity(home) }.isSuccess
        ) return
        val pm = packageManager
        val fallback = pm.getLeanbackLaunchIntentForPackage("com.dangbei.TVHomeLauncher")
            ?: pm.getLaunchIntentForPackage("com.dangbei.TVHomeLauncher")
        if (fallback != null) {
            toast(getString(R.string.toast_opened_stock_launcher))
            open(fallback)
        } else {
            toast(getString(R.string.toast_stock_launcher_not_found))
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packageChanges) }
        // 关于页不跨 Activity 重建(`about` 不进 Bundle):这个实例一走,它等着用户按「安装」的
        // 那份已校验文件就再没人用了,在这里丢掉;进行中的检查 / 下载本来就随 lifecycleScope 取消。
        aboutFlow.reset()
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /**
     * 桌面不该被返回键退出。
     * 不用 override 已废弃的 `onBackPressed`:targetSdk 升到 36 后预测式返回默认开启,
     * 那个 override 会**彻底不再被调用**,而默认行为是 finish 掉 Activity——
     * 对 HOME 应用就是桌面直接消失。常开的 callback 两种模式下都有效。
     */
    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    // 首次引导画在最上层(T10),兜底也最先判(Onboarding 自带的 BackHandler 正常会先接管)。
                    onboarding -> stepBackInOnboarding()
                    // 「关于」页画在最上层,兜底也最先判(AboutScreen 自带的 BackHandler 正常会先接管)。
                    // 它开着时下面几种浮层都不可能同时在场(只能从首页齿轮菜单打开,打开时菜单已收起)。
                    about -> onAboutBack()
                    menuOpen -> closeMenu()
                    // 与 menuOpen 同理的兜底:GearMenu 自带的 BackHandler 组合时挂得更晚、正常会先接管,
                    // 但这一层不能是空的 —— 万一那条路没接住,返回键就会落进「桌面根状态什么都不做」,
                    // 菜单留在屏幕上而按键毫无反应。
                    cardMenu != null -> closeCardMenu()
                    // 同理:TitleDialog 自带的 BackHandler 正常会先接管,这里是同一种兜底
                    // (T5 review Important #4)——返回键在对话框开着时绝不能是空操作。
                    renameTarget != null -> { renameTarget = null; focusNonce++ }
                    // 同理:每个选择器 / 导入页 / 默认桌面卡都自带 BackHandler,正常会先接管。
                    // 必须排在 editing / settings 之前(终审 C1):选择器盖在(或替换了)这两页上,
                    // 万一漏接,落到下面那两支就会把底下那页整个收掉、选择器却还留在首页上。
                    pickerTarget != null -> { pickerTarget = null; focusNonce++ }
                    // 同理:ConfirmDialog 自带的 BackHandler 正常会先接管,这里是同一种兜底——
                    // 放在 `settings` 之前,万一没接住也只收掉确认框本身,不连带关掉整个设置页。
                    confirmRestore -> confirmRestore = false
                    editing -> leaveEdit()
                    settings -> leaveSettings()
                    // 桌面根状态:什么都不做,绝不 finish
                }
            }
        })
    }
}

/**
 * followWallpaperColor 打开时,从当前壁纸主色推导界面强调色(经 LocalThemeColors 供给每个界面)。
 * **必须在 IO 线程调用**:取色会解一张缩略图并跑 Palette(实现见 [Wallpapers.paletteAccent])。
 *
 * accent 用取到的色,highlight 由 [highlightFrom] 混白 55% 推得 —— 与非金预设 highlight 同一手法。
 * 任何一步落空(没壁纸、解不出、Palette 抽不到色)返回 null,调用方回落到选中预设,绝不崩、绝不留黑。
 *
 * 取到的色只喂界面强调色,壁纸本身**不染色**(「主题化壁纸」2026-09-16 删掉,壁纸管线不再认识主题色)。
 */
private fun wallpaperThemeColors(ctx: android.content.Context, wallpaperFile: String): ThemeColors? =
    Wallpapers.resolveSource(ctx, wallpaperFile)
        ?.let { Wallpapers.paletteAccent(ctx, it) }
        ?.let { rgb ->
            // 壁纸主色可能很暗 / 很灰,先提亮到可读地板再当强调色(见 usableAccent);
            // 否则深色主题色压在 #0A0A0A 的设置页上,分组标题等文字直接消失。
            val accent = androidx.compose.ui.graphics.Color(usableAccent(rgb) or 0xFF000000.toInt())
            ThemeColors(accent, highlightFrom(accent))
        }
