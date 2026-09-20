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

    // Fix round 1(R15,2026-09-20):这个值**不再是** Google 实测的 125.5——那是用 Latin 标题
    // (`Top picks for you`)量出来的行距,套用到中文标题上会裁字(见 ROW_TITLE_LINE/ROW_GAP 的
    // KDoc 与 task-9b-report.md)。143.5625 是 CJK 不裁切的前提下,同一条公式重新算出来的值,
    // 断言这个新值,不是要把它凑回 125.5。
    @Test fun `中档行间距 = CJK 不裁切前提下的值,不是 Google 的 Latin 125点5`() {
        // 23(CJK 实测行盒) + 12.5 + 14(焦点描边留白) + 86.0625(中档卡高) + 8(ROW_GAP) = 143.5625
        assertEquals(143.5625f, GtvLayout.rowPitch(GtvCardSize.MEDIUM, showTitles = false), 0.01f)
    }

    @Test fun `纵向位移按行数累加`() {
        // -2 × 143.5625 = -287.125
        assertEquals(-287.125f, GtvLayout.rowShiftY(2, GtvCardSize.MEDIUM, showTitles = false), 0.3f)
    }

    // Task 9b:CategoryRow 实际渲染的纵向每一项(标题行盒、标题到卡间距、焦点描边留白、卡高、
    // 行外间距)曾经各自散落在 HomeLayout 字面量与 GtvLayout 公式两处,互相对不上,累积成每行
    // 26.5dp 的漂移。这里刻意把 rowPitch() 该覆盖的每一项摊开重算一遍、不直接调 rowPitch() 本身
    // 去比 rowPitch() ——公式漏项或常数被悄悄改回旧值,这个测试才会跟着报错。
    //
    // **这个测试覆盖不到什么**(review 指出,如实记录):它只断言 GtvLayout 内部的常量与公式互相
    // 一致,是纯 JVM 测试,不渲染 Compose——如果 `HomeScreen.kt` 的 `CategoryRow` 某天又悄悄改回
    // 读 `HomeLayout.ROW_TITLE_LINE`/`ROW_TITLE_GAP`/`ROW_GAP`(本任务修的四个漂移来源里的三个),
    // 这个测试依然会通过,因为它根本不知道 `CategoryRow` 读的是哪个常量。这一类回归目前只能靠
    // Step 5 那样的装机 uiautomator 量测发现,没有自动化测试能兜底。
    @Test fun `rowPitch 等于纵向每一项之和,不允许再漏项`() {
        val expected = GtvLayout.ROW_TITLE_LINE +
            GtvLayout.ROW_TITLE_TO_CARD +
            2f * (GtvLayout.FOCUS_OUTSET + GtvLayout.FOCUS_STROKE) + // 焦点描边留白:上下各一份
            GtvLayout.cardHeight(GtvCardSize.MEDIUM) +
            GtvLayout.ROW_GAP
        assertEquals(expected, GtvLayout.rowPitch(GtvCardSize.MEDIUM, showTitles = false), 0.01f)
        // Fix round 1:不再对齐 Google 的 Latin 125.5——对齐的是「CJK 不裁切」这个新前提下的 143.5625,
        // 数值出处见 ROW_TITLE_LINE/ROW_GAP 各自的 KDoc,不是这里随手写的。
        assertEquals(143.5625f, expected, 0.01f)
    }
}
