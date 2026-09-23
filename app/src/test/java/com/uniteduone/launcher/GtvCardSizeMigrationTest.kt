package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class GtvCardSizeMigrationTest {
    @Test fun `旧的每行张数映射到新的三档`() {
        // 张数越多卡越小 —— 8 张 = 小,6 张 = 中,5 张 = 大
        assertEquals(GtvCardSize.SMALL, cardsPerRowToGtvSize(8))
        assertEquals(GtvCardSize.MEDIUM, cardsPerRowToGtvSize(6))
        assertEquals(GtvCardSize.LARGE, cardsPerRowToGtvSize(5))
    }

    @Test fun `非法值落到中档`() {
        assertEquals(GtvCardSize.MEDIUM, cardsPerRowToGtvSize(0))
        assertEquals(GtvCardSize.MEDIUM, cardsPerRowToGtvSize(7))
        assertEquals(GtvCardSize.MEDIUM, cardsPerRowToGtvSize(-1))
    }
}
