package com.uniteduone.launcher

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import java.io.File

/** 设置页 / 引导里「主页键接管」胶囊显示的状态(R162)。 */
enum class HomeKeyStatus { ON, OFF, NOT_RUNNING, RESTRICTED }

/**
 * [enabled] 系统无障碍开关开着;[running] 服务这次开机以来连上过且没断(心跳);[restricted] 受限设置锁着
 * (Android 13+ 用系统安装器侧载的包,见调研 §2.2 #9–#10)。系统开关显示开着却没在跑是 Projectivy 最常见的用户问题,所以分开报。
 */
fun homeKeyStatus(enabled: Boolean, running: Boolean, restricted: Boolean): HomeKeyStatus = when {
    enabled && running -> HomeKeyStatus.ON
    enabled -> HomeKeyStatus.NOT_RUNNING
    restricted -> HomeKeyStatus.RESTRICTED
    else -> HomeKeyStatus.OFF
}

/** 胶囊画不画:UnitedU 不是默认桌面就画;是默认桌面但服务开着也画(得让人能关掉)。A95L(默认桌面、没开)不画。 */
fun showHomeKeyCapsule(isDefaultHome: Boolean, enabled: Boolean): Boolean = !isDefaultHome || enabled

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
 */
object HomeKeyState {
    /** 受限设置的 appop(`AppOpsManager.OPSTR_ACCESS_RESTRICTED_SETTINGS`,API 33;老系统没有这个 op,查询抛异常 → 当不受限)。 */
    private const val OP_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings"

    private fun file(ctx: Context) = File(ctx.filesDir, "homekey.state")

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

    /** 受限设置锁着(自己的 appop 不是 allow)。 */
    fun isRestricted(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(OP_ACCESS_RESTRICTED_SETTINGS, Process.myUid(), ctx.packageName) != AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun status(ctx: Context): HomeKeyStatus =
        homeKeyStatus(isEnabled(ctx), isRunning(read(ctx), bootCount(ctx)) && isBound(ctx), isRestricted(ctx))
}
