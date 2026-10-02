package com.uniteduone.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * 装新版(`adb install -r`)会把正在跑的桌面连同它的任务一起清掉,屏幕露出栈里下一个任务;
 * 系统只在有人「要求 HOME」时才启动默认桌面,所以更新完自己要求一次。
 * 什么时候拉见 [shouldRelaunchHome](R151):仍是默认桌面、有悬浮窗 appop(索尼固件不照 AOSP 豁免默认桌面的后台启动),
 * 而且更新那一刻桌面 / 系统屏保在屏幕上、或用户刚从关于页发起更新([RelaunchMarks])。
 * R162:主页键接管开着时也拉(不是默认桌面时点名自己)。
 */
class RelaunchAfterUpdate : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val defaultHome = context.packageManager
            .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        val isDefaultHome = defaultHome == context.packageName
        // R162:主页键接管开着时也拉;不是默认桌面的话隐式 HOME 会解析到原厂桌面,得点名自己(仍带 MAIN + HOME → type=home 任务)。
        val takeover = !isDefaultHome && HomeKeyState.isEnabled(context)
        val canOverlay = android.provider.Settings.canDrawOverlays(context)
        val marks = RelaunchMarks.read(context)
        val wasOnScreen = marks.homeVisible || marks.dreaming
        val userStarted = isRecentUpdateRequest(marks.updatePendingAt, System.currentTimeMillis())
        RelaunchMarks.consume(context)
        if (!shouldRelaunchHome(homeOrTakeover(isDefaultHome, takeover), canOverlay, wasOnScreen, userStarted)) {
            Log.i(TAG, "package replaced; skip relaunch: defaultHome=$defaultHome takeover=$takeover overlay=$canOverlay " +
                "onScreen=$wasOnScreen (home=${marks.homeVisible} dream=${marks.dreaming}) userStarted=$userStarted")
            return
        }
        val target = if (isDefaultHome) home else home.setComponent(android.content.ComponentName(context, MainActivity::class.java))
        runCatching { context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { Log.i(TAG, "package replaced; HOME intent sent (verdict in ActivityTaskManager log)") }
            .onFailure { Log.w(TAG, "package replaced; relaunch threw", it) }
    }

    private companion object {
        const val TAG = "UnitedU"
    }
}
