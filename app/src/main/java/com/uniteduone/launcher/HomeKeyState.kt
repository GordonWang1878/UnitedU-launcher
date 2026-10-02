package com.uniteduone.launcher

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import java.io.File

/** 设置页 / 引导里「主页键接管」胶囊显示的状态(R162)。 */
enum class HomeKeyStatus { ON, OFF, NOT_RUNNING, RESTRICTED }

/**
 * [enabled] 系统无障碍开关开着;[running] 服务这次开机以来连上过且没断(心跳),**而且**系统此刻真的绑定着它;[restricted] 受限设置锁着
 * (Android 13+ 用系统安装器侧载的包,见调研 §2.2 #9–#10;应用读不到锁本身,按安装来源推断,见 [HomeKeyState.isRestricted])。
 * 系统开关显示开着却没在跑是 Projectivy 最常见的用户问题,所以分开报。
 */
fun homeKeyStatus(enabled: Boolean, running: Boolean, restricted: Boolean): HomeKeyStatus = when {
    enabled && running -> HomeKeyStatus.ON
    enabled -> HomeKeyStatus.NOT_RUNNING
    restricted -> HomeKeyStatus.RESTRICTED
    else -> HomeKeyStatus.OFF
}

/** 胶囊画不画:UnitedU 不是默认桌面就画;是默认桌面但服务开着也画(得让人能关掉)。A95L(默认桌面、没开)不画。 */
fun showHomeKeyCapsule(isDefaultHome: Boolean, enabled: Boolean): Boolean = !isDefaultHome || enabled

/**
 * 受限设置(Android 13+)按**安装来源**推断:系统在安装时,对用系统安装器装的本地 / 下载文件(`PACKAGE_SOURCE_LOCAL_FILE` /
 * `DOWNLOADED_FILE`)上锁,adb(1)/ 商店(2)/ 会话安装(0)不锁;Android 12 及以下没有这道锁。纯函数,[source] 是
 * `InstallSourceInfo.getPackageSource()` 的值。**只看当前来源不够**:本应用的会话更新会把来源改回 0 而锁还在——见 [HomeKeyState.isRestricted] 的记号。
 */
fun restrictedBySource(sdk: Int, source: Int): Boolean =
    sdk >= Build.VERSION_CODES.TIRAMISU &&
        (source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)

/** 心跳文件的内容:`connected bootCount`。 */
data class HomeKeyHeartbeat(val connected: Boolean, val bootCount: Int)

fun formatHeartbeat(h: HomeKeyHeartbeat): String = "${if (h.connected) 1 else 0} ${h.bootCount}"

fun parseHeartbeat(text: String?): HomeKeyHeartbeat? {
    val parts = text?.trim()?.split(' ')?.takeIf { it.size == 2 } ?: return null
    val connected = parts[0].toIntOrNull() ?: return null
    if (connected !in 0..1) return null
    val boot = parts[1].toIntOrNull() ?: return null
    return HomeKeyHeartbeat(connected == 1, boot)
}

/** 「在运行」= 这次开机连上过且没断。 */
fun isRunning(h: HomeKeyHeartbeat?, bootCount: Int): Boolean = h != null && h.connected && h.bootCount == bootCount

/** Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 里有没有本包。`raw` 是冒号分隔的组件列表 (`pkg/component:pkg2/component2`)。 */
fun enabledServiceSetting(raw: String?, pkg: String): Boolean {
    if (raw.isNullOrBlank()) return false
    return raw.split(':').any { entry ->
        val pkgPart = entry.trim().substringBefore('/').trim()
        pkgPart == pkg
    }
}

/**
 * 心跳文件读写与系统状态查询。服务进程写、桌面进程读——跨进程,所以不用 SharedPreferences(它按进程缓存,另一个进程的写入看不到)。
 * 只有服务一个写者,原子写;读失败一律当「没有心跳」。
 * 例外:「见过受限」的记号([isRestricted])只有主进程(设置页 / 引导 / MainActivity)读写,服务进程不碰,用单进程的 SharedPreferences 就够。
 */
object HomeKeyState {
    private const val MARKS_FILE = "homekey"
    private const val KEY_RESTRICTED_SEEN = "restrictedSeen"

    private fun file(ctx: Context) = File(ctx.filesDir, "homekey.state")

    /** 记号文件;写入一律 `commit()`(同 [RelaunchMarks]:进程随时可能被更新整个杀掉,异步写可能来不及落盘)。 */
    private fun marks(ctx: Context) = ctx.getSharedPreferences(MARKS_FILE, Context.MODE_PRIVATE)

    /** 读不到时两边都是 -1,等于不按开机计数把关——宁可少报一次「没在运行」。 */
    fun bootCount(ctx: Context): Int =
        runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    fun write(ctx: Context, connected: Boolean) {
        val text = formatHeartbeat(HomeKeyHeartbeat(connected, bootCount(ctx)))
        runCatching {
            val ok = writeFileAtomically(file(ctx)) { it.write(text.toByteArray()) }
            if (!ok) {
                Log.w("UnitedU", "homekey heartbeat write returned false")
            }
        }.onFailure { e ->
            Log.w("UnitedU", "homekey heartbeat write failed", e)
        }
    }

