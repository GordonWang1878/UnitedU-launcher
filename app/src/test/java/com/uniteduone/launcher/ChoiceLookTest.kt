package com.uniteduone.launcher

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec §2.4「已满 5 行」的选择卡:变暗、仍可聚焦。Task 17 验收:原来焦点进来时照样填满主题色、只是整卡 45%,
 * 看上去还是亮的焦点态(ch-07)。焦点在已满的卡上时不填主题色,改成灰底,一眼看得出「选得到、但不能加」。
 */
class ChoiceLookTest {
    private val accent = Color(0xFFB9D8A8)

    @Test fun openCardFocusedFillsAccent() {
        val l = choiceLook(focused = true, full = false, accent = accent)
        assertEquals(accent, l.fill)
        assertEquals(1f, l.alpha)
    }

    @Test fun fullCardFocusedDoesNotFillAccent() {
        val l = choiceLook(focused = true, full = true, accent = accent)
        assertNotEquals("已满的卡有焦点时不能像可用的卡那样填主题色", accent, l.fill)
        assertEquals(Ink.Primary, l.ink)
    }

    @Test fun fullCardIsDimmerThanOpenCardInBothStates() {
        for (focused in listOf(false, true)) {
            val open = choiceLook(focused, full = false, accent = accent)
            val full = choiceLook(focused, full = true, accent = accent)
            assertTrue("focused=$focused", full.alpha < open.alpha)
        }
    }

    @Test fun idleCardsShareTheGlassFill() {
        assertEquals(choiceLook(false, false, accent).fill, choiceLook(false, true, accent).fill)
    }
}
