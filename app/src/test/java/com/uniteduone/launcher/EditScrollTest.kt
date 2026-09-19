package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑页自算纵向位移的首行规则(铁律 1:M4b 去掉 verticalScroll)。与图片网格同一条 [keepInView],
 * 多一层:首行为 0 时标题区还在屏上,放得下的行少一些。
 */
class EditScrollTest {
    @Test fun fiveLargeRowsDownAndBackUp() {
        // 大档(5 张 / 行):标题区在屏上时放得下 2 行,移出后放得下 3 行
        val firsts = mutableListOf<Int>()
        var first = 0
        for (r in listOf(0, 1, 2, 3, 4, 3, 2, 1, 0)) {
            first = editFirstRow(r, first, rowCount = 5, visibleWithHeader = 2, visibleBelow = 3)
            firsts += first
        }
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 2, 1, 0), firsts)
    }

    @Test fun theFocusedRowIsAlwaysInsideTheVisibleRange() {
        for (rowCount in 1..5) for (withHeader in 1..4) for (below in withHeader..5) {
            var first = 0
            val path = (0 until rowCount) + (rowCount - 1 downTo 0) + listOf(rowCount - 1, 0)
            for (r in path) {
                first = editFirstRow(r, first, rowCount, withHeader, below)
                val visible = if (first == 0) withHeader else below
                assertTrue("rows=$rowCount h=$withHeader b=$below r=$r first=$first", r in first until first + visible)
            }
        }
    }

    @Test fun deletingRowsPullsTheViewBackInsteadOfLeavingABlankTail() {
        // 5 行时停在首行 2(看得到 2..4);删到 3 行后焦点落在第 1 行(0 起)
        assertEquals(1, editFirstRow(focusedRow = 1, first = 2, rowCount = 3, visibleWithHeader = 2, visibleBelow = 3))
        // 删到 2 行、标题区在屏上也放得下 → 回到顶
        assertEquals(0, editFirstRow(focusedRow = 1, first = 1, rowCount = 2, visibleWithHeader = 2, visibleBelow = 3))
    }

    @Test fun threeRowsThatDoNotFitUnderTheHeaderStillReachTheLastRow() {
        // 默认三行、大档:标题区在屏上时只放得下 2 行,第 3 行必须把标题区移出去才露得全
        assertEquals(1, editFirstRow(focusedRow = 2, first = 0, rowCount = 3, visibleWithHeader = 2, visibleBelow = 3))
    }

    @Test fun rowsNotMeasuredYetMeanEverythingFits() {
        // 量出来之前调用方按「全部放得下」传(与 PickerGrid 同一约定),视窗不动
        assertEquals(0, editFirstRow(focusedRow = 4, first = 0, rowCount = 5, visibleWithHeader = 5, visibleBelow = 5))
    }
}
