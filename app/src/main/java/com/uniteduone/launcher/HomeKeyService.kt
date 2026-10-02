package com.uniteduone.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * 主页键接管(R162):UnitedU 不是默认桌面的电视(Google TV、锁 HOME 的国产品牌)上,按 HOME 回到 UnitedU。
 * 判定全在 `HomeKeyPolicy.kt`;这里只翻译系统事件、执行动作。两条路:
 * - 截键:[onKeyEvent] 先于系统看到 HOME(AOSP 里过滤器在 PhoneWindowManager 处理 HOME 之前),吃掉按下 + 松开、拉起首页——
 *   原厂桌面一帧都不画;
 * - 盯窗口:[onAccessibilityEvent] 看到别的桌面的 HOME Activity 到了前台就拉回来——开机、系统别的路径回桌面时的兜底,会先闪一下原厂桌面。
 * 跑在独立进程 `:homekey`(清单 `android:process`):开着时电视上每一下按键都先经过它,不能和首页的绘制抢主线程;
 * 系统把被绑定的无障碍服务常驻,独立进程才不会把整个桌面(壁纸位图)钉在内存里。**这个进程不碰任何状态文件**(`LockedFile` 是进程内锁),
 * 只写自己的心跳文件([HomeKeyState])。
 * 拉起用 `MAIN + HOME` + 显式组件:任务是 `type=home`,从应用按返回直接落回我们(普通 intent 是 `type=standard`,会先闪原厂桌面——Projectivy #605)。
 */
class HomeKeyService : AccessibilityService() {
    private var dreaming = false
    private var lastLaunchAt = 0L
    private var active = false
    private var stockHomes: Set<Pair<String, String>> = emptySet()
    private var refreshedAt = Long.MIN_VALUE / 2

    // 前台是不是屏保,以系统广播为准,不看窗口事件:屏保窗口的事件类名是 android.widget.FrameLayout、不是 DreamActivity
    // (2026-10-02 模拟器实测,API 34:认不出屏保,屏保里按 HOME 被吞,UnitedU 在屏保后面被拉起,用户卡在屏保里)。
    // ACTION_DREAMING_STARTED / STOPPED 是受保护的系统广播,只发给运行时注册的接收器;服务进程常驻,收得到。
    private val dreamReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            dreaming = intent.action == Intent.ACTION_DREAMING_STARTED
            Log.i(TAG, "homekey dreaming=$dreaming")
        }
    }

    override fun onServiceConnected() {
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        info.notificationTimeout = 0
        serviceInfo = info
        dreaming = false
        runCatching {
            registerReceiver(dreamReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_DREAMING_STARTED)
                addAction(Intent.ACTION_DREAMING_STOPPED)
            })
        }.onFailure { Log.w(TAG, "homekey dream receiver register failed", it) }
        refresh(force = true)
        HomeKeyState.write(this, connected = true)
        val uptime = SystemClock.elapsedRealtime()
        Log.i(TAG, "homekey connected: active=$active stock=$stockHomes uptime=$uptime")
        if (shouldLaunchOnConnect(active, uptime)) launch("connect")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        runCatching { unregisterReceiver(dreamReceiver) }
        HomeKeyState.write(this, connected = false)
        Log.i(TAG, "homekey unbound")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && event.repeatCount == 0) refresh(force = false)
        return when (onHomeKey(active, dreaming, down, event.repeatCount)) {
            HomeKeyAction.PASS -> false
            HomeKeyAction.CONSUME -> true
            HomeKeyAction.CONSUME_AND_LAUNCH -> { launch("key"); true }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        val cls = event.className?.toString()
        val now = SystemClock.elapsedRealtime()
        // 别的桌面来了才值得重算(resolveActivity / queryIntentActivities 是 binder 调用,窗口事件很频繁)
        if (pkg != null && pkg != packageName && stockHomes.any { it.first == pkg }) refresh(force = false)
        if (onWindowChanged(active, pkg, cls, stockHomes, lastLaunchAt, now)) launch("window:$pkg")
    }

    /** 默认桌面是谁、别的桌面有哪些:至多每 [REFRESH_MS] 重算一次(用户在系统设置里换默认桌面没有广播)。 */
    private fun refresh(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - refreshedAt < REFRESH_MS) return
        refreshedAt = now
        val pm = packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val default = runCatching { pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        // 没有默认桌面时解析到的是系统选择器(包名 android)或 null,都 ≠ 本包 → 也算生效
        active = default != packageName
        // activity-alias 在 queryIntentActivities 里报别名、窗口事件里带的是真实类名:两个名字都收,才认得出别的桌面。
        stockHomes = runCatching {
            pm.queryIntentActivities(home, 0).flatMap { ri ->
                val ai = ri.activityInfo
                listOfNotNull(ai.packageName to ai.name, ai.targetActivity?.let { ai.packageName to it })
            }
        }.getOrDefault(emptyList()).let { stockHomes(it, packageName) }
    }

    private fun launch(why: String) {
        lastLaunchAt = SystemClock.elapsedRealtime()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setComponent(ComponentName(this, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // 无障碍服务被系统以 BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS 绑定,后台启动不需要悬浮窗权限;失败只记日志。
        runCatching { startActivity(intent) }
            .onSuccess { Log.i(TAG, "homekey launch ($why)") }
            .onFailure { Log.w(TAG, "homekey launch ($why) failed", it) }
    }

    private companion object {
        const val TAG = "UnitedU"
        const val REFRESH_MS = 5_000L
    }
}
