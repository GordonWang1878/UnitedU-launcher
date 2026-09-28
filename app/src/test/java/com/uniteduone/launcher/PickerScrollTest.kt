package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自算位移的首行规则(铁律 1:不用 verticalScroll)。[keepInView] 现在只给编辑页用;
 * 图片网格 R115 起按像素 + 行顶算([revealScroll]),下面后半段。
 */
class PickerScrollTest {
    @Test fun insideTheViewportNothingMoves() {
        assertEquals(0, keepInView(focusedRow = 0, firstVisible = 0, visibleRows = 4))
        assertEquals(0, keepInView(3, 0, 4))
        assertEquals(2, keepInView(4, 2, 4))
    }

    @Test fun goingBelowMakesTheFocusedRowTheLastVisible() {
        assertEquals(1, keepInView(4, 0, 4))
        assertEquals(6, keepInView(9, 0, 4))   // 一次跳多行(删图后夹回、nonce 重落)也只露到刚好
    }

    @Test fun goingAboveMakesTheFocusedRowTheFirst() {
        assertEquals(2, keepInView(2, 5, 4))
        assertEquals(0, keepInView(0, 3, 4))
    }

    @Test fun nonPositiveVisibleRowsActsAsOne() {
        assertEquals(3, keepInView(3, 0, 0))
    }

    // ---- R115 图片网格:按像素、只停在行顶 ----

    /** 等高的格子行,没有标题:行高 100、行距 16(pitch 116)。 */
    private val uniform = List(6) { 100 }
    private val uniformTops = lineTops(uniform, 16)
    private val uniformTotal = contentHeight(uniformTops, uniform)

    @Test fun lineTopsAccumulateHeightsAndGaps() {
        assertEquals(listOf(0, 116, 232, 348, 464, 580), uniformTops)
        assertEquals(680, uniformTotal)
        assertEquals(listOf(0, 36, 152), lineTops(listOf(20, 100, 100), 16))
        assertEquals(0, contentHeight(emptyList(), emptyList()))
    }

    @Test fun uniformRowsMatchTheOldRowRule() {
        // 视窗 400:放得下 3 行(3×100 + 2×16 = 332 ≤ 400,4 行 448 放不下)——与 keepInView(visibleRows = 3) 逐行相同
        val viewport = 400
        var s = 0
        for ((row, oldFirst) in listOf(0 to 0, 1 to 0, 2 to 0, 3 to 1, 5 to 3)) {
            s = revealScroll(s, uniformTops[row], uniformTops[row] + 100, viewport, uniformTops, uniformTotal)
            assertEquals("row $row", uniformTops[oldFirst], s)
        }
        // 往回走:焦点行成为首行
        s = revealScroll(s, uniformTops[1], uniformTops[1] + 100, viewport, uniformTops, uniformTotal)
        assertEquals(uniformTops[1], s)
    }

    @Test fun insideTheViewportTheScrollStays() {
        assertEquals(116, revealScroll(116, 232, 332, 400, uniformTops, uniformTotal))
    }

    @Test fun scrollOnlyStopsOnLineTopsAndIsClampedToTheLastPage() {
        // 删图后内容变矮:原来停在 464 的,夹到最后一页的行顶(总高 680,视窗 400 → 能停的最深行顶是 348)
        assertEquals(348, maxScroll(uniformTops, uniformTotal, 400))
        assertEquals(348, revealScroll(464, 580, 680, 400, uniformTops, uniformTotal))
        // 内容比视窗矮 → 0
        assertEquals(0, maxScroll(listOf(0, 116), 216, 400))
        assertEquals(0, revealScroll(116, 116, 216, 400, listOf(0, 116), 216))
    }

    @Test fun enteringTheMineBlockAlsoRevealsItsTitle() {
        // 内置标题 20 / 内置行 100 / 我的标题 20 / 我的两行 100
        val lines = pickerLines(builtinCount = 3, mineCells = 6, columns = 3)
        val heights = listOf(20, 100, 20, 100, 100)
        val tops = lineTops(heights, 16)   // 0, 36, 152, 188, 304
        val total = contentHeight(tops, heights)   // 404
        // 焦点进「我的」第一行(行 3):范围从「我的」标题的顶算起
        assertEquals(152 to 288, revealRange(lines, tops, heights, 3))
        // 回到内置第一行(行 1):连「内置」标题一起露出 → 0
        assertEquals(0 to 136, revealRange(lines, tops, heights, 1))
        // 「我的」第二行(行 4)上面不是标题:只要这一行
        assertEquals(304 to 404, revealRange(lines, tops, heights, 4))
        // 视窗 260:往下走到「我的」第一行,停在「能露出行底 288」的最浅行顶 = 36(内置行顶;288 − 260 = 28 ≤ 36)
        assertEquals(36, revealScroll(0, 152, 288, 260, tops, total))
        // 再往下到「我的」第二行:底 404,需要 ≥ 144 的行顶 → 152(「我的」标题的顶),标题跟着露出
        assertEquals(152, revealScroll(36, 304, 404, 260, tops, total))
        // 往上回到内置第一行:连标题回到 0
        assertEquals(0, revealScroll(152, 0, 136, 260, tops, total))
    }

    @Test fun aBlockTallerThanTheViewportShowsItsTop() {
        assertEquals(116, revealScroll(0, 116, 316, 150, uniformTops, uniformTotal))
    }
}
