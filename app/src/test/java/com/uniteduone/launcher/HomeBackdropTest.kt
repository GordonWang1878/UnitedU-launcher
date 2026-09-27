package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** R110:壁纸 + 渐变两张缓存图层的合成参数。 */
class HomeBackdropTest {
    private val floor = GtvLayout.WALLPAPER_BROWSE_ALPHA

    @Test fun restStatesHitTheEndsExactly() {
        // 首行静止(α = 1)只合成上层;其余行静止(α = 终值)只合成下层——两端必须精确落到 1 / 0,
        // 否则另一层会以一个极小的 alpha 被多合成一次。
        assertEquals(1f, backdropLerp(1f), 0f)
        assertEquals(0f, backdropLerp(GtvLayout.wallpaperAlpha(-1000f)), 0f)
        assertEquals(1f, backdropLerp(GtvLayout.wallpaperAlpha(0f)), 0f)
    }

    @Test fun lerpReproducesTheLinearWallpaperAlpha() {
        // lerp(P_B, P_1, t) 的壁纸项 = (B + (1 − B)·t)·W,必须等于原来的 α·W。
        for (i in 0..100) {
            val alpha = floor + (1f - floor) * i / 100f
            val t = backdropLerp(alpha)
            assertEquals(alpha, floor + (1f - floor) * t, 1e-5f)
        }
    }

    @Test fun wallpaperAlphaNeverLeavesTheBakedRange() {
        // 两种变暗算法、含弹簧过冲的位移,alpha 都落在 [B, 1]——图层画法的前提。
        for (s in -2000..2000 step 7) {
            val shift = s.toFloat()
            for (perRow in listOf(false, true)) {
                val a = GtvLayout.homeWallpaperAlpha(shift, 120f, perRow)
                assert(a >= floor - 1e-6f && a <= 1f + 1e-6f) { "alpha $a at shift $shift perRow $perRow" }
            }
        }
    }

    @Test fun outOfRangeIsClamped() {
        assertEquals(0f, backdropLerp(0f), 0f)
        assertEquals(1f, backdropLerp(1.5f), 0f)
    }
}
