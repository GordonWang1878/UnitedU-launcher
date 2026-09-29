package com.uniteduone.launcher

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * 读系统设置 → [SystemUiStatus]。**每一项单独 try**:Android 12 起 targetSdk ≥ 31 的应用读没有
 * `@Readable` 的隐藏键会抛 SecurityException,厂商 ROM 也可能再收紧——一项读不到只让那一项变 null
 * (「系统屏保」摘要里省略那一段;动画缩放那一项读不到则显示「查看」),不连累别的项,更不崩。解析 / 格式化全在 `SystemStatus.kt` 的纯函数里。
 *
 * 在主线程同步调:四次 `Settings.*.getString` 走 SettingsProvider 的进程内缓存(generation tracker),
 * 一次 PackageManager 查应用名,合计亚毫秒到几毫秒。同步读是为了**不让「系统屏保」行先画成空值再跳成真值**,
 * 也不让「其他」组顶上那条动画缩放条件行在打开页面之后才冒出来(行数变化会走一遍焦点夹取,没必要)。
 */
fun readSystemUiStatus(ctx: Context): SystemUiStatus {
    val cr = ctx.contentResolver
    fun <T> safe(what: String, block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        // SecurityException(键不可读)是预期内的,记一笔方便真机排查,不上抛。
        Log.w("UnitedU", "读系统设置 $what 失败: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    val enabled = safe("screensaver_enabled") {
        parseEnabledFlag(Settings.Secure.getString(cr, "screensaver_enabled"))
    }
    val source = safe("screensaver_components") {
        parseDreamComponent(Settings.Secure.getString(cr, "screensaver_components"))?.let { (pkg, cls) ->
            if (pkg == ctx.packageName) DreamSource.Ours
            else DreamSource.Other(dreamLabel(ctx, pkg, cls) ?: pkg)
        }
    }
    val start = safe("screen_off_timeout") {
        timeoutDisplay(Settings.System.getString(cr, Settings.System.SCREEN_OFF_TIMEOUT)?.trim()?.toLongOrNull())
    }
    // R127:隐藏键(没有公开常量),字面量。没设过 → sleepTimeoutDisplay 返回「从不」;抛异常才是读不到(→「查看」)。
    val screenOff = safe("sleep_timeout") { sleepTimeoutDisplay(Settings.Secure.getString(cr, "sleep_timeout")) }
    // 三项动画缩放是公开键(Settings.Global.*_SCALE);**没设过 = 系统默认 1×**(WindowManagerService
    // 的缺省值,文档写明),这与 screensaver_enabled「没设过 = 看厂商 overlay」不同,所以这里 null → 1。
    // 只有抛异常或值不是数字才算读不到。
    fun scale(key: String): Float? = safe(key) {
        val raw = Settings.Global.getString(cr, key) ?: return@safe 1f
        raw.trim().toFloatOrNull()
    }
    // R127b:按候选链 SCREEN_OFF_SETTINGS_PAGES 的解析结果定小字(索尼节能控制面板 / TvSettings 首页 / 别家首页)。
    val where = safe("screen_off_where") { screenOffWhere(ctx) } ?: ScreenOffWhere.GENERIC
    return SystemUiStatus(
        screensaverEnabled = enabled,
        screensaverSource = source,
        screensaverStart = start,
        animatorScale = scale(Settings.Global.ANIMATOR_DURATION_SCALE),
        transitionScale = scale(Settings.Global.TRANSITION_ANIMATION_SCALE),
        windowScale = scale(Settings.Global.WINDOW_ANIMATION_SCALE),
        screenOff = screenOff,
        screenOffWhere = where,
    )
}

private fun screenOffWhere(ctx: Context): ScreenOffWhere {
    val pm = ctx.packageManager
    fun resolve(action: String) = pm.resolveActivity(
        android.content.Intent(action), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
    )?.activityInfo
    if (resolve(SONY_ECO_DASHBOARD_ACTION)?.exported == true) return ScreenOffWhere.SONY_ECO
    return if (resolve("android.settings.SETTINGS")?.packageName == "com.android.tv.settings") ScreenOffWhere.TV_SETTINGS
    else ScreenOffWhere.GENERIC
}

/**
 * 屏保来源的显示名:先取 DreamService 自己的 label(同一个应用可能带好几个 Dream),再退到应用名。
 * 包没装(模拟器的 Google TV 镜像就指着一个没装的 dreamx 包)或拿不到 → null,调用方退到包名。
 */
private fun dreamLabel(ctx: Context, pkg: String, cls: String): String? {
    val pm = ctx.packageManager
    val service = runCatching { pm.getServiceInfo(ComponentName(pkg, cls), 0) }.getOrNull()
    val fromService = service?.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
    if (fromService != null) return fromService
    return runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrNull()
        ?.takeIf { it.isNotBlank() }
}
