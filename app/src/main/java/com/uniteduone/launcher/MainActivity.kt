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
            // 设置页关闭时 leaveSettings() 会让 revision++,这里跟着重读 settings.json,
            // 首页拿到的就是最新设置——复用换布局图那颗计数器,不必再单独维护一份
            // settingsRevision。注意:这里不能显式写 Settings 类型名,本文件已经
            // `import android.provider.Settings`,裸写 Settings 会撞上那个系统类;
            // 靠类型推断绕开,只取用到的字段(showDate)。
            val homeSettings = remember(revision) { SettingsStore.read(this@MainActivity) }
            // 主题色:选中预设的 accent(齿轮)+ highlight(时钟/光晕/行标题)。
            // followWallpaperColor 打开时,accent 改从当前壁纸主色提取、highlight 由它混白推得
            // (与非金预设同一算法);解不出色或没壁纸就回落到预设。壁纸解码放 IO 线程,
            // key 带上 followWallpaperColor 与 revision:换壁纸(handlePick 走 recreate)、开关跟随、
            // 回到设置页都会重跑。preset 路径是纯内存查表,直接同步解析。
            val presetColors = remember(homeSettings.themePresetId) {
                ThemePresets.byId(homeSettings.themePresetId).colors()
            }
            val wallpaperColors by produceState<ThemeColors?>(
                null, homeSettings.followWallpaperColor, revision,
            ) {
                value = if (!homeSettings.followWallpaperColor) null
                else withContext(Dispatchers.IO) { wallpaperThemeColors(this@MainActivity) }
            }
            val themeColors =
                if (homeSettings.followWallpaperColor) wallpaperColors ?: presetColors
                else presetColors
            val touched = lastInput
            // **编辑界面和菜单开着时不进入待机。**淡出只做在首页那一层,而吞掉唤醒键是
            // Activity 级的 —— 两头不占的结果是:编辑界面画面全亮(看着醒着),
            // 第一下按键却被当唤醒吃掉,症状就是「按了没反应」。
            // 长时间停在这两个界面由电视自己的系统屏保接管(实测存在 DreamActivity)。
            val importing = pickerTarget == VIEW_IMPORT
            LaunchedEffect(touched, editing, menuOpen, settings, importing) {
                idle = false
                if (editing || menuOpen || settings || importing) return@LaunchedEffect
                delay(Theme.IdleAfterMs)
                idle = true
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
            Wallpaper(this@MainActivity)
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
                    onPickIcon = ::pickIcon,
                    onExit = ::leaveEdit,
                    focusNonce = focusNonce,
                    revision = revision,
                    cardsPerRow = homeSettings.cardsPerRow,
                )
            } else if (settings) {
                SettingsScreen(
                    onExit = ::leaveSettings,
                    focusNonce = focusNonce,
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
        MenuItem(getString(R.string.menu_import), getString(R.string.menu_import_desc)) { openImport() },
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

    private fun openImport() {
        if (Paths.baseOrNull(this) == null) { toast(getString(R.string.toast_storage_not_ready)); return }
        closeMenu()
        pickerTarget = VIEW_IMPORT
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
            val dest = Paths.wallpaper(this)
            val tmp = java.io.File(dest.parentFile, "wallpaper.tmp")
            val ok = runCatching {
                file.inputStream().use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out); out.flush(); out.fd.sync() }
                }
                check(Apps.isDecodableImage(tmp.absolutePath))
                if (!tmp.renameTo(dest)) { dest.delete(); check(tmp.renameTo(dest)) }
                Paths.wallpaperPng(this).delete()
            }.isSuccess
            tmp.delete()
            toast(getString(if (ok) R.string.toast_wallpaper_changed else R.string.toast_invalid_image))
            if (ok) recreate()
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
 * followWallpaperColor 打开时,从当前壁纸主色推导四处强调色。**必须在 IO 线程调用**:
 * 会解码一张缩略图(320×180 量级,够 Palette 取色又不占内存)并跑 [androidx.palette.graphics.Palette]。
 *
 * 取色优先级:vibrant(有活力的主色)→ 落空再用 dominant(占面积最大的色)。
 * accent 用取到的色,highlight 由 [highlightFrom] 混白 55% 推得 —— 与非金预设 highlight 同一手法。
 * 任何一步落空(没壁纸、解不出、Palette 抽不到色)返回 null,调用方回落到选中预设,绝不崩、绝不留黑。
 *
 * 壁纸文件与解码方式跟 [Wallpaper] 一致(Paths.wallpaper / wallpaperPng + Apps.decodeScaled),
 * 但这里用默认 ARGB_8888 而非 RGBA_F16:Palette 不吃 F16。
 */
private fun wallpaperThemeColors(ctx: android.content.Context): ThemeColors? {
    val f = listOf(Paths.wallpaper(ctx), Paths.wallpaperPng(ctx))
        .firstOrNull { it.exists() } ?: return null
    val bmp = runCatching { Apps.decodeScaled(f.absolutePath, 320, 180) }.getOrNull() ?: return null
    val palette = runCatching { androidx.palette.graphics.Palette.from(bmp).generate() }.getOrNull()
        ?: return null
    val rgb = palette.getVibrantColor(0).takeIf { it != 0 }
        ?: palette.getDominantColor(0).takeIf { it != 0 }
        ?: return null
    val accent = androidx.compose.ui.graphics.Color(rgb)
    return ThemeColors(accent, highlightFrom(accent))
}
