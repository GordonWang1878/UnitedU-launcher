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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 桌面主界面。这里只管三件事:待机计时、返回键不退出、齿轮菜单的入口。
 * 视觉全在 HomeScreen / AppCard / Clock 里,规格见 docs/DESIGN-custom-launcher.md §4。
 */
private const val PICK_WALLPAPER = "__wallpaper__"
private const val VIEW_SCREENSAVER_POOL = "__screensaver_pool__"
private const val VIEW_HOME_SETTINGS = "__home_settings__"

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

    /** 装了新应用或卸载了应用后,桌面和「添加应用」列表都要能跟上。 */
    private val packageChanges = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) { revision++ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.colorMode = ActivityInfo.COLOR_MODE_HDR
        registerReceiver(
            packageChanges,
            android.content.IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
        )
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
            // 壁纸渲染输入:文件名 + 主题化参数;accent 只在主题化时参与(见 wallpaperSpecOf)。
            // key 用 **presetColors 而不是 themeColors**:跟随壁纸主色时 spec 不带 accent
            // (followColor = true,由 Wallpapers.load 自己取 Palette 再染),所以 spec 不能
            // 依赖异步到达的 wallpaperColors——否则换一张图会先用旧主色渲一遍、取色落地后再渲一遍,
            // 每次轮播两次全量渲染 + 一份永不命中的缓存。
            val wallpaperSpec = remember(homeSettings, presetColors) {
                wallpaperSpecOf(homeSettings, presetColors.accent.toArgb() and 0xFFFFFF)
            }
            val touched = lastInput
            // **编辑界面和菜单开着时不进入待机。**淡出只做在首页那一层,而吞掉唤醒键是
            // Activity 级的 —— 两头不占的结果是:编辑界面画面全亮(看着醒着),
            // 第一下按键却被当唤醒吃掉,症状就是「按了没反应」。
            // 长时间停在这两个界面由电视自己的系统屏保接管(实测存在 DreamActivity)。
            LaunchedEffect(touched, editing, menuOpen, settings) {
                idle = false
                if (editing || menuOpen || settings) return@LaunchedEffect
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
                    onPickIcon = ::pickIcon,
                    onExit = ::leaveEdit,
                    focusNonce = focusNonce,
                    revision = revision,
                    cardsPerRow = homeSettings.cardsPerRow,
                    showTitles = homeSettings.showTitles,
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
                    accent = themeColors.accent,
                    highlight = themeColors.highlight,
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
            if (menuOpen) closeMenu() else { menuFromGear = false; menuOpen = true }
            return true
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

    /** HOME 键的语义是「回到桌面初始状态」,所以要把编辑界面和菜单都收掉。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        leaveEdit()
        leaveSettings()
        closeMenu()
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
        if (editing) { editing = false; revision++ }
    }

    /**
     * 关设置页**只有这一条路**。关掉后 focusNonce++ 让首页重新拿回焦点(与各选择器 onDismiss 同理);
     * revision++ 让首页跟着重读 settings.json——Task G 起首页开始消费 Settings(先接时钟的
     * showDate,E/F/H 陆续接其余字段),复用换布局图那颗计数器,不必再单独维护一份
     * settingsRevision。
     */
    private fun leaveSettings() {
        if (settings) { settings = false; focusNonce++; revision++ }
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
    }

    private fun menuItems() = listOf(
        MenuItem(getString(R.string.menu_edit), getString(R.string.menu_edit_desc)) { editing = true },
        MenuItem(getString(R.string.menu_settings), getString(R.string.menu_settings_desc)) { settings = true },
        MenuItem(getString(R.string.menu_wallpaper), getString(R.string.menu_wallpaper_desc)) { pickWallpaper() },
        MenuItem(getString(R.string.menu_screensaver), getString(R.string.menu_screensaver_desc)) { openScreensaverPool() },
        MenuItem(getString(R.string.menu_system_settings), getString(R.string.menu_system_settings_desc)) { open(Intent(Settings.ACTION_SETTINGS)) },
        MenuItem(getString(R.string.menu_set_default_home), getString(R.string.menu_set_default_home_desc)) { openHomeSettings() },
    )

    private fun pickIcon(pkg: String) {
        if (Paths.baseOrNull(this) == null) { toast(getString(R.string.toast_storage_not_ready)); return }
        pickerTarget = pkg
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
 * **这里只管强调色,不再决定壁纸怎么染**:壁纸那边的主色由 [Wallpapers.load] 在同一趟 IO 里
 * 自己取(`spec.followColor`),两边取色函数同一个,结果一致;分开之后 spec 不再等这个异步值,
 * 一张图只渲一次。
 */
private fun wallpaperThemeColors(ctx: android.content.Context, wallpaperFile: String): ThemeColors? =
    Wallpapers.resolveSource(ctx, wallpaperFile)
        ?.let { Wallpapers.paletteAccent(ctx, it) }
        ?.let { rgb ->
            val accent = androidx.compose.ui.graphics.Color(rgb or 0xFF000000.toInt())
            ThemeColors(accent, highlightFrom(accent))
        }
