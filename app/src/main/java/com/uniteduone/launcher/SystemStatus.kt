package com.uniteduone.launcher

import java.math.BigDecimal

/**
 * 设置页要显示的**系统**设置快照:系统屏保开关 / 来源 / 启动时间、三项动画缩放、关闭屏幕的时间(R127)。
 *
 * 只读。我们不申请 WRITE_SECURE_SETTINGS / WRITE_SETTINGS(Gordon 2026-09-23 定:直接改写要 adb 授权,
 * 而且会覆盖用户有意调的无障碍动画设置),每一项只显示当前值 + 一键跳系统对应页。
 *
 * **Ruling R56(2026-09-23 傍晚,推翻 R55 的「系统」组)**:三行跳同一个系统页、拆成一组很蠢(Gordon 原话
 * 「三行跳同一个系统页,拆出来很蠢」)。现在屏保三项合成「待机与屏保」组里**一行**「系统屏保 ▸」的摘要
 * ([screensaverSummary]),动画缩放提示行挪到「其他」组最上面。
 *
 * **每个字段 null = 读不到**(键没设过、新系统不让第三方读非公开键而抛 SecurityException、值格式不认识)。
 * 读不到的部分在摘要里**省略**,**不猜默认值**——`screensaver_enabled` 没设过时系统到底开没开,
 * 取决于厂商 overlay 的 `config_dreamsEnabledByDefault`,我们读不到就不说。
 *
 * 这个文件一行 Android 都不碰:解析与格式化全是纯函数,在 JVM 单测里钉死([SystemStatusTest]);
 * 真正去 ContentResolver 读的那一小段在 [readSystemUiStatus](`SystemStatusReader.kt`)里。
 * (`R.string.*` 只是 Int 常量,JVM 单测里照样能用——[SettingsModel] 早就这么做。)
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
    /**
     * `Settings.Secure.sleep_timeout`(R127「关闭屏幕」行):无操作多久之后连屏保一起关掉显示屏,已解析成显示单位
     * ([sleepTimeoutDisplay])。与其他字段一样 null = 读不到;键**没设过**不是读不到,是平台缺省「从不」。
     */
    val screenOff: TimeoutDisplay? = null,
    /**
     * 电视设置里「关闭屏幕」那一页的菜单路径(R127c),**用电视自己设置应用里的真实菜单名**、按电视当前语言:
     * 索尼 [系统, 电源和能耗, 自动关闭],Google TV 原生 [系统, 电源和能耗, 关机定时器];老布局没有「电源和能耗」时两段。
     * null = 读不到(不是 TvSettings 系的设置应用,或资源名对不上)→ 小字给通用提示。见 [screenOffNote]。
     */
    val screenOffPath: List<String>? = null,
    /** 当前默认桌面(R132,「通用 → 默认桌面」行右端的值):null = 读不到,不显示。见 [defaultHomeSummary]。 */
    val defaultHome: DefaultHome? = null,
) {
    companion object {
        /** 一项都没读到(读取器还没跑、或者整块被系统挡掉)。 */
        val UNKNOWN = SystemUiStatus()
    }
}

/**
 * 默认桌面(R132)。[NotSet] = 系统还没有选定默认桌面(HOME 解析到系统的「选择打开方式」,按主页键会弹选择框);
 * [App] 的 [App.label] 是那个桌面的应用名(是 UnitedU 时就是「UnitedU」)。
 */
sealed interface DefaultHome {
    data object NotSet : DefaultHome
    data class App(val label: String) : DefaultHome
}

/** 「默认桌面」行右端的值(R132):写默认桌面的名字;还没选写「未设置」;读不到不显示(空清单)。 */
fun defaultHomeSummary(home: DefaultHome?): List<HintPart> = when (home) {
    null -> emptyList()
    DefaultHome.NotSet -> listOf(HintPart.Res(R.string.settings_home_not_set))
    is DefaultHome.App -> listOf(HintPart.Text(home.label))
}

/** 系统屏保来源。[Ours] = 选的就是 UnitedU 自己的 Dream;[Other] 的 [label] 是应用名,拿不到应用名时是包名。 */
sealed interface DreamSource {
    data object Ours : DreamSource
    data class Other(val label: String) : DreamSource
}

