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

    // Task 9b:CategoryRow 实际渲染的纵向每一项(标题行盒、标题到卡间距、焦点描边留白、卡高、
    // 行外间距)曾经各自散落在 HomeLayout 字面量与 GtvLayout 公式两处,互相对不上,累积成每行
    // 26.5dp 的漂移。这里刻意把 rowPitch() 该覆盖的每一项摊开重算一遍、不直接调 rowPitch() 本身
    // 去比 rowPitch() ——公式漏项或常数被悄悄改回旧值,这个测试才会跟着报错。
    @Test fun `rowPitch 等于纵向每一项之和,不允许再漏项`() {
        val expected = GtvLayout.ROW_TITLE_LINE +
            GtvLayout.ROW_TITLE_TO_CARD +
            2f * (GtvLayout.FOCUS_OUTSET + GtvLayout.FOCUS_STROKE) + // 焦点描边留白:上下各一份
            GtvLayout.cardHeight(GtvCardSize.MEDIUM) +
            GtvLayout.ROW_GAP
        assertEquals(expected, GtvLayout.rowPitch(GtvCardSize.MEDIUM, showTitles = false), 0.01f)
        // 与 Google 实测行距(研究报告 §3:890 − 639 = 251 px = 125.5 dp)对齐,容差 0.1
        assertEquals(125.5f, expected, 0.1f)
    }
}
