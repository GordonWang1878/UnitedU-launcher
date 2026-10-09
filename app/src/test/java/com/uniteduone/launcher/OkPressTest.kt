package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R165:编辑页卡片上的确定键——短按松开 = 拿起 / 放下,按满 600 ms = 卡片菜单。按 downTime 认一次按压(同首页移动态的
 * moveDownTime 手法),每一下按压天然不同,不需要清(铁律 7)。
 */
class OkPressTest {
    private val t0 = 10_000L

    @Test fun aQuickReleaseIsAShortPress() {
        val p = OkPress()
        assertEquals(OkOutcome.NOTHING, p.onDown(t0, t0, 0))
        assertEquals(OkOutcome.NOTHING, p.onDown(t0, t0 + 300, 1))     // 首次重复,还没满 600
        assertEquals(OkOutcome.SHORT_PRESS, p.onUp(t0, canceled = false))
    }

    @Test fun holdingFiresTheLongPressExactlyOnce() {
        val p = OkPress()
        p.onDown(t0, t0, 0)
        assertEquals(OkOutcome.NOTHING, p.onDown(t0, t0 + 400, 1))
        assertEquals(OkOutcome.LONG_PRESS, p.onDown(t0, t0 + 650, 2))
        assertEquals("同一下按压只出一次", OkOutcome.NOTHING, p.onDown(t0, t0 + 700, 3))
    }

    /** Review Focus 1:长按开菜单之后松手——这一下的 UP 归我们(要吞掉),但绝不是短按(不能拿起)。 */
    @Test fun upOfALongPressIsOwnedButNeverAShortPress() {
        val p = OkPress()
        p.onDown(t0, t0, 0)
        p.onDown(t0, t0 + 650, 2)
        assertTrue("UP 要吞掉:否则落到菜单第一颗胶囊上被当成一次点击", p.owns(t0))
        assertEquals(OkOutcome.NOTHING, p.onUp(t0, canceled = false))
    }

    @Test fun aCanceledReleaseDoesNothing() {
        val p = OkPress()
        p.onDown(t0, t0, 0)
        assertEquals(OkOutcome.NOTHING, p.onUp(t0, canceled = true))
    }

    @Test fun pressesWeNeverSawAreNotOurs() {
        val p = OkPress()
        assertFalse(p.owns(t0))
        assertEquals("没见过 DOWN 的 UP", OkOutcome.NOTHING, p.onUp(t0, canceled = false))
        assertEquals("没见过首个 DOWN 的重复", OkOutcome.NOTHING, p.onDown(t0, t0 + 900, 5))
    }

    @Test fun theNextPressStartsFresh() {
        val p = OkPress()
        p.onDown(t0, t0, 0); p.onDown(t0, t0 + 650, 2); p.onUp(t0, false)
        val t1 = t0 + 5_000
        p.onDown(t1, t1, 0)
        assertFalse(p.owns(t0))
        assertTrue(p.owns(t1))
        assertEquals(OkOutcome.SHORT_PRESS, p.onUp(t1, canceled = false))
    }

    @Test fun thresholdIsTheSharedLongPressTime() {
        val p = OkPress()
        p.onDown(t0, t0, 0)
        assertEquals(OkOutcome.NOTHING, p.onDown(t0, t0 + LONG_PRESS_MS - 1, 1))
        assertEquals(OkOutcome.LONG_PRESS, p.onDown(t0, t0 + LONG_PRESS_MS, 2))
    }
}
