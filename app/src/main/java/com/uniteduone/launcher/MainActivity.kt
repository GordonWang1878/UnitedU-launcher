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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面主界面。统筹待机计时、返回键不退出、齿轮菜单入口,以及设置页/选择器/引导等浮层的开关状态。
 * 视觉全在 HomeScreen / AppCard / Clock 里,规格见 docs/DESIGN-unitedu-open-source.md §4。
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

/**
 * 移动态下照常放行的键(M4b spec §3「其它键吞掉」的唯一例外):音量。桌面自己从不处理它们,
 * 放行只会落到系统的音量调节,不碰任何界面状态;吞掉的话搬卡的那几秒里电视音量调不了。
 */
private val MOVE_PASSTHROUGH_KEYS = setOf(
    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
)

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
    /**
     * 待机与自定义屏保(M5 spec §1)。**一个值装两个布尔量**,只取 [StandbyFlags] 的三个常量:
     * 不变量「屏保 ⇒ 待机」由它的构造函数钉死,两个量一次写完、没有「只写了一半」的中间态。
     * 读者照旧按布尔量读([idle] / [screensaverActive]),`idle` 原有的五个消费者一个字都不用改。
     * **必须住在 Activity 里**,因为唤醒发生在 dispatchKeyEvent。
     */
    private var standby by mutableStateOf(StandbyFlags.NORMAL)
    /** 待机:首页内容淡出 + 下一个按键当唤醒吞掉。自定义屏保时同样为真(spec §1 表)。 */
    private val idle: Boolean get() = standby.idle
    /** 自定义屏保:全屏轮播屏保图库。只在 [idle] 为真时可能为真。 */
    private val screensaverActive: Boolean get() = standby.screensaverActive
    /**
     * 屏保按钮的请求计数(M8 spec §1.5;M5 spec §1.3 起 = 立刻进自定义屏保、跳过待机,图库空时退为待机)。
     * **不能在点击回调里直接写状态**:按下确认键的那次 dispatchKeyEvent 已经刷新了 lastInput,
     * 计时效果随之在同一次重组里重启、把状态写回正常。所以走一个独立的请求计数:它的效果声明在
     * 计时效果之后、先等一帧再写(R3),稳赢那次重启。唤醒仍由 dispatchKeyEvent 吞掉下一次按键
     * (与超时进入同一条路);不是闩——每次点击都是一次新计数(铁律 7)。
     */
    private var screensaverRequests by mutableStateOf(0)
    /**
     * 屏保图库版本(M5 spec §3 / §5):删图后 +1。设置页的图库计数(「屏保启动」行的提示)与图库查看器的
     * 文件列表都以它为 key 重读。只增不减,不是闩(铁律 7)。
     */
    private var galleryVersion by mutableStateOf(0)
    /**
     * 屏保图库网格当前聚焦的那张图(PickerGrid 上报:得到报文件、失去报 null)。长按确定键据此弹删除确认框。
     * 只在 [pickerTarget] == [VIEW_SCREENSAVER_POOL] 时有意义(长按那一支先判它);网格离开组合时报 null,
     * [openScreensaverPool] 打开时再清一次——下一次会话在第一次焦点上报之前也读不到上一次的旧文件。
     */
    private var poolFocusedFile by mutableStateOf<java.io.File?>(null)
    /**
     * 删除确认框开着的那张图;null = 没开(M5 spec §5)。确定([deletePoolImage] 删完才清)、取消 / 返回
     * (onCancelDelete)各自清它,[openScreensaverPool] 打开时再兜底清一次:不会有「上次没清掉、下次一打开图库
     * 就蹦出确认框」的路(铁律 7)。
     */
    private var poolDeleteTarget by mutableStateOf<java.io.File?>(null)
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
    /** 编辑页该定位到哪张卡:编辑页里「换卡片图」的选择器关掉、编辑页重建时(M7 终审 C1)。
     *  (M4b 起长按菜单「移动位置」改为首页原地移动,不再经过这里。)**(layout.json 行号, 包名)** ——
     *  列号不能带:编辑页按 layout.json 排,里面还有装不到的包占位,渲染列号对不上。
     *  只有 `leaveEdit()` 清它;编辑期间留着无害(编辑页按「已应用的目标」比对,同一个实例只应用一次)。 */
    private var editTarget by mutableStateOf<Pair<Int, String>?>(null)
    /**
     * **首页原地移动态**(M4b spec §3)。唯一的一份:按键([dispatchKeyEvent] 里最先截获)改它,HomeScreen 照它画、
     * 以它的 `pos` 为焦点目标。null = 不在移动态。进入只有 [startMove] 一条路;结束只有 [endMove] 一条路
     * (放下写盘成功 / 什么都没动就按确定 / [cancelMove]),取消的入口见 [cancelMove] 的 KDoc。
     */
    private var moving by mutableStateOf<MoveState?>(null)
    /** 移动态结束时的落点(见 [MoveLanding]);HomeScreen 的还原效果把它写进目标格。每次结束都是一个新对象,不需要清。 */
    private var moveLanding by mutableStateOf<MoveLanding?>(null)
    /**
     * 首页最近一次组合画出来的行(HomeScreen 的 `onRowsShown`)。进移动态时拿它当工作副本的起点——
     * 「首页已渲染的行」,不重读磁盘。**不是 `mutableStateOf`**:只在 [startMove] 那一刻读一次。
     */
    private var shownRows: List<Row> = emptyList()
    /**
     * 移动态里按下的那一下的 downTime:同一次按压之后的事件(重复、UP)按它认,一律吞掉——**哪怕这一下
     * (确定 / 返回)已经结束了移动态**。不吞的话确定键的 UP 会落到被搬的卡上:tv-material 的卡在 UP 时
     * 直接触发 onClick(不看之前有没有收到过 DOWN),放下的同时把应用打开了。与 [wakeDownTime] 同一手法。
     */
    private var moveDownTime = -1L
    /**
     * 移动态里按满 [LONG_PRESS_MS] 的那一下确定键的 downTime:它松手时不算放下(Step 6「移动态长按确定:无反应」)。
     * 按 downTime 认,每一下按压天然不同,不需要清(铁律 7)。由**重复事件**判长按,不看 UP 的时间戳:
     * `input keyevent --longpress` 注入的 UP 沿用 DOWN 的 eventTime,按时间差算永远是「短按」。
     */
    private var moveHeldDownTime = -1L
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
                    // M5:屏保图库与换壁纸同一套(只置 pickerTarget,叠在设置页上),关掉后设置页把焦点接回这一行。
                    openScreensaverGallery = { openScreensaverPool() },
                    openSystemScreensaver = { openSystemScreensaverSettings() },
                    // M4b:布局组「恢复隐藏的输入源」行,只在 hiddenInputs > 0 时存在。
                    restoreHiddenInputs = ::restoreHiddenInputs,
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
            // M5:解析本体搬到 ThemeResolve.kt 的 rememberThemeColors——系统屏保(UnitedUDream)走同一条路(spec §4)。
            val themeColors = rememberThemeColors(this@MainActivity, homeSettings, revision)
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
            // 底下那层照旧在计时;不挡的话在「换壁纸」里挑图挑够时间,首页会先淡出进入待机、
            // 再等屏保时长用完才渐入自定义屏保(M5 spec §1:待机与屏保是先后两个互斥状态,
            // 不是一步耦合),而下一个按键还要被 dispatchKeyEvent 当唤醒吞掉。
            // 与 menuOpen 同一处理:既是 key 也是守卫(铁律 6)。
            val overlay = overlayOpen
            // 长按卡片菜单与「修改标题」对话框同理(终审 Important #3):输入法显示着时每个按键都被它先吃掉,
            // 根本到不了 dispatchKeyEvent,lastInput 在打字期间不会刷新;不让路的话三分钟后卡片淡出、
            // 屏保从蒙版后面渐入,下一个按键还被当唤醒吞掉。与 menuOpen 完全同一处理:既是 key 也是守卫(铁律 6)。
            val homeOverlay = cardMenu != null || renameTarget != null
            // 待机时长/内容改由设置页驱动(Task 3):idleAfterMs 既是 key 也是守卫(铁律 6)——
            // 用户把它从「关」改成别的值(或反过来)时,这条 effect 必须以新 key 重启,
            // 否则「关」之后再打开待机,要等到下一次别的 key 变化才会生效。
            // M5:screensaverAfterMs 同理(铁律 6)——两个时刻都由 standbyPlan 从同一次按键起算(spec §1.1)。
            val idleAfterMs = homeSettings.idleAfterMs
            val screensaverAfterMs = homeSettings.screensaverAfterMs
            LaunchedEffect(touched, editing, menuOpen, overlay, homeOverlay, idleAfterMs, screensaverAfterMs) {
                standby = StandbyFlags.NORMAL
                if (editing || menuOpen || overlay || homeOverlay) return@LaunchedEffect
                val plan = standbyPlan(idleAfterMs, screensaverAfterMs)
                var waited = 0L
                val standbyAt = plan.standbyAt
                if (standbyAt != null) {
                    delay(standbyAt)
                    waited = standbyAt
                    // 只升不降:屏保按钮可能已经把状态推到了屏保,这一拍不能把它拉回待机
                    // (spec 的写法是「只写 idle = true」;打包成一个值之后,等价写法就是 atLeastStandby()——
                    // 已经 idle 就原样不动,只有 NORMAL 才被抬到 STANDBY)。判断抽到 StandbySchedule.kt,
                    // 与屏保按钮效果共用同一份逻辑,JVM 单测覆盖(终审 Important 2)。
                    standby = standby.atLeastStandby()
                }
                // 屏保「关」:停在待机(待机也「关」就是什么都不发生)。
                val screensaverAt = plan.screensaverAt ?: return@LaunchedEffect
                delay(screensaverAt - waited)
                // 到点才查图库(spec §1.1):空 → 停在待机,下一个键照常只负责唤醒;非空 → 进屏保。
                if (withContext(Dispatchers.IO) { hasScreensaverImages(this@MainActivity) }) {
                    standby = StandbyFlags.SCREENSAVER
                }
            }
            // **首页被盖住 = 移动态取消**(M4b spec §0-9「进待机、任何浮层要打开 → 等同取消」)。移动态下按键全被
            // dispatchKeyEvent 截走,遥控器开不出任何浮层;剩下的来路是待机计时到点,以及指针事件(鼠标 / 触摸点到齿轮)
            // ——都在这一处收口,不去每个浮层的入口各判一次。守卫的两个量都是 key(铁律 6);
            // 不是闩(铁律 7):cancelMove 本身读活状态,写盘途中(committing)它什么都不做,由写盘结果决定去留。
            val moveBlocked = editing || menuOpen || overlay || homeOverlay || idle
            val inMove = moving != null
            LaunchedEffect(moveBlocked, inMove) {
                if (moveBlocked && inMove) cancelMove()
            }
            // 屏保按钮的请求(见 screensaverRequests 的 KDoc):声明在计时效果之后、再等一帧,保证后写(R3)。
            // M5 spec §1.3:有图 → 立刻进自定义屏保、跳过待机;图库空 → 退为进待机;空图库 +「不淡出」→ 空操作,
            // 下一个键不被吞(「不淡出」的待机没有任何可见效果,进了只会白吞一个键)。NO_FADE 的判断从调用点
            // 移到这里:有图时「不淡出」也能进屏保。idleContentNow 是按下那一刻的设置(本效果随请求计数重启)。
            // 目标状态的判断抽到 StandbySchedule.screensaverButtonTarget,与本效果共用同一份逻辑,JVM 单测覆盖
            // (终审 Important 2)。
            val idleContentNow = homeSettings.idleContent
            LaunchedEffect(screensaverRequests) {
                if (screensaverRequests == 0) return@LaunchedEffect
                // 这次请求触发那一刻的按键时间戳:按钮那下 dispatchKeyEvent 已经刷新过 lastInput
                // (先于 screensaverRequests 这颗计数器的写入,见其 KDoc),这里捕获的就是「这次点击」
                // 本身,不是更早的一次。
                val at = lastInput
                withFrameNanos { }
                val hasImages = withContext(Dispatchers.IO) { hasScreensaverImages(this@MainActivity) }
                // **异步跳转(等帧 + IO 扫描)之后的一次性复查,不是重启型守卫**——不进 key。铁律 6 管的是
                // 「守卫值必须同时是 key,否则效果不会在它变化时重启」;这里反过来,故意不想因为这些量的
                // 变化重启整个效果,只想在真正落笔之前再看一眼当下是否仍然成立。期间若又来一次按键
                // (计时效果随之以新 key 重启、把状态写回 NORMAL)或打开了菜单/浮层,这次写入就该放弃——
                // 否则要么把 SCREENSAVER 盖在一个更新的状态之上,要么在浮层开着时违反「浮层 ⇒ 绝不待机」,
                // 让轮播在打开的菜单底下渐入、下一下按键还被当唤醒错吞。
                if (lastInput != at || editing || menuOpen || overlayOpen || cardMenu != null || renameTarget != null) {
                    return@LaunchedEffect
                }
                val target = screensaverButtonTarget(hasImages, idleContentNow)
                if (target != null) standby = target
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
            UnitedUTheme(themeColors) {
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
            // 自定义屏保层(M5 spec §1.4 第 2 层):只看 screensaverActive。不再因「不淡出」不组合——
            // 待机显示只管待机,「不淡出」时屏保照样会来(spec §0);没进屏保时 alpha 为 0,一张图都不画。
            Screensaver(active = screensaverActive, intervalMs = homeSettings.screensaverIntervalMs)
            // BLACK(Task 3):在屏保之上叠一层纯黑,随 idle 淡入淡出;配合 HomeScreen 里
            // 时钟自己的 clockAlpha 一起淡出,才是「整屏全黑」而不是黑底衬着屏保/时钟。
            // **待机演示(spec §3.2)也要能让这层变黑**:目标值同时看真实待机与演示值
            // (`activeDemoIdle ?: 真实设置`)——只看真实设置的话,演示切到「全黑」时这层不会动。
            //
            // **动画状态常驻组合,Box 只在 alpha > 0 时才组合**(终审 M3)。之前是「选了 BLACK 或
            // 演示值 == BLACK」才组合整段(连同 animateFloatAsState):从「时钟」演示切到「全黑」那一下,
            // 这段才刚被组合出来,动画的初值就是目标值 1,黑屏是「弹」出来的;离开那一行时整段又被
            // 立刻移出组合,黑屏同样是瞬间消失而不是 400 ms 淡出。现在动画值一直活着、从上一次的值
            // 平滑过去(淡入 0 → 1、淡出 1 → 0 都完整),Box 等淡出真的走到 0 才离开组合。
            // 两处读 alpha 都不在组合阶段逐帧发生:`derivedStateOf` 只在「是否 > 0」翻转时让这里重组,
            // 透明度在 drawBehind(绘制阶段)里读——淡入淡出的 1.2 s 里不会每帧重组整层。
            val demoActive = activeDemoIdle != null
            val blackIdle = idle || demoActive
            val blackContent = activeDemoIdle ?: homeSettings.idleContent
            val blackAlpha = animateFloatAsState(
                // 进自定义屏保时黑层淡出、照片亮出来(M5 spec §1.4 第 3 层)——「全黑」只管待机。
                targetValue = if (blackIdle && blackContent == IdleContent.BLACK && !screensaverActive) 1f else 0f,
                animationSpec = tween(if (blackIdle) 1200 else 400),
                label = "blackAlpha",
            )
            val blackShown by remember(blackAlpha) { derivedStateOf { blackAlpha.value > 0f } }
            if (blackShown) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawRect(androidx.compose.ui.graphics.Color.Black, alpha = blackAlpha.value)
                        }
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
                    screensaver = screensaverActive,
                    idleContent = homeSettings.idleContent,
                    demoIdle = activeDemoIdle,
                    menuItems = menu,
                    menuOpen = menuOpen,
                    onMenuOpenChange = { if (it) { menuFromGear = true; menuOpen = true } else closeMenu() },
                    // 屏保按钮 = 立刻进自定义屏保、跳过待机(M5 spec §1.3),走请求计数(见 screensaverRequests 的 KDoc)。
                    // 「不淡出」的判断移进了请求效果:有图时照样进屏保,只有空图库 +「不淡出」才是空操作。
                    onScreensaver = { screensaverRequests++ },
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
                    moving = moving,
                    moveLanding = moveLanding,
                    onRowsShown = { shownRows = it },
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
                        // 图库版本(M5):删图后 +1,设置页据此重数图库(「屏保启动」行的提示)。
                        galleryVersion = galleryVersion,
                        // M4b:「隐藏 / 恢复输入源」都靠它让首页重建卡片行,设置页的隐藏数顺着它重读
                        // (见 SettingsScreen 里这个参数的 KDoc——不能挂 settingsRevision,那颗不重建首页行)。
                        revision = revision,
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
            // M5 spec §5:长按缩略图删图。长按识别在 dispatchKeyEvent(「图库光着」那一支),这里只接线:
            // 网格上报聚焦的文件、确认框的目标与两个按钮。确认框自己负责焦点;关掉后(删除 / 取消都 focusNonce++)
            // deleteTarget 变 null 让 PickerGrid 的 covered 翻回 false,由它 (nonce, covered, focusRequesters)
            // 那条定位效果重新跑一轮接回原位置;万一没接上,还有 holderIdx == null 的看门狗兜底。
            VIEW_SCREENSAVER_POOL -> ScreensaverPoolViewer(
                nonce = focusNonce,
                refresh = galleryVersion,
                onFocusedFile = { poolFocusedFile = it },
                deleteTarget = poolDeleteTarget,
                onConfirmDelete = ::deletePoolImage,
                onCancelDelete = { poolDeleteTarget = null; focusNonce++ },
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
            // 待机与自定义屏保一样:任意键回到正常,这一下只负责唤醒(spec §1.2)。
            standby = StandbyFlags.NORMAL
            wakeDownTime = event.downTime
            return true
        }
        if (wakeDownTime != -1L && event.downTime == wakeDownTime) {
            if (event.action == KeyEvent.ACTION_UP) wakeDownTime = -1L
            return true
        }
        // **首页原地移动态**(M4b spec §3):排在 MENU、长按识别与 Compose 之前,除音量外的键一律在这里截走。
        // 方向键搬卡、确定放下(松手时,见 onMoveKeyUp)、返回取消,其余(MENU、长按、别的一切)按下去什么都不发生。
        // 每一下按压从 DOWN 到 UP 整个吞掉:按 downTime 认(见 moveDownTime 的 KDoc),所以结束移动态的那一下
        // (确定 / 返回)的 UP 也落不到界面上——卡片不会因为这个 UP 被「点击」,返回键也不会再触发一次返回。
        val inMovePress = moveDownTime != -1L && event.downTime == moveDownTime
        if ((moving != null && event.keyCode !in MOVE_PASSTHROUGH_KEYS) || inMovePress) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (moving != null) {
                    moveDownTime = event.downTime
                    onMoveKey(event)
                }
                KeyEvent.ACTION_UP -> {
                    if (inMovePress) moveDownTime = -1L
                    onMoveKeyUp(event)
                }
            }
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
            // 屏保图库「光着」(M5 spec §5):图库开着、确认框没开、网格上有聚焦的缩略图。满 LONG_PRESS_MS 弹删除
            // 确认框,整下吞掉(同 longPressDownTime 手法:UP 落不到缩略图上,不会顺带打开全屏预览)。
            // 与上面的 homeBare 天然互斥:homeBare 要求 !overlayOpen,而图库开着时 pickerTarget != null。
            // 全屏预览开着时网格失焦、poolFocusedFile 已报 null,这一支不成立 = 预览里长按无效。
            val poolBare = pickerTarget == VIEW_SCREENSAVER_POOL && poolDeleteTarget == null && poolFocusedFile != null
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && poolBare
                && event.eventTime - event.downTime >= LONG_PRESS_MS
            ) {
                longPressDownTime = event.downTime
                window.decorView.playSoundEffect(SoundEffectConstants.CLICK)
                poolDeleteTarget = poolFocusedFile
                return true
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
        if (event.action == KeyEvent.ACTION_DOWN) playKeySound(event.keyCode)
        return result
    }

    /** 方向键 / 确定键的按键音。普通导航(super.dispatchKeyEvent 之后)与移动态([onMoveKey])共用这一份映射。 */
    private fun playKeySound(keyCode: Int) {
        val sfx = when (keyCode) {
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

    /**
     * 移动态下一次按下(DOWN,含按住不放的重复)。方向键:搬一步——按住就连续搬,与普通导航按住连续移动焦点一致;
     * 状态在这里同步改完,焦点由 HomeScreen 的还原效果按新的 `pos` 追过去,连发再快也不会跑在状态前面。
     * 返回 = 取消(只认第一下)。确定键在这里**只记长按**,放下在松手时([onMoveKeyUp]):DOWN 那一刻分不出
     * 短按还是长按,在 DOWN 上放下的话,长按确定也会把卡放下(2026-09-19 模拟器实测),而移动态里长按应当无反应。
     * 其余键:什么都不做(已被外层吞掉)。写盘途中(committing)一律不动。
     */
    private fun onMoveKey(event: KeyEvent) {
        val st = moving ?: return
        if (st.committing) return
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> MoveDir.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> MoveDir.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> MoveDir.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> MoveDir.DOWN
            else -> null
        }
        if (dir != null) {
            playKeySound(event.keyCode)
            val (rows, pos) = moveCard(st.rows, st.pos, dir)
            // 到头 / 那个方向没有应用行:moveCard 原样返回同一个 list,状态不动
            if (rows !== st.rows) moving = st.copy(rows = rows, pos = pos)
            return
        }
        when (event.keyCode) {
            // 与首页长按识别同一判据(同一次按压持续满 LONG_PRESS_MS 后的重复事件)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (event.repeatCount > 0 && event.eventTime - event.downTime >= LONG_PRESS_MS) {
                    moveHeldDownTime = event.downTime
                }
            KeyEvent.KEYCODE_BACK -> if (event.repeatCount == 0) cancelMove()
        }
    }

    /**
     * 移动态下松开一个键。只有确定键有事:没按成长按、也没被系统取消(FLAG_CANCELED)的那一下 = 放下,
     * 与普通状态下「松手才点击、按满时长是长按」同一语义。
     */
    private fun onMoveKeyUp(event: KeyEvent) {
        val st = moving ?: return
        if (st.committing || event.isCanceled || event.downTime == moveHeldDownTime) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                playKeySound(KeyEvent.KEYCODE_DPAD_CENTER)
                dropMove(st)
            }
        }
    }

    /**
     * 长按菜单「移动位置」:进入首页原地移动态(M4b spec §0-9)。工作副本 = 首页此刻画着的行([shownRows])。
     * 那张卡按 **(layout 行号, 包名)** 在里面重新定位,不直接用 ref 的渲染行列号:菜单开着的这段时间里
     * 后台重读可能已经落地、行列变了;一行之内包名唯一(`Layout.read` 做过 distinct),所以这样找不会找错
     * ——同一个包出现在两行时,layout 行号把它们分开(spec §3「按位置追踪,不按包名找」说的正是跨行重名)。
     * 找不到(那张卡刚被卸载)就什么都不做。存储没挂上时先提示:搬完也存不下来。
     */
    private fun startMove(ref: CardRef) {
        if (Paths.baseOrNull(this) == null) { toast(getString(R.string.toast_storage_not_ready)); return }
        val rows = shownRows
        val r = rows.indexOfFirst { it.kind == RowKind.APPS && it.layoutRow == ref.layoutRow }
        val c = rows.getOrNull(r)?.apps?.indexOfFirst { it.packageName == ref.pkg } ?: -1
        if (c < 0) return
        val at = MovePos(r, c)
        moving = MoveState(pos = at, rows = rows, original = rows, from = at)
    }

    /**
     * 放下(确定键短按松手,见 [onMoveKeyUp])。什么都没动过就直接结束,不写盘、不重读。否则先标 committing(按键与取消入口在写盘期间一律不理),
     * IO 线程上 `Layout.write(mergeMove(盘上最新, original, 工作副本))`:成功 → 结束,焦点落在卡的新位置,
     * `revision++` 让首页按新 layout.json 重读(**不是 settingsRevision**:那颗只重读 settings.json、不重建首页行,
     * 挂它的话放下后首页会一直画着搬之前的旧数据);失败 → toast 并回到移动态,可以再按确定重试或按返回取消
     * ——若这期间已经离开前台,就直接取消。
     */
    private fun dropMove(st: MoveState) {
        if (st.rows == st.original) { endMove(st.pos, wrote = false); return }
        moving = st.copy(committing = true)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val disk = Layout.read(this@MainActivity)
                // 移动途中被真正卸载的包,pruneUninstalled 已经把它从盘上清掉了;工作副本里它还在,
                // 不滤掉的话这里会把它原样写回去,编辑页从此多一张「未安装」的僵尸卡。
                // **original 与工作副本一起滤**:mergeMove 按「可见顺序变没变」决定一行要不要改写,
                // 只滤一边的话,没搬到、却恰好少了个被卸载包的那一行会被当成「变了」,未安装的包被挪到行尾。
                val onDisk = disk.flatMapTo(HashSet()) { it.apps }
                fun List<Row>.onlyOnDisk() = map { r ->
                    if (r.kind == RowKind.APPS) r.copy(apps = r.apps.filter { it.packageName in onDisk }) else r
                }
                Layout.write(this@MainActivity, mergeMove(disk, st.original.onlyOnDisk(), st.rows.onlyOnDisk()))
            }
            val cur = moving?.takeIf { it.committing } ?: return@launch
            if (ok) {
                endMove(cur.pos, wrote = true)
                revision++
            } else {
                toast(getString(R.string.toast_move_failed))
                moving = cur.copy(committing = false)
                if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) cancelMove()
            }
        }
    }

    /**
     * 取消移动态:首页照磁盘数据原样画回(`loaded` 就是进入移动态前的样子——移动态从不写盘),焦点回出发那一格。
     * 入口:返回键([onMoveKey];[installBackHandler] 兜底)、HOME([onNewIntent])、离开前台([onPause])、
     * 首页被任何东西盖住或进待机(setContent 里的 moveBlocked 效果)。写盘途中不理:放下已经决定了,由写盘结果收尾。
     */
    private fun cancelMove() {
        val st = moving ?: return
        if (st.committing) return
        endMove(st.from, wrote = false)
    }

    /** 结束移动态**只有这一条路**:清掉状态,给出落点(HomeScreen 的还原效果把焦点送到那里、写进它的目标格)。 */
    private fun endMove(landing: MovePos, wrote: Boolean) {
        moving = null
        moveLanding = MoveLanding(landing, wrote)
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
        // HOME = 移动态取消(M4b spec §0-9)。通常 onPause 已经先取消过,这里是同一件事的第二道。
        cancelMove()
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
        // moveDownTime **故意不清**:它按 downTime 匹配,留着不会误吞别的按压;而一个在移动态里按下、
        // 暂停期间没松开的键,它的 UP 若回到本 Activity,仍然要吞——放过去的话确定键的 UP 会「点击」焦点卡、把应用打开。
        // 离开前台(HOME、系统设置侧边面板、别的应用盖上来)= 移动态取消(M4b spec §0-9)
        cancelMove()
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

    /**
     * 长按菜单项:哪几项由 [cardMenuActions] 按 `ref.kind` 定(应用行六项见 design §2,
     * 输入源行三项见 M4b spec §0-11);这里只管每一项按下去做什么。OPEN/RENAME 的文案与动作
     * 都会再按 `ref.kind` 二次分流(应用 vs 输入源不共用同一套「应用」措辞,也不共用启动方式)。
     */
    private fun cardMenuItems(ref: CardRef): List<MenuItem> = cardMenuActions(ref.kind).mapNotNull { action ->
        when (action) {
            // 文案按 ref.kind 分流(M4b 跟进复审):card_menu_open(_desc) 写死「应用」,
            // 输入源卡不能借用——换成不提「应用」两个字的 card_menu_open_input(_desc)。
            CardAction.OPEN -> {
                val (labelRes, descRes) = if (ref.kind == RowKind.INPUTS) {
                    R.string.card_menu_open_input to R.string.card_menu_open_input_desc
                } else {
                    R.string.card_menu_open to R.string.card_menu_open_desc
                }
                MenuItem(getString(labelRes), getString(descRes)) {
                    closeCardMenu()
                    // 按 ref.kind 分流:输入源卡的 pkg 存的是输入 id,不是包名——同 HomeScreen
                    // 卡片本身点击时的分流(CategoryRow.onClick)一样,不能一律走 Apps.launch。
                    val ok = when (ref.kind) {
                        RowKind.INPUTS -> Inputs.launch(this, ref.pkg)
                        RowKind.APPS -> Apps.launch(this, ref.pkg)
                    }
                    if (!ok) toast(getString(R.string.toast_cant_open_app, ref.label))
                }
            }
            CardAction.UNINSTALL -> MenuItem(getString(R.string.card_menu_uninstall), getString(R.string.card_menu_uninstall_desc)) {
                closeCardMenu()
                val ok = runCatching {
                    startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${ref.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (!ok) toast(getString(R.string.toast_uninstall_failed))
            }
            // 标题(card_menu_rename「修改标题」/「Rename Card」)三种语言都不提「应用」,两种行共用；
            // 说明文字原句提到「应用」,输入源卡换 card_menu_rename_input_desc(M4b 跟进复审)。
            CardAction.RENAME -> MenuItem(
                getString(R.string.card_menu_rename),
                getString(if (ref.kind == RowKind.INPUTS) R.string.card_menu_rename_input_desc else R.string.card_menu_rename_desc),
            ) {
                closeCardMenu(); renameTarget = ref
            }
            CardAction.CHANGE_ICON -> MenuItem(getString(R.string.card_menu_icon), getString(R.string.card_menu_icon_desc)) {
                closeCardMenu()
                // 不再记落点(M7 T4):选择器改成叠在常驻首页之上,首页那棵树不会被移除,
                // `tgtRow`/`tgtIdx` 在 `previewing` 期间冻着,关掉选择器就原样还原到这张卡。
                pickIcon(ref.pkg)
            }
            // M4b spec §0-9:首页原地移动,取代原来的「跳编辑页定位」兜底。关菜单的 focusNonce++ 与移动态的目标
            // 同一次重组生效:还原效果按 moving.pos 把焦点送回这张卡(它此刻就在出发那一格)。
            CardAction.MOVE -> MenuItem(getString(R.string.card_menu_move), getString(R.string.card_menu_move_desc)) {
                closeCardMenu(); startMove(ref)
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
            // 输入源行专属(M4b spec §0-11)。从桌面隐藏这个输入源,能在设置页「布局 → 恢复隐藏的
            // 输入源」一键找回。写盘(tmp → fsync → rename)放 IO 线程,与 REMOVE 同构;时序也同构——
            // 先关菜单让还原效果把焦点落回这张卡(它此刻还在),写完 revision++ 才让那一行变短、
            // 焦点按夹过的列号送到同行邻卡(design §1「行变短时索引夹取」,与卸载/移除同一套机制)。
            // **不能挂 settingsRevision**:那颗只重读 settings.json、明确不重建首页行(见其字段 KDoc),
            // 挂它的话卡片写完盘也不会消失。
            CardAction.HIDE -> MenuItem(getString(R.string.menu_hide_input), getString(R.string.menu_hide_input_desc)) {
                closeCardMenu()
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { HiddenInputs.set(this@MainActivity, ref.pkg, true) }
                    if (ok) {
                        toast(getString(R.string.toast_input_hidden, ref.label))
                        revision++
                    } else {
                        toast(getString(R.string.toast_input_hide_failed))
                    }
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

    /**
     * 设置页「系统屏保 ▸」(M5 spec §3):系统屏保设置页;解析不到(`ActivityNotFoundException`)退到系统设置首页;
     * 两个都打不开才 toast。不检测系统当前选的是不是 UnitedU(spec §8:隐藏设置键,读不可靠)。
     * 回来时 onResume 的 focusNonce++ 让设置页把焦点送回这一行(ON_PAUSE 起冻结)。
     */
    private fun openSystemScreensaverSettings() {
        for (action in listOf(Settings.ACTION_DREAM_SETTINGS, Settings.ACTION_SETTINGS)) {
            val ok = runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) return
        }
        toast(getString(R.string.toast_system_screensaver_unavailable))
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
     * 屏保图库查看器的入口:设置页「待机与屏保 → 屏保图库 ▸」(M5 spec §3)。叠在设置页之上,
     * 关掉后 focusNonce++ 让设置页把焦点接回这一行(设置页 `covered` 期间冻结目标)。
     * 打开时清掉删图的两个量:它们只属于一次图库会话,旧值不能带进新会话(见两个字段的 KDoc)。
     */
    private fun openScreensaverPool() {
        if (Paths.baseOrNull(this) == null) { toast(getString(R.string.toast_storage_not_ready)); return }
        closeMenu()
        poolDeleteTarget = null
        poolFocusedFile = null
        pickerTarget = VIEW_SCREENSAVER_POOL
    }

    /**
     * 图库删图的「删除」键(M5 spec §5)。顺序照 spec:IO 线程删文件 → 播放器重扫 → 图库版本 +1 →
     * 收确认框 → focusNonce++。`galleryVersion++`/`poolDeleteTarget = null`/`focusNonce++` 这三个
     * 赋值中间没有挂起点,总在同一帧落地——这一步不是巧合,是协程顺序执行的必然。真正不确定的是
     * 它们和图库那边的重扫(ImagePicker.kt ScreensaverPoolViewer 的 produceState,在另一条 IO 协程
     * 上跑)谁先谁后——**中间是有一段「谁都不管」的空档的**(fix round 1 之前踩过:焦点漏给背后
     * 盖住的设置页)。现在补上的办法不在这一层,而是让 PickerGrid 的定位效果把 focusRequesters
     * 也编进 key、另配一个焦点看门狗,新列表一到 / 焦点莫名其妙没了都能接住,不再单靠「这三个赋值
     * 同一帧落地」这件事本身。开头比对目标:过期的调用直接忽略。删不掉(文件还在)记日志 + toast
     * (`toast_pool_delete_failed`)——列表按盘上实况重读,那张图留在原处。
     */
    private fun deletePoolImage(file: java.io.File) {
        if (poolDeleteTarget != file) return
        lifecycleScope.launch {
            val gone = withContext(Dispatchers.IO) { file.delete() || !file.exists() }
            if (!gone) {
                android.util.Log.w("UnitedU", "屏保图删不掉: ${file.name}")
                toast(getString(R.string.toast_pool_delete_failed, file.name))
            }
            ScreensaverPlayer.rescan(this@MainActivity)
            galleryVersion++
            poolDeleteTarget = null
            focusNonce++
        }
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
     * 设置页「布局 → 恢复隐藏的输入源」(M4b spec §0-11):一次性清空 hidden-inputs.json。
     * `revision++`(不是 settingsRevision——理由同 [cardMenuItems] 里 HIDE 那支的注释)让首页把
     * 之前隐藏的卡片重新排回输入源行,同时让设置页自己的 hiddenInputs 计数跟着重读、这一行归零后消失
     * (焦点交给 SettingsScreen 现成的看门狗接到相邻行,不需要新写焦点代码)。
     * 没有专门的失败文案:`HiddenInputs.clear` 只在外置存储写失败时才返回 false,与
     * [deletePoolImage] 删不掉时的处理同一个姿势——只记日志,不拿一条用户可能永远不会撞见的
     * 错误路径去换一个没人要求过的新字符串。
     */
    private fun restoreHiddenInputs() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { HiddenInputs.clear(this@MainActivity) }
            if (ok) {
                toast(getString(R.string.toast_inputs_restored))
                revision++
            } else {
                android.util.Log.w("UnitedU", "恢复隐藏的输入源写盘失败")
            }
        }
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
                    // 移动态的返回键正常在 dispatchKeyEvent 里就被截走(DOWN 取消、UP 吞掉),到不了这里;
                    // 这一支只为「返回键绕过 dispatchKeyEvent」的路(预测式返回)兜底,同样是取消。
                    moving != null -> cancelMove()
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
