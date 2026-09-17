package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockPatternsTest {
    @Test fun twentyFourHour() {
        assertEquals("HH:mm" to "EEE yyyy/M/d", clockPatterns(is24Hour = true))
    }

    @Test fun twelveHourCarriesAmPm() {
        // 12 小时制必须带 a:凌晨 2 点和下午 2 点否则长得一样(2026-09-15 复审)
        assertEquals("h:mm a" to "EEE yyyy/M/d", clockPatterns(is24Hour = false))
    }
}
