package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class CardColorTest {
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float): Triple<Float, Float, Float> =
        Triple(
            m[0] * r + m[1] * g + m[2] * b + m[4],
            m[5] * r + m[6] * g + m[7] * b + m[9],
            m[10] * r + m[11] * g + m[12] * b + m[14],
        )

    @Test fun whiteMapsToAccent() {
        val m = cardTintMatrix(0xC0A73A)
        val (r, g, b) = apply(m, 1f, 1f, 1f)
        assertEquals(0xC0 / 255f, r, 1e-4f)
        assertEquals(0xA7 / 255f, g, 1e-4f)
        assertEquals(0x3A / 255f, b, 1e-4f)
    }

    @Test fun blackStaysBlack() {
        val (r, g, b) = apply(cardTintMatrix(0xC0A73A), 0f, 0f, 0f)
        assertEquals(0f, r, 1e-6f); assertEquals(0f, g, 1e-6f); assertEquals(0f, b, 1e-6f)
    }

    @Test fun midGreyIsHalfAccent() {
        // 中灰(0.5) 去色后亮度 0.5 → 半强度 accent
        val (r, _, _) = apply(cardTintMatrix(0x7FA07A), 0.5f, 0.5f, 0.5f)
        assertEquals(0x7F / 255f * 0.5f, r, 1e-4f)
    }

    @Test fun alphaRowUntouched() {
        val m = cardTintMatrix(0x123456)
        assertEquals(1f, m[18], 1e-6f)
    }
}

class EdgeColorTest {
    @Test fun averagesOpaqueEdgePixels() {
        // 全是纯红边 → 红
        val red = IntArray(40) { 0xFFFF0000.toInt() }
        assertEquals(0xFFFF0000.toInt(), edgeColor(red))
    }

    @Test fun ignoresTransparentEdge() {
        // 全透明边 → null(别硬造底)
        val clear = IntArray(40) { 0x00000000 }
        assertEquals(null, edgeColor(clear))
    }

    @Test fun mixedMostlyOpaqueAverages() {
        // 一半纯蓝一半透明,有效过半 → 蓝
        val px = IntArray(40) { if (it % 2 == 0) 0xFF0000FF.toInt() else 0 }
        assertEquals(0xFF0000FF.toInt(), edgeColor(px))
    }
}

class OpaqueFractionTest {
    @Test fun fullBleedBannerIsMostlyOpaque() {
        val px = IntArray(100) { 0xFF123456.toInt() }
        org.junit.Assert.assertTrue(opaqueFraction(px) >= 0.8f)
    }

    @Test fun transparentEdgedLogoIsMostlyClear() {
        // 只有中间不透明,四边透明 → 低占比
        val px = IntArray(100) { if (it in 40..59) 0xFFFF0000.toInt() else 0 }
        org.junit.Assert.assertTrue(opaqueFraction(px) < 0.8f)
    }

    @Test fun emptyIsZero() {
        org.junit.Assert.assertEquals(0f, opaqueFraction(IntArray(0)), 1e-6f)
    }
}
