package com.uniteduone.launcher

import android.content.Context

/**
 * [shouldRelaunchHome] 的依据(R151):被更新杀掉那一刻桌面 / 我们的系统屏保在不在屏幕上、用户是不是刚从关于页发起了更新。
 * 更新会 force-stop 本应用,新进程收到 MY_PACKAGE_REPLACED 时读这份——只能存盘,不能放内存。
 * 一份很小的 SharedPreferences,写入一律 `commit()`(同步):进程随时可能被整个杀掉,异步写可能来不及落盘。
 * 不是 layout 那类多写者状态文件(落盘铁律管的是那些);这里每个键只有一个写者。
 */
object RelaunchMarks {
    private const val FILE = "relaunch"
    private const val HOME_VISIBLE = "homeVisible"
    private const val DREAMING = "dreaming"
    private const val UPDATE_PENDING_AT = "updatePendingAt"

    data class Snapshot(val homeVisible: Boolean, val dreaming: Boolean, val updatePendingAt: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** MainActivity 的 onStart / onStop。 */
    fun setHomeVisible(ctx: Context, visible: Boolean) {
        prefs(ctx).edit().putBoolean(HOME_VISIBLE, visible).commit()
    }

    /** 系统屏保 [UnitedUDream] 的 onDreamingStarted / onDreamingStopped(屏保开着时 MainActivity 已经 onStop)。 */
    fun setDreaming(ctx: Context, dreaming: Boolean) {
        prefs(ctx).edit().putBoolean(DREAMING, dreaming).commit()
    }

    /** 关于页把更新包交给系统安装器之前。 */
    fun markUpdatePending(ctx: Context, now: Long = System.currentTimeMillis()) {
        prefs(ctx).edit().putLong(UPDATE_PENDING_AT, now).commit()
    }

    fun read(ctx: Context): Snapshot = prefs(ctx).let {
        Snapshot(it.getBoolean(HOME_VISIBLE, false), it.getBoolean(DREAMING, false), it.getLong(UPDATE_PENDING_AT, 0L))
    }

    /** 用过一次就清:这一次拉不拉定下来之后,旧的「刚发起更新」「在屏幕上」不能影响下一次(新进程还没有界面)。 */
    fun consume(ctx: Context) {
        prefs(ctx).edit().remove(UPDATE_PENDING_AT).putBoolean(HOME_VISIBLE, false).putBoolean(DREAMING, false).commit()
    }
}
