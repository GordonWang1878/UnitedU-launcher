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

/**
 * 只升不降:已经 idle(待机或屏保)时原样不动,只有 NORMAL 才被抬到 STANDBY。
 * 计时效果到点进待机那一拍用它(spec 的写法是「只写 idle = true」,打包成一个值之后
 * 等价写法就是这个判断)——屏保按钮可能已经把状态推到了屏保,这一拍不能把它拉回待机。
 * 终审 Important 2:从 MainActivity 抽出,JVM 单测覆盖(原来是内联、模拟器专属)。
 */
fun StandbyFlags.atLeastStandby(): StandbyFlags = if (idle) this else StandbyFlags.STANDBY

/**
 * 屏保按钮按下时的目标状态(M5 spec §1.3 / M8 spec §1.5):有图 → 立刻进自定义屏保、跳过待机;
 * 图库空 → 退为进待机;空图库 +「不淡出」→ null(空操作,调用方不写状态、下一个键不被吞——
 * 「不淡出」的待机没有任何可见效果,进了只会白吞一个键)。
 * 终审 Important 2:从 MainActivity 的按钮效果抽出,JVM 单测覆盖(原来是内联、模拟器专属)。
 */
fun screensaverButtonTarget(hasImages: Boolean, idleContent: IdleContent): StandbyFlags? = when {
    hasImages -> StandbyFlags.SCREENSAVER
    idleContent != IdleContent.NO_FADE -> StandbyFlags.STANDBY
    else -> null
}
