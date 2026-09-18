package com.uniteduone.launcher

/**
 * M5 待机与屏保的计时(spec §1.1)。纯 Kotlin、不碰 Android,JVM 单测见 StandbyScheduleTest。
 * 两个时刻都从「最后一次按键」起算:[standbyAt] 进待机,[screensaverAt] 进自定义屏保;null = 这一步不发生。
 * 屏保按「进入待机后再过多久」配置,待机时长为「关」时从最后一次按键算(spec §0「屏保启动」)。
 * 「到点时图库是不是空的」不在这里判:那要读盘,由 MainActivity 在 IO 线程到点再查。
 */
data class StandbyPlan(val standbyAt: Long?, val screensaverAt: Long?)

fun standbyPlan(idleAfterMs: Long, screensaverAfterMs: Long): StandbyPlan {
    val standbyAt = idleAfterMs.takeIf { it > 0L }
    val screensaverAt = if (screensaverAfterMs <= 0L) null else (standbyAt ?: 0L) + screensaverAfterMs
    return StandbyPlan(standbyAt, screensaverAt)
}

/**
 * spec §1 表的三个合法状态。MainActivity 只持有**一个**这样的值:两个布尔量打包写入,一次写完、没有
 * 「只写了一半」的中间态;读者照旧按布尔量读(`idle` 原有的五个消费者不用改,spec §1「不改成枚举」的理由)。
 * 不变量「屏保 ⇒ 待机」由构造函数钉死:屏保层、黑层、唤醒吞键都默认它成立(spec §1.4),
 * 漏写一半的状态在这里当场抛,而不是在电视上表现成「照片开着、按键却直接落到卡片上」。
 */
data class StandbyFlags(val idle: Boolean, val screensaverActive: Boolean) {
    init {
        require(idle || !screensaverActive) { "screensaverActive requires idle" }
    }

    companion object {
        val NORMAL = StandbyFlags(idle = false, screensaverActive = false)
        val STANDBY = StandbyFlags(idle = true, screensaverActive = false)
        val SCREENSAVER = StandbyFlags(idle = true, screensaverActive = true)
    }
}
