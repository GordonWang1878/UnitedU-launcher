package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 卡片档位的回落(M8 终审遗留):非法张数一律按中档 6 张。 */
class ThemeMetricsTest {
    @Test fun invalidCardsPerRowFallsBackToSix() {
        assertEquals(Theme.cardMetrics(6), Theme.cardMetrics(7))
        assertEquals(Theme.cardMetrics(6), Theme.cardMetrics(0))
        assertEquals(Theme.cardMetrics(6), Theme.cardMetrics(-3))
    }

    @Test fun validCountsKeepTheirOwnWidth() {
        assertNotEquals(Theme.cardMetrics(6).cardWidth, Theme.cardMetrics(5).cardWidth)
        assertNotEquals(Theme.cardMetrics(6).cardWidth, Theme.cardMetrics(8).cardWidth)
    }
}
