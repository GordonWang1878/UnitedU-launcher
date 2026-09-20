package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class GtvLayoutTest {
    @Test fun `中档等于 Google 实测的 153x86`() {
        assertEquals(153f, GtvLayout.cardWidth(GtvCardSize.MEDIUM), 0.01f)
        // 153 × 9/16 = 86.0625;实测量到的 86 是取整值,容差放到 0.1
        assertEquals(86f, GtvLayout.cardHeight(GtvCardSize.MEDIUM), 0.1f)
    }

    @Test fun `三档都是 16比9`() {
        for (s in GtvCardSize.values()) {
            assertEquals(16f / 9f, GtvLayout.cardWidth(s) / GtvLayout.cardHeight(s), 0.02f)
        }
    }

    @Test fun `pitch 等于卡宽加间距`() {
        assertEquals(173f, GtvLayout.cardPitch(GtvCardSize.MEDIUM), 0.01f)
    }

    @Test fun `焦点卡永远钉在左基准线`() {
        // 第 n 张卡聚焦时,行整体左移 n 个 pitch —— 于是它的左缘落在 CONTENT_KEYLINE
        assertEquals(0f, GtvLayout.rowShiftX(0, GtvCardSize.MEDIUM), 0.01f)
        assertEquals(-173f, GtvLayout.rowShiftX(1, GtvCardSize.MEDIUM), 0.01f)
        assertEquals(-865f, GtvLayout.rowShiftX(5, GtvCardSize.MEDIUM), 0.01f)
    }

    @Test fun `负索引夹到 0`() {
        assertEquals(0f, GtvLayout.rowShiftX(-3, GtvCardSize.MEDIUM), 0.01f)
    }

    @Test fun `中档行间距等于实测的 125点5`() {
        // 15 + 12.5 + 86.0625 + 12 = 125.5625,实测 125.5,容差 0.1
        assertEquals(125.5f, GtvLayout.rowPitch(GtvCardSize.MEDIUM, showTitles = false), 0.1f)
    }

    @Test fun `纵向位移按行数累加`() {
        assertEquals(-251f, GtvLayout.rowShiftY(2, GtvCardSize.MEDIUM, showTitles = false), 0.3f)
    }
}
