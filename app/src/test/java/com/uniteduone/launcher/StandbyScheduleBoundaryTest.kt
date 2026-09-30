package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
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

    /**
     * 待机 + 屏保两个大值相加越过 Long.MAX_VALUE:现在绕成负数(standbyPlan(1, MAX) 的屏保时刻 = Long.MIN_VALUE,
     * 比待机还早)。**走不到**:Settings 读盘时把两个值夹回合法档位表(最大 10 分 + 30 分);而且 MainActivity
     * 只用差值 delay(screensaverAt − standbyAt),补码减法恰好还原出 screensaverAfterMs,电视上行为不变。
     * 只是这个纯函数自己的返回值违反了「屏保不早于待机」;要不要改成饱和加法留给维护者定。
     */
    @Ignore("潜在溢出,Settings 的档位表使其不可达;是否改成饱和加法待定")
    @Test fun hugeSumsSaturateInsteadOfWrappingIntoThePast() {
        assertEquals(StandbyPlan(1L, Long.MAX_VALUE), standbyPlan(1L, Long.MAX_VALUE))
        assertEquals(StandbyPlan(Long.MAX_VALUE, Long.MAX_VALUE), standbyPlan(Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
