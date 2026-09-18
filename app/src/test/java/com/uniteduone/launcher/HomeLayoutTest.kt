package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutTest {
    private fun eq(expected: Float, actual: Float) = assertEquals(expected, actual, 0.01f)

    @Test fun cardWidthsFollowGoogleGrid() {
        // spec §1.1:(844 − 20×(N−1)) / N
        eq(152.8f, HomeLayout.cardWidth(5))
        eq(124f, HomeLayout.cardWidth(6))
        eq(88f, HomeLayout.cardWidth(8))
    }

    @Test fun cardHeightsAre16x9() {
        eq(85.95f, HomeLayout.cardHeight(5))
        eq(69.75f, HomeLayout.cardHeight(6))
        eq(49.5f, HomeLayout.cardHeight(8))
    }

    @Test fun sixCardsFillTheSpanExactly() {
        eq(844f, HomeLayout.cardWidth(6) * 6 + HomeLayout.CARD_SPACING * 5)
        eq(844f, HomeLayout.span())
    }

    @Test fun rowVerticalPadCoversFocusGrowthPlusBorder() {
        // 放大 10% 的溢出一半 + 3dp 描边
        eq(69.75f * 0.05f + 3f, HomeLayout.rowVerticalPad(6))
    }

    @Test fun rowPitchIsTitleGapPadsCardTitleAndGap() {
        val pad = 69.75f * 0.05f + 3f
        eq(24f + 8f + 2f * pad + 69.75f + 20f, HomeLayout.rowPitch(6, showTitles = false))
        eq(HomeLayout.rowPitch(6, false) + 20f, HomeLayout.rowPitch(6, showTitles = true))
    }

    @Test fun shiftAnchorsTheFocusedRow() {
        val pitch = HomeLayout.rowPitch(6, false)
        eq(0f, HomeLayout.shift(0, 6, false))
        eq(-pitch, HomeLayout.shift(1, 6, false))
        eq(-2f * pitch, HomeLayout.shift(2, 6, false))
        eq(0f, HomeLayout.shift(-1, 6, false))   // 越界夹到第 0 行
    }

    @Test fun heroHiddenFromSecondRowOn() {
        eq(1f, HomeLayout.heroAlpha(0))
        eq(0f, HomeLayout.heroAlpha(1))
        eq(0f, HomeLayout.heroAlpha(4))
    }

    @Test fun anchorIsTwoThirdsAndScrimReachesSixtyAbove() {
        eq(360f, HomeLayout.anchorTop(540f))
        eq(240f, HomeLayout.scrimHeight(540f))
    }
}
