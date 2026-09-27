package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R85:首页背景三段压暗曲线(owner 在自己壁纸的模拟里选的第 6 种)。 */
class HomeFadeTest {
    @Test fun topThirtyPercentUntouched() {
        assertEquals(0f, GtvTokens.homeFadeAlpha(0f), 0f)
        assertEquals(0f, GtvTokens.homeFadeAlpha(0.30f), 0f)
    }

    @Test fun kneeAtCardRowIs85Percent() {
        assertEquals(0.85f, GtvTokens.homeFadeAlpha(0.80f), 1e-5f)
        // 2 次方加速:两个拐点正中压暗只有 1/4 × 0.85
        assertEquals(0.2125f, GtvTokens.homeFadeAlpha(0.55f), 1e-5f)
    }

    @Test fun bottomIsFullyBlackAndCurveIsMonotonic() {
        assertEquals(1f, GtvTokens.homeFadeAlpha(1f), 1e-5f)
        var last = -1f
        for (i in 0..100) {
            val a = GtvTokens.homeFadeAlpha(i / 100f)
            assertTrue(a >= last)
            last = a
        }
    }

    @Test fun kneesFallOnStops() {
        val step = 1f / GtvTokens.HOME_FADE_STOPS
        assertEquals(0f, (GtvTokens.HOME_FADE_START / step) % 1f, 1e-4f)
        assertEquals(0f, (GtvTokens.HOME_FADE_KNEE / step) % 1f, 1e-4f)
    }
}
