package com.uniteduone.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.SoundEffectConstants
import android.widget.Toast
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

class MainActivity : ComponentActivity() {

    private var lastInput by mutableStateOf(System.currentTimeMillis())
    /** 焦点自救计数:界面报告「整棵树都没有焦点」时 +1,让它重新请求。 */
    private var focusNonce by mutableStateOf(0)
    private var menuOpen by mutableStateOf(false)
    private var editing by mutableStateOf(false)
    /** UnitedU 设置页浮层是否打开(与 [editing] 同构:全屏替换首页那一层)。 */
    private var settings by mutableStateOf(false)
    /** 换过图/改过布局后 +1,用来强制界面重新读取 */
    private var revision by mutableStateOf(0)
    /**
     * 只重读 settings.json、**不重建首页行**的计数器。壁纸选图 / 轮播 / 滑块实时预览走它:
     * 这些事每 5 分钟就来一次,若走 revision 会连 layout.json 与全部卡片图一起重读一遍。
     */
    private var settingsRevision by mutableStateOf(0)
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
    /** 首页当前聚焦的卡(HomeScreen 上报);长按确定键时据此弹菜单。 */
    private var focusedCard by mutableStateOf<CardRef?>(null)
    /** 长按菜单开着的那张卡;null = 没开。 */
    private var cardMenu by mutableStateOf<CardRef?>(null)
    /** 「修改标题」对话框(Task 5 接线)。 */
    private var renameTarget by mutableStateOf<CardRef?>(null)
    /** 「移动位置」兜底:进编辑页时定位到这张卡。**(layout.json 行号, 包名)** ——
     *  列号不能带:编辑页按 layout.json 排,里面还有装不到的包占位,渲染列号对不上。 */
    private var editTarget by mutableStateOf<Pair<Int, String>?>(null)
    /** 图片选择器关掉、首页重新组合时,把焦点记忆种回这张卡 (渲染行, 列)。
     *  选择器会把首页整棵树移除,`remember` 的焦点记忆一并没了,不种就落回第一张卡。 */
    private var homeInitialTarget by mutableStateOf<Pair<Int, Int>?>(null)
    /** 长按识别:记下那次按压的 downTime,同一次按压之后的事件(含 UP)全吞——clickable 在 UP 才触发,不会顺带启动应用。 */
    private var longPressDownTime = -1L
    private val LONG_PRESS_MS = 600L

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.colorMode = ActivityInfo.COLOR_MODE_HDR
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
        installBackHandler()
        setContent {
            // 菜单项列表不必每次重组都新建,否则整棵树都不可跳过
            val menu = remember { menuItems() }
            // 设置页关闭时 leaveSettings() 会让 revision++,壁纸选图 / 轮播 / 滑块预览走的是
            // 专用的 settingsRevision(见其字段 KDoc,只重读 settings、不重建首页行)——
            // 两颗计数器都能让这里重读 settings.json,首页拿到的就是最新设置。注意:
            // 这里不能显式写 Settings 类型名,本文件已经 `import android.provider.Settings`,
            // 裸写 Settings 会撞上那个系统类;靠类型推断绕开,只取用到的字段(showDate)。
            val homeSettings = remember(revision, settingsRevision) { SettingsStore.read(this@MainActivity) }
            // 主题色:选中预设的 accent(齿轮)+ highlight(时钟/光晕/行标题)。
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
            val wallpaperSpec = remember(homeSettings) { wallpaperSpecOf(homeSettings) }
            val touched = lastInput
            // **编辑界面和菜单开着时不进入待机。**淡出只做在首页那一层,而吞掉唤醒键是
            // Activity 级的 —— 两头不占的结果是:编辑界面画面全亮(看着醒着),
            // 第一下按键却被当唤醒吃掉,症状就是「按了没反应」。
            // 长时间停在这两个界面由电视自己的系统屏保接管(实测存在 DreamActivity)。
            val importing = pickerTarget == VIEW_IMPORT
            // 长按卡片菜单与「修改标题」对话框同理(终审 Important #3):输入法显示着时每个按键都被它先吃掉,
            // 根本到不了 dispatchKeyEvent,lastInput 在打字期间不会刷新;不让路的话三分钟后卡片淡出、
            // 屏保从蒙版后面渐入,下一个按键还被当唤醒吞掉。与 menuOpen 完全同一处理:既是 key 也是守卫(铁律 6)。
            val homeOverlay = cardMenu != null || renameTarget != null
            LaunchedEffect(touched, editing, menuOpen, settings, importing, homeOverlay) {
                idle = false
                if (editing || menuOpen || settings || importing || homeOverlay) return@LaunchedEffect
                delay(Theme.IdleAfterMs)
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
            Box(
                Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black)
            ) {
            // prepare() 在首启/升级那一趟会往 settings.json 写 wallpaperFile,而 homeSettings
            // 是在此之前读的;不重读的话,从 M2 升上来、开着「跟随壁纸主色」的用户整个首次会话
            // 都看不到壁纸主色(见 Wallpapers.prepare 的 KDoc)。
            Wallpaper(this@MainActivity, wallpaperSpec, onSettingsChanged = { settingsRevision++ })
            Screensaver(this@MainActivity, idle)
            val pt = pickerTarget
            if (pt == PICK_WALLPAPER) {
                WallpaperPicker(
                    directory = Paths.wallpaperLibrary(this@MainActivity),
                    title = stringResource(R.string.picker_wallpaper_title),
                    nonce = focusNonce,
                    onSelect = { file -> handlePick(file) },
                    onDismiss = { pickerTarget = null; focusNonce++ },
                )
            } else if (pt == VIEW_SCREENSAVER_POOL) {
                ScreensaverPoolViewer(
                    directory = Paths.screensaverLibrary(this@MainActivity),
                    nonce = focusNonce,
                    onDismiss = { pickerTarget = null; focusNonce++ },
                )
            } else if (pt == VIEW_IMPORT) {
                ImportScreen(
                    onExit = { pickerTarget = null; focusNonce++ },
                    focusNonce = focusNonce,
                )
            } else if (pt == VIEW_HOME_SETTINGS) {
                val pm = packageManager
                val info = remember(revision) {
                    pm.resolveActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                        PackageManager.MATCH_DEFAULT_ONLY,
                    )
                }
                val unknownAppLabel = stringResource(R.string.home_settings_unknown)
                HomeSettingsCard(
                    currentLabel = remember(info) { info?.loadLabel(pm)?.toString() ?: unknownAppLabel },
                    currentPkg = remember(info) { info?.activityInfo?.packageName },
                    onOpenSystem = { switchHome() },
                    onDismiss = { pickerTarget = null; focusNonce++ },
                    nonce = focusNonce,
                )
            } else if (pt != null) {
                IconPicker(
                    directory = Paths.cardLibrary(this@MainActivity),
                    originalIcon = remember(pt) { Apps.originalIcon(this@MainActivity, pt) },
                    nonce = focusNonce,
                    onSelect = { file -> handlePick(file) },
                    onRestoreOriginal = { restoreOriginalIcon(pt) },
                    onDismiss = { pickerTarget = null; focusNonce++ },
                )
            } else if (editing) {
                EditScreen(
                    onPickIcon = { pickIcon(it) },
                    onExit = ::leaveEdit,
                    focusNonce = focusNonce,
                    revision = revision,
                    cardsPerRow = homeSettings.cardsPerRow,
                    showTitles = homeSettings.showTitles,
                    initialTarget = editTarget,
                )
            } else if (settings) {
                SettingsScreen(
                    onExit = ::leaveSettings,
                    focusNonce = focusNonce,
                    onWallpaperParamsChanged = { settingsRevision++ },
                )
            } else {
                HomeScreen(
                    idle = idle,
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
                    newAppsSeenAt = homeSettings.newAppsSeenAt,
                    accent = themeColors.accent,
                    highlight = themeColors.highlight,
                    onFocusedCard = { focusedCard = it },
                    cardMenu = cardMenu,
                    cardMenuItems = remember(cardMenu) { cardMenu?.let { cardMenuItems(it) } ?: emptyList() },
                    onCardMenuDismiss = ::closeCardMenu,
                    renameTarget = renameTarget,
                    onRenameSave = ::onRenameSave,
                    onRenameCancel = { renameTarget = null; focusNonce++ },
                    initialTarget = homeInitialTarget,
                    onInitialTargetConsumed = { homeInitialTarget = null },
                )
            }
            }
        }
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
            if (pickerTarget != null) return true
            if (editing) { leaveEdit(); return true }
            if (settings) { leaveSettings(); return true }
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
            val homeBare = !editing && !settings && !menuOpen && pickerTarget == null && cardMenu == null && renameTarget == null
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
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        leaveEdit()
        leaveSettings()
        closeMenu()
        closeCardMenu()
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
        // homeInitialTarget 也要清:编辑页退出照旧把焦点交给首页自己的还原逻辑,
        // 留着旧种子会把焦点按在「上次换图那张卡」上,那不是这条路该有的行为。
        if (editing) { editing = false; revision++; editTarget = null; homeInitialTarget = null }
    }

    /**
     * 关设置页**只有这一条路**。关掉后 focusNonce++ 让首页重新拿回焦点(与各选择器 onDismiss 同理);
     * revision++ 让首页跟着重读 settings.json——Task G 起首页开始消费 Settings(先接时钟的
     * showDate,E/F/H 陆续接其余字段),复用换布局图那颗计数器,不必再单独维护一份
     * settingsRevision。
     */
    private fun leaveSettings() {
        if (settings) { settings = false; focusNonce++; revision++; homeInitialTarget = null }
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

    private fun menuItems() = listOf(
        MenuItem(getString(R.string.menu_edit), getString(R.string.menu_edit_desc)) { editing = true },
        MenuItem(getString(R.string.menu_settings), getString(R.string.menu_settings_desc)) { settings = true },
        MenuItem(getString(R.string.menu_import), getString(R.string.menu_import_desc)) { openImport() },
        MenuItem(getString(R.string.menu_wallpaper), getString(R.string.menu_wallpaper_desc)) { pickWallpaper() },
        MenuItem(getString(R.string.menu_screensaver), getString(R.string.menu_screensaver_desc)) { openScreensaverPool() },
        MenuItem(getString(R.string.menu_system_settings), getString(R.string.menu_system_settings_desc)) { open(Intent(Settings.ACTION_SETTINGS)) },
        MenuItem(getString(R.string.menu_set_default_home), getString(R.string.menu_set_default_home_desc)) { openHomeSettings() },
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
                // 选择器会把首页整棵树移除,焦点记忆随 remember 一起没;把落点记下来,
                // 回来时 HomeScreen 用它当初值(**渲染坐标**,种的是焦点不是盘上的位置)。
                // **只在选择器真的打开时才种**(终审 Minor #6):存储没就绪时 pickIcon 只弹 toast、首页原地不动,
                // 提前种下的坐标会一直留到下一次从别的浮层回来时误种——那几条路焦点原本在齿轮上。
                if (pickIcon(ref.pkg)) homeInitialTarget = ref.rowIndex to ref.colIndex
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
        // 从齿轮菜单进来,焦点原本在齿轮上——不清的话会被 CHANGE_ICON 留下的旧种子带偏(T4 review item A)。
        homeInitialTarget = null
        if (Paths.baseOrNull(this) == null) { toast(getString(R.string.toast_storage_not_ready)); return }
        closeMenu()
        pickerTarget = PICK_WALLPAPER
    }

    private fun openImport() {
        homeInitialTarget = null
        if (Paths.baseOrNull(this) == null) { toast(getString(R.string.toast_storage_not_ready)); return }
        closeMenu()
        pickerTarget = VIEW_IMPORT
    }

    private fun openScreensaverPool() {
        homeInitialTarget = null
        if (Paths.baseOrNull(this) == null) { toast(getString(R.string.toast_storage_not_ready)); return }
        closeMenu()
        pickerTarget = VIEW_SCREENSAVER_POOL
    }

    private fun openHomeSettings() {
        homeInitialTarget = null
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
                    menuOpen -> closeMenu()
                    // 与 menuOpen 同理的兜底:GearMenu 自带的 BackHandler 组合时挂得更晚、正常会先接管,
                    // 但这一层不能是空的 —— 万一那条路没接住,返回键就会落进「桌面根状态什么都不做」,
                    // 菜单留在屏幕上而按键毫无反应。
                    cardMenu != null -> closeCardMenu()
                    // 同理:TitleDialog 自带的 BackHandler 正常会先接管,这里是同一种兜底
                    // (T5 review Important #4)——返回键在对话框开着时绝不能是空操作。
                    renameTarget != null -> { renameTarget = null; focusNonce++ }
                    editing -> leaveEdit()
                    settings -> leaveSettings()
                    // 桌面根状态:什么都不做,绝不 finish
                }
            }
        })
    }
}

/**
 * followWallpaperColor 打开时,从当前壁纸主色推导**四处强调色**(齿轮 / 时钟 / 光晕 / 行标题)。
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
            val accent = androidx.compose.ui.graphics.Color(rgb or 0xFF000000.toInt())
            ThemeColors(accent, highlightFrom(accent))
        }
