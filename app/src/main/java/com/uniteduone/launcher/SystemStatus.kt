package com.uniteduone.launcher

import java.math.BigDecimal

/**
 * 设置页「系统」组(ui-pending #16)要显示的**系统**设置快照:系统屏保开关 / 来源 / 启动时间、三项动画缩放。
 *
 * 只读。我们不申请 WRITE_SECURE_SETTINGS / WRITE_SETTINGS(Gordon 2026-09-23 定:直接改写要 adb 授权,
 * 而且会覆盖用户有意调的无障碍动画设置),每一项只显示当前值 + 一键跳系统对应页。
 *
 * **每个字段 null = 读不到**(键没设过、新系统不让第三方读非公开键而抛 SecurityException、值格式不认识)。
 * 读不到就在界面上只写「查看」,**不猜默认值**——`screensaver_enabled` 没设过时系统到底开没开,
 * 取决于厂商 overlay 的 `config_dreamsEnabledByDefault`,我们读不到就不说。
 *
 * 这个文件一行 Android 都不碰:解析与格式化全是纯函数,在 JVM 单测里钉死([SystemStatusTest]);
 * 真正去 ContentResolver 读的那一小段在 [readSystemUiStatus](`SystemStatusReader.kt`)里。
 */
data class SystemUiStatus(
    /** `Settings.Secure.screensaver_enabled`:true = 开。 */
    val screensaverEnabled: Boolean? = null,
    /** `Settings.Secure.screensaver_components` 的第一项,已经解析成来源(见 [DreamSource])。 */
    val screensaverSource: DreamSource? = null,
    /** `Settings.System.SCREEN_OFF_TIMEOUT`(Android TV 上就是「屏保启动时间」),已格式化成显示单位。 */
    val screensaverStart: TimeoutDisplay? = null,
    /** `Settings.Global.animator_duration_scale`:Compose 动画(焦点放大、整页位移)吃的就是它。 */
    val animatorScale: Float? = null,
    /** `Settings.Global.transition_animation_scale`:只影响 Activity 切换。 */
    val transitionScale: Float? = null,
    /** `Settings.Global.window_animation_scale`:只影响窗口进出。 */
    val windowScale: Float? = null,
) {
    companion object {
        /** 一项都没读到(读取器还没跑、或者整块被系统挡掉)。 */
        val UNKNOWN = SystemUiStatus()
    }
}

/** 系统屏保来源。[Ours] = 选的就是 UnitedU 自己的 Dream;[Other] 的 [label] 是应用名,拿不到应用名时是包名。 */
sealed interface DreamSource {
    data object Ours : DreamSource
    data class Other(val label: String) : DreamSource
}

/** 屏保启动时间的显示单位。整小时显示小时、整分钟显示分钟,否则显示秒;[Never] = 系统的「从不」。 */
sealed interface TimeoutDisplay {
    data object Never : TimeoutDisplay
    data class Seconds(val n: Int) : TimeoutDisplay
    data class Minutes(val n: Int) : TimeoutDisplay
    data class Hours(val n: Int) : TimeoutDisplay
}

/**
 * 「系统动画缩放」提示行要说什么。null = 三项都是 1×(或都读不到之外的「正常」)——**不出这一行**。
 * - [Animator]:`animator_duration_scale` ≠ 1,它直接改变我们界面动画的速度,优先说它;
 * - [WindowOnly]:动画程序是 1×,只有窗口 / 过渡 ≠ 1,只影响打开 / 切换应用;
 * - [Unreadable]:动画程序那一项读不到——不知道是不是 1×,只放「查看」+ 跳转。
 */
sealed interface AnimScaleNotice {
    data class Animator(val scale: Float) : AnimScaleNotice
    data class WindowOnly(val window: Float, val transition: Float) : AnimScaleNotice
    data object Unreadable : AnimScaleNotice
}

/** `screensaver_enabled` 的原始字符串 → 开关。只认 "0" / "1"(系统就写这两个),别的一律算读不到。 */
internal fun parseEnabledFlag(raw: String?): Boolean? = when (raw?.trim()) {
    "1" -> true
    "0" -> false
    else -> null
}

/**
 * `screensaver_components` → (包名, 完整类名)。系统存的是逗号分隔的 `ComponentName.flattenToString()`,
 * 多个时第一个是生效的那个;类名以「.」开头是相对包名的简写(`com.x/.Dream` = `com.x/com.x.Dream`)。
 * 空串 / 格式不对 → null(读不到,不猜)。不用 `ComponentName.unflattenFromString`:那是 Android 类,
 * 放进来这个函数就不能在 JVM 单测里跑了,而它做的事就是下面这几行。
 */
internal fun parseDreamComponent(raw: String?): Pair<String, String>? {
    val first = raw?.split(',')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
    val slash = first.indexOf('/')
    if (slash <= 0 || slash == first.lastIndex) return null
    val pkg = first.substring(0, slash)
    val cls = first.substring(slash + 1).let { if (it.startsWith(".")) pkg + it else it }
    return pkg to cls
}

/**
 * `SCREEN_OFF_TIMEOUT`(毫秒)→ 显示单位。Android TV 的「从不」存的是 `Int.MAX_VALUE`;
 * ≤ 0 不是系统会写的值,当读不到。
 */