    fun read(ctx: Context): HomeKeyHeartbeat? = runCatching { parseHeartbeat(file(ctx).readText()) }.getOrNull()

    /** 系统无障碍开关(Settings 里那一项)。 */
    fun isEnabled(ctx: Context): Boolean = runCatching {
        val raw = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        enabledServiceSetting(raw, ctx.packageName)
    }.getOrDefault(false)

    /** 系统此刻真的绑定着我们的服务(getEnabledAccessibilityServiceList 返回的是已绑定的,不是开关)。 */
    fun isBound(ctx: Context): Boolean = runCatching {
        val am = ctx.getSystemService(AccessibilityManager::class.java) ?: return false
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == ctx.packageName }
    }.getOrDefault(false)

    /**
     * 受限设置(Android 13+)锁不锁:系统在**安装时**按来源决定——用系统安装器装的本地 / 下载文件才上锁,adb / 商店 / 会话安装不锁
     * (判据见 [restrictedBySource])。锁本身(appop `ACCESS_RESTRICTED_SETTINGS`)应用读不到(要 MANAGE_APPOPS,
     * 2026-10-02 模拟器实测抛 SecurityException),所以按来源推断。
     *
     * **见过一次就记住**:本应用的会话更新(自我更新走会话 API)会把 `packageSource` 改回 0,而锁(appop deny)还在——只看当前来源就瞎了。
     * 所以推断为真的那一刻写一个记号(`restrictedSeen`),之后记号在就一直算受限;[status] 在开关开着的那一刻清掉它(开得起来 = 锁显然已经开了)。
     * 用户已用 adb 解锁但还没打开服务时,这里会一直报「不允许」,直到用户开过一次服务(受限时胶囊照样带去无障碍页,见 `openHomeKeySettings`)——
     * 但清记号只对来源已被会话更新改回 0 的安装管用;来源仍是 3 / 4 的安装,关掉开关后记号又会从来源重新点亮(已知误报,见 [status])。
     * Android 13 以下没有这道锁 → false;读失败一律当不受限。
     */
    fun isRestricted(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val prefs = marks(ctx)
        if (prefs.getBoolean(KEY_RESTRICTED_SEEN, false)) return true
        val source = ctx.packageManager.getInstallSourceInfo(ctx.packageName).packageSource
        restrictedBySource(Build.VERSION.SDK_INT, source).also { if (it) prefs.edit().putBoolean(KEY_RESTRICTED_SEEN, true).commit() }
    }.getOrDefault(false)

    /** 开关开着的那一刻清「见过受限」的记号。没有记号时什么都不写(不白落一次盘)。 */
    private fun clearRestrictedMark(ctx: Context) {
        runCatching {
            val prefs = marks(ctx)
            if (prefs.getBoolean(KEY_RESTRICTED_SEEN, false)) prefs.edit().remove(KEY_RESTRICTED_SEEN).commit()
        }
    }

    fun status(ctx: Context): HomeKeyStatus {
        val enabled = isEnabled(ctx)
        // 开关开着 = 锁显然已经开了:清记号。注意这只对「来源已被会话更新改回 0」的安装有意义——记号是它唯一的依据,清掉后关掉开关
        // 回到「未开启」;来源仍是文件(3 / 4)的安装,关掉开关后 [isRestricted] 会从来源把记号重新点亮、小字回到「不允许」
        // (已知误报:按钮照样带去无障碍页,不是死路)。受限只在没开时才有意义([homeKeyStatus]),开着时不必再推断。
        if (enabled) clearRestrictedMark(ctx)
        return homeKeyStatus(enabled, isRunning(read(ctx), bootCount(ctx)) && isBound(ctx), !enabled && isRestricted(ctx))
    }
}

/** 胶囊下的状态小字。 */
fun homeKeyStatusRes(s: HomeKeyStatus): Int = when (s) {
    HomeKeyStatus.ON -> R.string.homekey_state_on
    HomeKeyStatus.OFF -> R.string.homekey_state_off
    HomeKeyStatus.NOT_RUNNING -> R.string.homekey_state_not_running
    HomeKeyStatus.RESTRICTED -> R.string.homekey_state_restricted
}

/** 左侧说明(光标在这颗胶囊时;引导第 3 步直接画在当前桌面下面)。 */
fun homeKeyNoteRes(s: HomeKeyStatus): Int = when (s) {
    HomeKeyStatus.ON -> R.string.homekey_note_on
    HomeKeyStatus.OFF -> R.string.homekey_note_off
    HomeKeyStatus.NOT_RUNNING -> R.string.homekey_note_not_running
    HomeKeyStatus.RESTRICTED -> R.string.homekey_note_restricted
}
