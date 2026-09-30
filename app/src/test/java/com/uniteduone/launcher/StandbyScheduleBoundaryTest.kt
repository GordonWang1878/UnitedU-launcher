package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待机 / 屏保计时的边界(StandbyScheduleTest 之外):设置页全部合法取值的组合、0 与负数(= 关)、1 ms、
 * 逼近 Long.MAX_VALUE 的大值。两个时刻都是「距最后一次按键多少毫秒」,MainActivity 先 delay(standbyAt)、
 * 再 delay(screensaverAt − standbyAt),所以屏保时刻永远不能早于待机时刻、也不能是负数。
 * (计时不读时钟、只做相对 delay,「时钟倒拨」在这一层不存在。)
 */
class StandbyScheduleBoundaryTest {

    @Test fun everyValidSettingsCombinationKeepsTheInvariants() {
        for (idle in VALID_IDLE_AFTER_MS) {
            for (after in VALID_SCREENSAVER_AFTER_MS) {
                val p = standbyPlan(idle, after)
                val tag = "idle=$idle after=$after"
                assertEquals(tag, if (idle == 0L) null else idle, p.standbyAt)
                if (after == 0L) {
                    assertNull(tag, p.screensaverAt)
                } else {
                    val at = p.screensaverAt!!
                    // 待机「关」时从最后一次按键算(0 + after),否则接在待机之后
                    assertEquals(tag, idle + after, at)
                    assertTrue(tag, at > 0L)
                    p.standbyAt?.let { assertTrue(tag, at > it) }
                }
            }
        }
    }

    @Test fun negativeDurationsMeanOff() {
        assertEquals(StandbyPlan(null, null), standbyPlan(-1L, -1L))
        assertEquals(StandbyPlan(null, 60_000L), standbyPlan(-60_000L, 60_000L))
        assertEquals(StandbyPlan(60_000L, null), standbyPlan(60_000L, -60_000L))
        assertEquals(StandbyPlan(null, null), standbyPlan(Long.MIN_VALUE, Long.MIN_VALUE))
    }

    @Test fun oneMillisecondIsAlreadyOn() {
        assertEquals(StandbyPlan(1L, 2L), standbyPlan(1L, 1L))
        assertEquals(StandbyPlan(null, 1L), standbyPlan(0L, 1L))
        assertEquals(StandbyPlan(1L, null), standbyPlan(1L, 0L))
    }

    @Test fun hugeSingleDurationsPassThrough() {
        assertEquals(StandbyPlan(Long.MAX_VALUE, null), standbyPlan(Long.MAX_VALUE, 0L))
        assertEquals(StandbyPlan(null, Long.MAX_VALUE), standbyPlan(0L, Long.MAX_VALUE))
        assertEquals(StandbyPlan(null, Long.MAX_VALUE), standbyPlan(-5L, Long.MAX_VALUE))
    }
}