/**
 * 系统时长的显示单位。整小时显示小时、整分钟显示分钟,否则显示秒;[Never] = 系统的「从不」。
 * [HoursMinutes] 只由 [sleepTimeoutDisplay] 产生(R127:超过 1 小时又不是整小时的整分钟值,如「1 小时 30 分钟」);
 * 屏保启动时间([timeoutDisplay])照旧写成「63 分钟」,不拆。
 */
sealed interface TimeoutDisplay {
    data object Never : TimeoutDisplay
    data class Seconds(val n: Int) : TimeoutDisplay
    data class Minutes(val n: Int) : TimeoutDisplay
    data class Hours(val n: Int) : TimeoutDisplay
    data class HoursMinutes(val hours: Int, val minutes: Int) : TimeoutDisplay
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

/**
 * 动作行值文字里的一段(R56):资源文案(可带格式参数)或现成的字(别的应用的名字——来自 PackageManager,
 * 不是我们的资源)。界面逐段解析后用「 · 」连起来;模型层不认识 Context,拼不了字符串,只能交出这份清单。
 */
sealed interface HintPart {
    data class Res(val id: Int, val args: List<Any> = emptyList()) : HintPart
    data class Text(val text: String) : HintPart
}

/**
 * 「系统屏保 ▸」行的值摘要(R56):开着时「开 · UnitedU · 5 分钟」(来源名 + 启动时间,读不到的部分省略);
 * 关着时只写「关」——来源与启动时间此时与用户无关;开关读不到时照样给出读得到的来源 / 时间
 * (「读不到的部分省略」,不因为开关读不到就把整行清空)。**全读不到返回空清单 = 不显示值**。
 */
internal fun screensaverSummary(sys: SystemUiStatus): List<HintPart> {
    if (sys.screensaverEnabled == false) return listOf(HintPart.Res(R.string.settings_off))
    val source: HintPart? = when (val src = sys.screensaverSource) {
        null -> null
        DreamSource.Ours -> HintPart.Res(R.string.app_name)
        is DreamSource.Other -> HintPart.Text(src.label)
    }
    // R132:启动时间写成「无操作 5 分钟后」(与「自动关屏」同一种说法);「从不」写「不会自动开始」——
    // 原来「开 · 从不」两段挨着,读起来像自相矛盾。
    val start: HintPart? = when (val t = sys.screensaverStart) {
        null -> null
        TimeoutDisplay.Never -> HintPart.Res(R.string.settings_sys_never_starts)
        is TimeoutDisplay.Seconds -> HintPart.Res(R.string.settings_sys_idle_seconds, listOf(t.n))
        is TimeoutDisplay.Minutes -> HintPart.Res(R.string.settings_sys_idle_minutes, listOf(t.n))
        is TimeoutDisplay.Hours -> HintPart.Res(R.string.settings_sys_idle_hours, listOf(t.n))
        // timeoutDisplay 不产生这一种;万一传进来,按屏保启动时间一贯的写法折回整分钟。
        is TimeoutDisplay.HoursMinutes -> HintPart.Res(R.string.settings_sys_idle_minutes, listOf(t.hours * 60 + t.minutes))
    }
    val enabled = if (sys.screensaverEnabled == true) HintPart.Res(R.string.settings_on) else null
    return listOfNotNull(enabled, source, start)
}

/**
 * 「关闭屏幕」行的值(R127):「无操作 24 小时后」「无操作 1 小时 30 分钟后」「从不」;读不到写「查看」(同动画缩放行,
 * 不猜)。永远正好一段——这一行没有「省略读不到的部分」可言,整行就是这一个值。
 */
internal fun screenOffSummary(t: TimeoutDisplay?): List<HintPart> = listOf(
    when (t) {
        null -> HintPart.Res(R.string.settings_sys_view)
        TimeoutDisplay.Never -> HintPart.Res(R.string.settings_sys_never)
        is TimeoutDisplay.Seconds -> HintPart.Res(R.string.settings_sys_idle_seconds, listOf(t.n))
        is TimeoutDisplay.Minutes -> HintPart.Res(R.string.settings_sys_idle_minutes, listOf(t.n))
        is TimeoutDisplay.Hours -> HintPart.Res(R.string.settings_sys_idle_hours, listOf(t.n))
        is TimeoutDisplay.HoursMinutes -> HintPart.Res(R.string.settings_sys_idle_hours_minutes, listOf(t.hours, t.minutes))
    },
)

/**
 * `sleep_timeout`(毫秒)的原始字符串 → 显示单位(R127)。这个键是「无操作多久之后让设备睡眠」:屏保(Dream)开着时,
 * 到点就连屏保一起关掉显示屏(A95L 的值是 86400000 = 24 小时,所以整夜停在屏保上)。
 * - 键**没设过**(null):PowerManagerService 用缺省 −1 = 从不,不算读不到;
 * - ≤ 0 或 ≥ `Int.MAX_VALUE`:从不(AOSP 的「从不」存 −1;≤ 0 一律被 PowerManagerService 当成不启用);
 * - 不是整数(含空串):读不到 → null,界面写「查看」;
 * - 其余复用 [timeoutDisplay] 定单位,超过 1 小时又不是整小时的整分钟值拆成 [TimeoutDisplay.HoursMinutes]。
 */
internal fun sleepTimeoutDisplay(raw: String?): TimeoutDisplay? {
    if (raw == null) return TimeoutDisplay.Never
    val ms = raw.trim().toLongOrNull() ?: return null
    if (ms <= 0 || ms >= Int.MAX_VALUE) return TimeoutDisplay.Never
    val d = timeoutDisplay(ms)
    return if (d is TimeoutDisplay.Minutes && d.n > 60) TimeoutDisplay.HoursMinutes(d.n / 60, d.n % 60) else d
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
 * 「关闭屏幕」行(R127)的候选链。索尼 A95L 的「系统 → 电源和能耗 → 关闭显示屏」是 TvSettings 里
 * `power_and_energy` 偏好页内部的一个 fragment(`com.android.tv.settings.device.display.daydream.EnergySaverFragment`),
 * **没有公开的 intent 能直达**(2026-09-29 查过电视上 SonyTvSettings 的清单;`android.settings.DISPLAY_SETTINGS`
 * 在索尼上解析到画质设置,不是电源),所以今天只有系统设置首页这一项(电视上解析到 `com.android.tv.settings/.MainSettings`),
 * 行下的小字告诉人往哪走。仍写成链、走同一个 `openSystemPage`(弹回检测):以后找到直达电源页的入口,加在最前面即可。
 */
internal val SCREEN_OFF_SETTINGS_PAGES = listOf(
    SystemPage(action = "android.settings.SETTINGS"), // Settings.ACTION_SETTINGS
)

/** R162:「主页键接管」胶囊 → 系统无障碍设置(TvSettings 的无障碍页列出我们的服务);没有这一页的固件退到系统设置首页。 */
internal val ACCESSIBILITY_SETTINGS_PAGES = listOf(
    SystemPage(action = "android.settings.ACCESSIBILITY_SETTINGS"), // Settings.ACTION_ACCESSIBILITY_SETTINGS
    SystemPage(action = "android.settings.SETTINGS"),
)

/**
 * 「关闭屏幕」行下的小字(R127c):读得到路径 →「在 系统 → 电源和能耗 → 自动关闭 里修改」(路径用电视自己的菜单名,
 * 各家叫法不同:索尼「自动关闭」、Google TV 原生「关机定时器」);读不到 → 通用提示。返回 (资源 id, 参数)。
 *
 * 为什么不直达那一页(2026-09-29 查过索尼与 Google TV 两份 TvSettings):那一页是 `EnergySaverFragment`,只作为
 * 偏好页里的 fragment 出现,`MainSettings` / `TvSettingsActivity` 永远从首页开、不读任何「跳到哪页」的参数,
 * 没有公开 intent;R127b 用过的索尼「节能控制面板」是索尼独有的,Gordon 否掉(要适用尽量多的安卓电视)。
 */
fun screenOffNote(path: List<String>?): Pair<Int, List<Any>> =
    if (path.isNullOrEmpty()) R.string.settings_screen_off_where_generic to emptyList()
    else R.string.settings_screen_off_where to listOf(path.joinToString(" → "))

/**
 * 启动系统页之后,本 Activity 在这么短的时间内就回到前台(或根本没离开)= 那一页没真正打开(当场 finish / 崩溃),
 * 换下一个候选。判的是「回到前台距启动多久」,不是「这么久之后是否在前台」——后者会把用户很快按返回也当成弹回。
 * 1.5 s:模拟器上 DaydreamActivity 从启动到自己关掉约 0.3 s,AmbientActivity 崩掉约 1.2 s;人打开一页再按返回远超 1.5 s。
 */
internal const val SYSTEM_PAGE_BOUNCE_MS = 1_500L
