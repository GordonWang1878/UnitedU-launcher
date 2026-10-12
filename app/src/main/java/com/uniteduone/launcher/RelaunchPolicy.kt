package com.uniteduone.launcher

/**
 * 更新后要不要把自己拉回桌面任务(R151,2026-09-30 深夜 Gordon:「两步都做」):
 * - 必须仍是默认桌面,或主页键接管开着(R162,调用方传 [homeOrTakeover] 的结果;默认桌面是别人、接管也没开,更新后就该是别人);
 * - 有悬浮窗 appop:索尼固件不豁免默认桌面的后台启动,没有它 startActivity 会被 BAL 拦下(见 CLAUDE.md 真机一节);
 * - **而且**被更新杀掉的那一刻桌面(或我们的系统屏保)就在屏幕上,或者用户刚从关于页发起了这次更新。
 *   用户在别的应用里时(商店后台更新、开发时 adb 覆盖安装)不拉——拉回来会把桌面盖到正在看的 YouTube 上;
 *   代价是那种情况下首页任务照样没了,退出那个应用会落到任务栈里的下一个(见 WORKLOG 2026-09-30 深夜)。
 */
fun shouldRelaunchHome(
    isDefaultHome: Boolean,
    canDrawOverlays: Boolean,
    wasOnScreen: Boolean,
    userStartedUpdate: Boolean,
): Boolean = isDefaultHome && canDrawOverlays && (wasOnScreen || userStartedUpdate)

/** 关于页交给安装器之后多久内完成的更新,算「用户刚发起的这一次」(安装器里确认、装完,通常一两分钟)。 */
const val UPDATE_REQUEST_WINDOW_MS = 30 * 60 * 1000L

/** [pendingAt](关于页交给安装器的时刻,0 = 没有)离 [now] 不超过 [window]。时钟倒退(pendingAt 在未来)不算。 */
fun isRecentUpdateRequest(pendingAt: Long, now: Long, window: Long = UPDATE_REQUEST_WINDOW_MS): Boolean =
    pendingAt > 0L && now >= pendingAt && now - pendingAt <= window

/** R162:主页键接管开着时,HOME 本来就会回到我们,更新后拉回与默认桌面同一待遇。 */
fun homeOrTakeover(isDefaultHome: Boolean, takeoverEnabled: Boolean): Boolean = isDefaultHome || takeoverEnabled

/**
 * 更新后拉回的 HOME intent 带这个标记(2026-10-12 Gordon:更新完跳到 Apple TV 的 HDMI 2,要再按 HOME)。
 * 机制见 CLAUDE.local.md「R151 拉回也挡不住 CEC」:首页任务被删的空档里输入源应用(tvlin)被短暂恢复、调回上次的 HDMI,
 * 3 s 后被 CEC 一键播放顶到最前,盖住已经拉回的桌面。带标记启动的桌面在 [UPDATE_RECLAIM_WINDOW_MS] 内被盖住、
 * 而用户一个键都没按过时,自己再回前台([shouldReclaimAfterUpdate])。
 */
const val EXTRA_UPDATE_RELAUNCH = "com.uniteduone.launcher.UPDATE_RELAUNCH"

/** 更新拉回后守多久(CEC 那一下实测在拉回后约 3 s;留足余量)。 */
const val UPDATE_RECLAIM_WINDOW_MS = 10_000L

/** 每次更新最多夺回几次:防止与某个同样执着的应用来回抢。 */
const val UPDATE_RECLAIM_MAX = 2

/** 桌面刚被盖住([now] 是 elapsedRealtime):在守护窗口内、用户没按过键、次数没用完 → 夺回。 */
fun shouldReclaimAfterUpdate(now: Long, guardUntil: Long, userKeyed: Boolean, reclaims: Int): Boolean =
    !userKeyed && now < guardUntil && reclaims < UPDATE_RECLAIM_MAX
