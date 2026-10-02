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

/**
 * HOME 键「按下 / 松开」的去重与配对(终审 2026-10-02),服务持有一份,所有按键事件都在主线程上进来,不加锁。两件事:
 * - **去重**:真实输入送进过滤器的按下 `repeatCount` 恒为 0(长按的重复是过滤器之后才合成的,过滤器看不到),有的遥控器还会用同一个
 *   `downTime` 连发 DOWN——[onHomeKey] 只看 `repeat`,会把每一发都当成新的一下、拉起 N 次。所以一次按下(按 `downTime` 认)只判一次:
 *   吃掉过的,同一个 downTime 的后续 DOWN 只吃、不再拉;放行过的,后续 DOWN 也放行——别半路改口(放行的是屏保里的 HOME,它刚把屏保退出、
 *   `dreaming` 随即翻成 false,不记着的话重复的 DOWN 会把 UnitedU 拉起来)。`downTime <= 0` 是「不知道」,一概不去重
 *   (一个恒报 0 的设备不能让第一下之后的所有按键都被当成重复、变成死键)。
 * - **配对**:松开跟着它的按下走——按下吃了松开就吃,按下放行了松开也放行,**不按松开那一刻的 `active` / `dreaming` 重判**:
 *   两下之间状态可能翻了(屏保刚好启动 / 结束、5 s 一次的重算换了结论),半吃半放会让系统只收到一个松开——它正是在松开那一下回原厂桌面的。
 */
class HomeKeyPresses {
    private var consumedDownTime = -1L
    private var passedDownTime = -1L
    private var downConsumed = false

    /** 一个 DOWN:只在它是新的一下时才调 [decide](按 [onHomeKey] 判),返回最终怎么办。 */
    fun onDown(downTime: Long, decide: () -> HomeKeyAction): HomeKeyAction {
        if (downTime > 0L) {
            if (downTime == consumedDownTime) return HomeKeyAction.CONSUME
            if (downTime == passedDownTime) return HomeKeyAction.PASS
        }
        val action = decide()
        if (action == HomeKeyAction.PASS) {
            passedDownTime = downTime
            downConsumed = false
        } else {
            consumedDownTime = downTime
            downConsumed = true
        }
        return action
    }

    /** 一个 UP:返回要不要吃(= 它的 DOWN 是不是被吃了);没见过 DOWN 的 UP 放行。 */
    fun onUp(): Boolean = downConsumed.also { downConsumed = false }
}

/** 别的桌面的 (包名, HOME Activity 类名):`queryIntentActivities(MAIN + HOME)` 的结果去掉自己。 */
fun stockHomes(all: List<Pair<String, String>>, self: String): Set<Pair<String, String>> =
    all.filter { it.first != self }.toSet()

/** 连续两次拉起之间的最短间隔:一次换桌面会发两三个窗口事件,别拉两次。 */
const val HOME_RELAUNCH_DEBOUNCE_MS = 1_000L

/** 去抖丢掉窗口事件之后,补查排在去抖结束后的这么多毫秒(让窗口事件的先后落定)。 */
const val HOME_RECHECK_SLACK_MS = 50L

/** 生效中,且 ([pkg], [cls]) 是别的桌面的 **HOME Activity 本身**——[onWindowChanged] 与 [debouncedOnly] 共用的前提。 */
private fun isStockHomeWindow(active: Boolean, pkg: String?, cls: String?, stockHomes: Set<Pair<String, String>>): Boolean =
    active && pkg != null && cls != null && (pkg to cls) in stockHomes

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
): Boolean = isStockHomeWindow(active, pkg, cls, stockHomes) && now - lastLaunchAt >= HOME_RELAUNCH_DEBOUNCE_MS

/**
 * 本该拉回、**只因为**落在上一次拉起后的 [HOME_RELAUNCH_DEBOUNCE_MS] 内而被 [onWindowChanged] 丢掉的窗口事件(与它严格互补)。
 * 服务据此在去抖结束后补查一次([recheckDelayMs]):「我们的拉起还没落地、原厂桌面又冒出来」那一下若被去抖吞掉,
 * 原厂桌面之后不再发窗口事件,用户就一直停在原厂桌面上(开机时模拟器上见过拉起 1.2 s 后原厂桌面又冒出来,那次落在去抖之外、被拉回了;
 * 早 0.3 s 就会被吞)。
 */
fun debouncedOnly(
    active: Boolean,
    pkg: String?,
    cls: String?,
    stockHomes: Set<Pair<String, String>>,
    lastLaunchAt: Long,
    now: Long,
): Boolean = isStockHomeWindow(active, pkg, cls, stockHomes) && now - lastLaunchAt < HOME_RELAUNCH_DEBOUNCE_MS

/** 补查要等多久:从 [now] 起到「上次拉起 + 去抖 + [HOME_RECHECK_SLACK_MS]」,过了就 0(不为负)。 */
fun recheckDelayMs(lastLaunchAt: Long, now: Long): Long =
    (lastLaunchAt + HOME_RELAUNCH_DEBOUNCE_MS + HOME_RECHECK_SLACK_MS - now).coerceAtLeast(0L)

/** 开机后多久以内连上算「开机」。模拟器上 27–31 s 连上,真电视慢得多;3 分钟够。 */
const val BOOT_LAUNCH_WINDOW_MS = 3 * 60 * 1000L

/**
 * 服务刚连上(开机 / 用户刚在设置里打开 / 更新后系统重连):只在开机窗口内拉一次——开机时服务连上之前原厂桌面已经在屏幕上
 * 约 1.5 s(躲不掉),连上后它也不会再发窗口事件,不拉就停在原厂桌面。用户刚打开时不拉,他还在设置页里。
 */
fun shouldLaunchOnConnect(active: Boolean, uptimeMs: Long): Boolean = active && uptimeMs < BOOT_LAUNCH_WINDOW_MS
