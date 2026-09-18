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

    // 终审 Important 2:屏保按钮的目标状态与「只升不降」从 MainActivity 抽到这里,
    // 两处只有一份判断,JVM 单测覆盖(原来是内联、模拟器专属)。

    @Test fun buttonTargetWithImagesIsAlwaysScreensaverRegardlessOfIdleContent() {
        for (content in IdleContent.entries) {
            assertEquals(StandbyFlags.SCREENSAVER, screensaverButtonTarget(hasImages = true, idleContent = content))
        }
    }

    @Test fun buttonTargetNoImagesClockOnlyOrBlackFallsBackToStandby() {
        assertEquals(StandbyFlags.STANDBY, screensaverButtonTarget(hasImages = false, idleContent = IdleContent.CLOCK_ONLY))
        assertEquals(StandbyFlags.STANDBY, screensaverButtonTarget(hasImages = false, idleContent = IdleContent.BLACK))
    }

    @Test fun buttonTargetNoImagesNoFadeIsANoOp() {
        assertEquals(null, screensaverButtonTarget(hasImages = false, idleContent = IdleContent.NO_FADE))
    }

    @Test fun atLeastStandbyRaisesOnlyFromNormal() {
        assertEquals(StandbyFlags.STANDBY, StandbyFlags.NORMAL.atLeastStandby())
        assertEquals(StandbyFlags.STANDBY, StandbyFlags.STANDBY.atLeastStandby())
        assertEquals(StandbyFlags.SCREENSAVER, StandbyFlags.SCREENSAVER.atLeastStandby())
    }
}
