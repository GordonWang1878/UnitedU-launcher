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
    // R127c:从电视自己的设置应用里读「关闭屏幕」那一页的菜单路径(各家叫法不同),读不到 → 通用提示。
    val path = safe("screen_off_path") { screenOffPath(ctx) }
    // R132:当前默认桌面。解析到系统包(ResolverActivity,「选择打开方式」)= 还没选默认桌面。
    val home = safe("default_home") {
        val pm = ctx.packageManager
        val info = pm.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )
        val pkg = info?.activityInfo?.packageName
        if (pkg == null || pkg == "android") DefaultHome.NotSet else DefaultHome.App(info.loadLabel(pm).toString())
    }
    return SystemUiStatus(
        screensaverEnabled = enabled,
        screensaverSource = source,
        screensaverStart = start,
        animatorScale = scale(Settings.Global.ANIMATOR_DURATION_SCALE),
        transitionScale = scale(Settings.Global.TRANSITION_ANIMATION_SCALE),
        windowScale = scale(Settings.Global.WINDOW_ANIMATION_SCALE),
        screenOff = screenOff,
        screenOffPath = path,
        defaultHome = home,
    )
}

/**
 * R127c:打开「系统设置」会落到哪个应用,就去那个应用的资源里找「关闭屏幕」那一页的菜单名(电视当前语言)。
 * 只认 TvSettings 系(AOSP / Google TV / 索尼都是):偏好页 xml 里 `android:fragment` 以 `EnergySaverFragment`
 * 结尾的那一项就是它——索尼标题「自动关闭」、Google TV「关机定时器」,不写死。先找 `power_and_energy`(新布局:
 * 系统 → 电源和能耗 → X),再找 `device`(老布局:系统 → X)。任何一步对不上 → null(小字退通用提示)。
 */
private fun screenOffPath(ctx: Context): List<String>? {
    val pm = ctx.packageManager
    val pkg = pm.resolveActivity(
        android.content.Intent("android.settings.SETTINGS"), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
    )?.activityInfo?.packageName ?: return null
    val res = pm.getResourcesForApplication(pkg)
    fun str(name: String): String? =
        res.getIdentifier(name, "string", pkg).takeIf { it != 0 }?.let { res.getString(it) }?.takeIf { it.isNotBlank() }
    val ns = "http://schemas.android.com/apk/res/android"
    fun energySaverTitle(xml: String): String? {
        val id = res.getIdentifier(xml, "xml", pkg).takeIf { it != 0 } ?: return null
        val p = res.getXml(id)
        try {
            while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (p.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
                if (p.getAttributeValue(ns, "fragment")?.endsWith(".EnergySaverFragment") != true) continue
                val t = p.getAttributeResourceValue(ns, "title", 0)
                return (if (t != 0) res.getString(t) else p.getAttributeValue(ns, "title"))?.takeIf { it.isNotBlank() }
            }
        } finally {
            p.close()
        }
        return null
    }
    val system = str("device_pref_category_title") ?: return null
    energySaverTitle("power_and_energy")?.let { item -> str("power_and_energy")?.let { return listOf(system, it, item) } }
    energySaverTitle("device")?.let { return listOf(system, it) }
    return null
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
