package com.uniteduone.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * R165 §2.5:编辑页底 = 当前壁纸缩到 1/8 做一次模糊、上盖约 72% 深色,算一次、缓存成位图,之后每帧只画它
 * (A95L 不实时算模糊)。纯像素计算在 [editBackdropPixels],这里不起 Android。
 */
class EditBackdropTest {
    private fun px(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun ch(p: Int, c: Int) = (p shr (16 - 8 * c)) and 0xFF
    private fun lin(c: Int): Double { val s = c / 255.0; return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
    private fun lum(p: Int) = 0.2126 * lin(ch(p, 0)) + 0.7152 * lin(ch(p, 1)) + 0.0722 * lin(ch(p, 2))
    private fun contrast(a: Int, b: Int): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    @Test fun constantsAreAnEighthOfTheScreenAndTheSpecDim() {
        assertEquals(1920, EditBackdrop.SRC_W * EditBackdrop.DOWNSCALE)
        assertEquals(1080, EditBackdrop.SRC_H * EditBackdrop.DOWNSCALE)
        assertEquals(0.72f, EditBackdrop.DIM, 0f)
    }

    @Test fun uniformGreyIsDimmedToTheMixedValue() {
        val out = editBackdropPixels(IntArray(24 * 14) { px(128, 128, 128) }, 24, 14, 48, 28)
        assertEquals(48 * 28, out.size)
        // 128 × 0.28 + 底色 (10, 10, 12) × 0.72 = 43.0 / 43.0 / 44.5;灰色不受饱和度影响;抖动 ±0.5 级
        for (p in out) {
            assertEquals(0xFF, p ushr 24)
            assertTrue(ch(p, 0) in 42..44); assertTrue(ch(p, 1) in 42..44); assertTrue(ch(p, 2) in 43..45)
        }
    }

    /** Review Focus 5:最亮的壁纸(纯白)也不把底洗白——胶囊未聚焦的字(Ink.Label)≥ 3:1,标题(Ink.Primary)≥ 4.5:1。 */
    @Test fun brightestWallpaperStillLeavesTextReadable() {
        val out = editBackdropPixels(IntArray(24 * 14) { px(255, 255, 255) }, 24, 14, 48, 28)
        val label = 0xFFB0B0B0.toInt(); val primary = 0xFFF5F5F5.toInt()
        for (p in out) {
            assertTrue("底色 ${Integer.toHexString(p)} 太亮", ch(p, 0) <= 80)
            assertTrue(contrast(label, p) >= 3.0)
            assertTrue(contrast(primary, p) >= 4.5)
        }
    }

    @Test fun saturationBoostsColourBeforeDimming() {
        val out = editBackdropPixels(IntArray(24 * 14) { px(200, 60, 60) }, 24, 14, 48, 28)
        // 不加饱和度时 (200 − 60) × 0.28 ≈ 39;× 1.2 之后 ≈ 47
        assertTrue(out.all { ch(it, 0) - ch(it, 1) >= 45 })
    }

    @Test fun blurSoftensAHardEdge() {
        val w = 48; val h = 8
        val src = IntArray(w * h) { i -> if (i % w < w / 2) px(0, 0, 0) else px(255, 255, 255) }
        val out = editBackdropPixels(src, w, h, w, h)
        val mid = h / 2
        val rowR = (0 until w).map { ch(out[mid * w + it], 0) }
        assertTrue("从左到右不减:$rowR", rowR.zipWithNext().all { (a, b) -> b >= a - 1 })
        val edge = rowR[w / 2]
        assertTrue("分界处是过渡值 $edge,不是硬边", edge > rowR.first() + 5 && edge < rowR.last() - 5)
    }

    @Test fun sameInputSameOutput() {
        val src = IntArray(30 * 17) { i -> px(i % 256, (i * 7) % 256, (i * 13) % 256) }
        assertArrayEquals(editBackdropPixels(src, 30, 17, 60, 34), editBackdropPixels(src, 30, 17, 60, 34))
    }

    /** Review Focus 5(另一半):尺寸对不上的输入当场拒绝(调用方 runCatching 兜底 → 退回纯 MenuBg),不画乱码。 */
    @Test(expected = IllegalArgumentException::class)
    fun undecodableInputIsRejectedNotCrashed() {
        editBackdropPixels(IntArray(10), 24, 14, 48, 28)
    }
}
