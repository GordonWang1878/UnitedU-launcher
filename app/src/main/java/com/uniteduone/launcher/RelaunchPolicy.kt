package com.uniteduone.launcher

/**
 * 更新后要不要把自己拉回桌面任务(R151,2026-09-30 深夜 Gordon:「两步都做」):
 * - 必须仍是默认桌面(默认桌面是别人,更新后就该是别人);
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
