package com.uniteduone.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * 装新版(`adb install -r`)会把正在跑的桌面连同它的任务一起清掉,屏幕露出栈里下一个任务;
 * 系统只在有人「要求 HOME」时才启动默认桌面,所以更新完自己要求一次。
 * 前提只有一个:系统的默认桌面仍是本应用。默认桌面是别人,更新后就该是别人,什么都不做。
 * 后台启动限制对默认桌面豁免(AOSP `BackgroundActivityStartController.isHomeApp`),
 * 它的判据和这里一样:PackageManager 解析出来的默认 HOME。
 */
class RelaunchAfterUpdate : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val defaultHome = context.packageManager
            .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        val isDefaultHome = defaultHome == context.packageName
        val canOverlay = android.provider.Settings.canDrawOverlays(context)
        if (!shouldRelaunchHome(isDefaultHome, canOverlay)) {
            Log.i(TAG, "package replaced; skip relaunch: defaultHome=$defaultHome overlay=$canOverlay")
            return
        }
        // 只有隐式 HOME 意图会被建成 type=home 的桌面任务;点名自己的 Activity 会变成普通任务。
        // 被后台启动限制拦下时 startActivity 不抛异常,判定要看 ActivityTaskManager 的日志。
        runCatching { context.startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { Log.i(TAG, "package replaced; HOME intent sent (verdict in ActivityTaskManager log)") }
            .onFailure { Log.w(TAG, "package replaced; relaunch threw", it) }
    }

    private companion object {
        const val TAG = "UnitedU"
    }
}
