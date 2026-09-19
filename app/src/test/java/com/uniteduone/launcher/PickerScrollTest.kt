package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** 图片网格自算位移的首行规则(铁律 1:不用 verticalScroll)。 */
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
}
