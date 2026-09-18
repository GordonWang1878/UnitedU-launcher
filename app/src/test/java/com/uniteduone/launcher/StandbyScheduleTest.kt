package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5 spec §1.1 的四种组合 + §1 表的不变量「屏保 ⇒ 待机」。两个时刻都从最后一次按键起算。 */
class StandbyScheduleTest {
    @Test fun standbyThenScreensaver() {
        // 默认:3 分待机,再 5 分屏保 → 距最后一次按键 3 分、8 分
        assertEquals(StandbyPlan(180_000L, 480_000L), standbyPlan(180_000L, 300_000L))
    }

    @Test fun standbyOffCountsScreensaverFromLastKey() {
        assertEquals(StandbyPlan(null, 60_000L), standbyPlan(0L, 60_000L))
    }

    @Test fun screensaverOffLeavesStandbyAlone() {
        assertEquals(StandbyPlan(60_000L, null), standbyPlan(60_000L, 0L))
    }

    @Test fun bothOffMeansNothingHappens() {
        assertEquals(StandbyPlan(null, null), standbyPlan(0L, 0L))
    }

    @Test fun flagConstantsMatchTheSpecTable() {
        assertFalse(StandbyFlags.NORMAL.idle); assertFalse(StandbyFlags.NORMAL.screensaverActive)
        assertTrue(StandbyFlags.STANDBY.idle); assertFalse(StandbyFlags.STANDBY.screensaverActive)
        assertTrue(StandbyFlags.SCREENSAVER.idle); assertTrue(StandbyFlags.SCREENSAVER.screensaverActive)
    }

    @Test(expected = IllegalArgumentException::class)
    fun screensaverWithoutStandbyIsRejected() {
        StandbyFlags(idle = false, screensaverActive = true)
    }
}