internal fun timeoutDisplay(ms: Long?): TimeoutDisplay? {
    if (ms == null || ms <= 0) return null
    if (ms >= Int.MAX_VALUE) return TimeoutDisplay.Never
    return when {
        ms % 3_600_000L == 0L -> TimeoutDisplay.Hours((ms / 3_600_000L).toInt())
        ms % 60_000L == 0L -> TimeoutDisplay.Minutes((ms / 60_000L).toInt())
        // 不是整分钟(TvHome 时期手设过 63 分钟这种值 = 3_780_000,仍是整分钟;这里兜的是秒级的怪值),四舍五入到秒。
        else -> TimeoutDisplay.Seconds(((ms + 500) / 1000).toInt().coerceAtLeast(1))
    }
}

/** 缩放值是不是 1×。浮点从字符串 "1.0" 解析来,用小容差比,不用 `==`。 */
internal fun isUnitScale(scale: Float): Boolean = kotlin.math.abs(scale - 1f) < 0.001f

/**
 * 三项缩放 → 要不要出提示行、说什么(规则见 [AnimScaleNotice])。
 * 窗口 / 过渡读不到时按 1× 算:它们只影响应用切换,不值得为「不知道」常驻一行;
 * 动画程序那一项读不到则要出「查看」——它决定我们界面动画的速度,读不到就不能假装它是 1×。
 * (「读不到」指抛异常 / 值不是数字;键**没设过**是系统默认 1×,读取器已经把它换成 1f 传进来。)
 */
internal fun animScaleNotice(animator: Float?, transition: Float?, window: Float?): AnimScaleNotice? {
    if (animator == null) return AnimScaleNotice.Unreadable
    if (!isUnitScale(animator)) return AnimScaleNotice.Animator(animator)
    val w = window ?: 1f
    val t = transition ?: 1f
    if (!isUnitScale(w) || !isUnitScale(t)) return AnimScaleNotice.WindowOnly(w, t)
    return null
}

/**
 * 缩放值显示成最短的十进制:1.25 → "1.25",0.5 → "0.5",10 → "10",0 → "0"。
 * 经 `toString()` 再进 BigDecimal:直接 `BigDecimal(1.25f)` 会带出二进制尾巴。
 */
internal fun formatScale(scale: Float): String =
    BigDecimal(scale.toString()).stripTrailingZeros().toPlainString()

/**
 * 跳系统页的一个候选:隐式 [action],或显式组件([pkg], [cls])。用字符串字面量而不是 `Settings.ACTION_*`,
 * 只为让这个文件保持不碰 Android(字面量与常量逐字相同,注释里标了对应的常量名)。
 */
data class SystemPage(val action: String? = null, val pkg: String? = null, val cls: String? = null)

/**
 * 系统屏保设置页的候选链,按顺序试(MainActivity.openSystemPage):解析不到、启动抛异常、或**启动了却在
 * [SYSTEM_PAGE_BOUNCE_MS] 内弹回来**,都换下一个。弹回检测是 2026-09-23 在 `unitedu-gtv` AVD 上实测补的:
 * 1. `ACTION_DREAM_SETTINGS`:AOSP / 手机的标准入口;这台 Google TV 镜像上**解析不到**(M5 起就记过);
 * 2. TvSettings 的 `DaydreamActivity`(显式):镜像里有、exported、`am start` 报成功,但**当场自己 finish**,
 *    什么都不显示——Google TV 的 TvSettings 把屏保页换成了下一项,这个类只剩空壳。不做弹回检测的话,
 *    用户按下去什么都没发生;
 * 3. `com.google.android.tv.settings.ambient`:Google TV 真正的屏保(Ambient)页 `AmbientActivity`。
 *    这台 AVD 上它会崩(`screensaver_components` 指向的 dreamx 包没装,缺它的 slice provider),属于镜像残缺;
 * 4. `ACTION_SETTINGS`:系统设置首页,最后的退路。
 * A95L 上哪一项生效没在真机上试过(本轮不碰电视),链的设计保证落在第一个真的打开了的页面上。
 */
internal val DREAM_SETTINGS_PAGES = listOf(
    SystemPage(action = "android.settings.DREAM_SETTINGS"), // Settings.ACTION_DREAM_SETTINGS
    SystemPage(pkg = "com.android.tv.settings", cls = "com.android.tv.settings.device.display.daydream.DaydreamActivity"),
    SystemPage(action = "com.google.android.tv.settings.ambient"),
    SystemPage(action = "android.settings.SETTINGS"), // Settings.ACTION_SETTINGS
)

/** 动画缩放在开发者选项里;开发者选项没打开的设备上它可能解析不到或弹回,退到系统设置首页。 */
internal val ANIMATION_SETTINGS_PAGES = listOf(
    SystemPage(action = "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"), // Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
    SystemPage(action = "android.settings.SETTINGS"),
)

/**
 * 启动系统页之后,本 Activity 在这么短的时间内就回到前台(或根本没离开)= 那一页没真正打开(当场 finish / 崩溃),
 * 换下一个候选。判的是「回到前台距启动多久」,不是「这么久之后是否在前台」——后者会把用户很快按返回也当成弹回。
 * 1.5 s:模拟器上 DaydreamActivity 从启动到自己关掉约 0.3 s,AmbientActivity 崩掉约 1.2 s;人打开一页再按返回远超 1.5 s。
 */
internal const val SYSTEM_PAGE_BOUNCE_MS = 1_500L
