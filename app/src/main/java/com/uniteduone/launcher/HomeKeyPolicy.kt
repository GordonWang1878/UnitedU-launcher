package com.uniteduone.launcher

/**
 * 主页键接管(R162)的纯判定。服务(`HomeKeyService`,独立进程)只负责把系统事件翻译成这里的参数、再按返回值动作;
 * 一行 Android 都不碰,全部在 JVM 上钉死(`HomeKeyPolicyTest`)。先例与探针:docs/research/2026-10-02-home-key-takeover-prior-art.md。
 */

/** 对一下 HOME 键怎么办。 */
enum class HomeKeyAction {
    /** 原样放行,系统照常处理(回原厂桌面 / 退出屏保)。 */
    PASS,
    /** 吃掉,不做别的(松开、长按的重复事件)。 */
    CONSUME,
    /** 吃掉并拉起 UnitedU(第一下按下)。 */
    CONSUME_AND_LAUNCH,
}

/**
 * @param active 接管此刻生效 = UnitedU **不是**默认桌面(是的话系统自己会回到我们,服务只旁观——A95L 上就是这样)。
 * @param dreaming 屏保正在播放(服务按系统广播 ACTION_DREAMING_STARTED / STOPPED 记,任何包的屏保都算):放行,让系统按原生规则「HOME 只退出屏保、不回桌面」
 *   (探针实测:吃掉的话屏保不退出,UnitedU 在屏保后面被拉起,用户卡在屏保里)。
 * @param down 按下(false = 松开);@param repeat 重复计数(长按时 > 0,长按等于短按)。
 * 松开也要吃:系统是在松开那一下才回桌面(AOSP `DisplayHomeButtonHandler.handleHomeButton`),只吃按下照样回原厂桌面。
 */
fun onHomeKey(active: Boolean, dreaming: Boolean, down: Boolean, repeat: Int): HomeKeyAction = when {
    !active || dreaming -> HomeKeyAction.PASS
    down && repeat == 0 -> HomeKeyAction.CONSUME_AND_LAUNCH
    else -> HomeKeyAction.CONSUME
}

/** 别的桌面的 (包名, HOME Activity 类名):`queryIntentActivities(MAIN + HOME)` 的结果去掉自己。 */
fun stockHomes(all: List<Pair<String, String>>, self: String): Set<Pair<String, String>> =
    all.filter { it.first != self }.toSet()

/** 连续两次拉起之间的最短间隔:一次换桌面会发两三个窗口事件,别拉两次。 */
const val HOME_RELAUNCH_DEBOUNCE_MS = 1_000L

/**
 * 前台窗口换成了 ([pkg], [cls]):要不要把 UnitedU 拉到前面。只认别的桌面的 **HOME Activity 本身**([stockHomes]),
 * 它们的别的 Activity / 弹窗不算——Projectivy 4.63 / 4.64 修过「所有声明 launcher activity 的应用被误接管」。
 */
fun onWindowChanged(
    active: Boolean,
    pkg: String?,
    cls: String?,
    stockHomes: Set<Pair<String, String>>,
    lastLaunchAt: Long,
    now: Long,
): Boolean = active && pkg != null && cls != null && (pkg to cls) in stockHomes && now - lastLaunchAt >= HOME_RELAUNCH_DEBOUNCE_MS

/** 开机后多久以内连上算「开机」。模拟器上 27–31 s 连上,真电视慢得多;3 分钟够。 */
const val BOOT_LAUNCH_WINDOW_MS = 3 * 60 * 1000L

/**
 * 服务刚连上(开机 / 用户刚在设置里打开 / 更新后系统重连):只在开机窗口内拉一次——开机时服务连上之前原厂桌面已经在屏幕上
 * 约 1.5 s(躲不掉),连上后它也不会再发窗口事件,不拉就停在原厂桌面。用户刚打开时不拉,他还在设置页里。
 */
fun shouldLaunchOnConnect(active: Boolean, uptimeMs: Long): Boolean = active && uptimeMs < BOOT_LAUNCH_WINDOW_MS
