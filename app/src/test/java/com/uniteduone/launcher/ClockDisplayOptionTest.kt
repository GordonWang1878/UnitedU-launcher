package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R149:「时钟显示」三档 ↔ showDate / showWeekday 两个存盘键。 */
class ClockDisplayOptionTest {
    private val write = optionWrite("clockDisplay")!!

    @Test fun `三档写盘`() {
        val base = Settings()
        write(base, 0).let { assertFalse(it.showDate); assertFalse(it.showWeekday) }
        write(base, 1).let { assertTrue(it.showDate); assertFalse(it.showWeekday) }
        write(base, 2).let { assertTrue(it.showDate); assertTrue(it.showWeekday) }
    }

    @Test fun `读回是同一档`() {
        for (i in 0..2) assertEquals(i, clockDisplayIndex(write(Settings(), i)))
    }

    @Test fun `只关日期时星期无意义,算仅时间`() {
        assertEquals(0, clockDisplayIndex(Settings(showDate = false, showWeekday = true)))
    }

    /** 旧文件没有 showWeekday:原来选「时间与日期」的落在不带星期的那一档(默认值)。 */
    @Test fun `旧文件没有星期键,落在时间与日期`() {
        val s = parseSettings("{\"showDate\": true}")
        assertTrue(s.showDate)
        assertFalse(s.showWeekday)
        assertEquals(1, clockDisplayIndex(s))
    }

    @Test fun `写盘再读回,星期键保留`() {
        val s = Settings(showDate = true, showWeekday = true)
        assertTrue(parseSettings(s.toJson()).showWeekday)
    }
}
