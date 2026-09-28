package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** R122 / R123 / R124:显示尺寸、增益图几何、HDR 比例档位的纯函数。 */
class HdrImageMathTest {

    // ---- R122:解码尺寸跟窗口走 ----

    @Test fun decodeSizeIsTheWindowSizeUpTo4k() {
        // A95L:面板 4K,界面 wm size 1920×1080 → 与 R122 之前写死的值逐位相同
        assertEquals(1920 to 1080, screenDecodeSize(1920, 1080))
        assertEquals(3840 to 2160, screenDecodeSize(3840, 2160))
        assertEquals(2560 to 1440, screenDecodeSize(2560, 1440))
        assertEquals(1280 to 720, screenDecodeSize(1280, 720))
        assertEquals(1080 to 1920, screenDecodeSize(1080, 1920))   // 竖屏不翻转
    }

    @Test fun decodeSizeCapsTheLongEdgeProportionally() {
        assertEquals(3840 to 2160, screenDecodeSize(7680, 4320))
        assertEquals(3840 to 2160, screenDecodeSize(5120, 2880))
        assertEquals(2160 to 3840, screenDecodeSize(4320, 7680))
        val (w, h) = screenDecodeSize(4096, 2160)
        assertEquals(DECODE_LONG_EDGE_CAP, w)
        assertEquals(2025, h)
    }

    @Test fun decodeSizeFallsBackWhenUnknown() {
        assertEquals(1920 to 1080, screenDecodeSize(0, 0))
        assertEquals(1920 to 1080, screenDecodeSize(-1, 1080))
        assertEquals(1920 to 1080, screenDecodeSize(1920, 0))
    }

    // ---- 中心裁剪:与 R123 之前 cropScale 里的算术逐位相同 ----

    /** R123 之前 `Wallpapers.cropScale` 的原文(底图结果不能变)。 */
    private fun legacyCrop(srcW: Int, srcH: Int, w: Int, h: Int): PixelRect {
        val scale = maxOf(w.toFloat() / srcW, h.toFloat() / srcH)
        val sw = (w / scale).toInt().coerceIn(1, srcW)
        val sh = (h / scale).toInt().coerceIn(1, srcH)
        return PixelRect((srcW - sw) / 2, (srcH - sh) / 2, sw, sh)
    }

    @Test fun centerCropMatchesTheLegacyArithmetic() {
        val rnd = Random(7)
        repeat(2000) {
            val srcW = rnd.nextInt(1, 8000)
            val srcH = rnd.nextInt(1, 8000)
            val (w, h) = listOf(1920 to 1080, 3840 to 2160, 1280 to 720).random(rnd)
            assertEquals("$srcW×$srcH → $w×$h", legacyCrop(srcW, srcH, w, h), centerCropRect(srcW, srcH, w, h))
        }
    }

    @Test fun centerCropKnownCases() {
        assertEquals(PixelRect(0, 0, 3840, 2160), centerCropRect(3840, 2160, 1920, 1080))
        assertEquals(PixelRect(0, 375, 4000, 2250), centerCropRect(4000, 3000, 1920, 1080))
        assertEquals(PixelRect(0, 0, 1920, 1080), centerCropRect(1920, 1080, 3840, 2160))   // 小图放大:整张
    }

    // ---- R123:增益图与底图同一套几何 ----

    @Test fun fullResolutionGainmapGetsExactlyTheBaseCrop() {
        val crop = centerCropRect(4000, 3000, 1920, 1080)
        assertEquals(FloatRect(0f, 375f, 4000f, 2625f), gainmapRectFor(crop, 4000, 3000, 4000, 3000))
        assertEquals(1920 to 1080, gainmapSizeFor(1920, 1080, 4000, 3000, 4000, 3000))
    }

    @Test fun quarterGainmapCropIsNotTruncated() {
        // 1/4 增益图:375 / 4 = 93.75——Android 14 自带的 createBitmap(…, matrix) 截断成 93,这里保留小数
        val crop = centerCropRect(4000, 3000, 1920, 1080)
        assertEquals(FloatRect(0f, 93.75f, 1000f, 656.25f), gainmapRectFor(crop, 4000, 3000, 1000, 750))
        assertEquals(480 to 270, gainmapSizeFor(1920, 1080, 4000, 3000, 1000, 750))
    }

    @Test fun gainmapSizeStaysWithinOnePixelAndTheOutput() {
        assertEquals(1 to 1, gainmapSizeFor(1920, 1080, 3840, 2160, 1, 1))
        assertEquals(1920 to 1080, gainmapSizeFor(1920, 1080, 1920, 1080, 3840, 2160))   // 比底图还大:封顶
        assertEquals(3840 to 2160, gainmapSizeFor(3840, 2160, 3840, 2160, 3840, 2160))
    }

    /**
     * 「同一套几何」的定义:底图上任意一点,经裁剪缩放落到输出的归一化位置,与它在增益图坐标里经增益图裁剪区
     * 落到输出增益图的归一化位置相同——着色器按归一化坐标把两张图叠在一起,这样高光才对得上。
     */
    @Test fun anyPointLandsAtTheSameNormalizedSpotInBaseAndGainmap() {
        val rnd = Random(11)
        repeat(500) {
            val baseW = rnd.nextInt(200, 6000)
            val baseH = rnd.nextInt(200, 6000)
            val div = listOf(1, 2, 4).random(rnd)
            val gainW = maxOf(1, baseW / div)
            val gainH = maxOf(1, baseH / div)
            val (outW, outH) = listOf(1920 to 1080, 3840 to 2160).random(rnd)
            val crop = centerCropRect(baseW, baseH, outW, outH)
            val g = gainmapRectFor(crop, baseW, baseH, gainW, gainH)
            val px = crop.x + rnd.nextFloat() * crop.w
            val py = crop.y + rnd.nextFloat() * crop.h
            val baseU = (px - crop.x) / crop.w
            val baseV = (py - crop.y) / crop.h
            val gx = px * gainW / baseW
            val gy = py * gainH / baseH
            val gainU = (gx - g.left) / (g.right - g.left)
            val gainV = (gy - g.top) / (g.bottom - g.top)
            assertEquals(baseU, gainU, 1e-3f)
            assertEquals(baseV, gainV, 1e-3f)
        }
    }

    @Test fun gainmapBlurWidthKeepsTheSameFractionOfTheImage() {
        val target = blurTargetWidth(20, 1920)
        assertEquals(target, gainmapBlurWidth(target, 1920, 1920))           // 全分辨率增益图:同宽
        assertEquals(Math.round(target / 4f), gainmapBlurWidth(target, 1920, 480))   // 1/4:1/4
        assertEquals(1, gainmapBlurWidth(120, 1920, 4))                      // 至少 1
        // 4K 输出:工作宽度随输出同比例翻倍(模糊掉的是画面上同样比例的细节)
        assertEquals(2 * blurTargetWidth(20, 1920).toFloat(), blurTargetWidth(20, 3840).toFloat(), 1f)
    }

    // ---- R124:HDR/SDR 比例档位 ----

    @Test fun ratioBucketIsZeroWithoutHeadroom() {
        assertEquals(0, hdrRatioBucket(1f))
        assertEquals(0, hdrRatioBucket(1.005f))
        assertEquals(0, hdrRatioBucket(0.5f))
        assertEquals(0, hdrRatioBucket(Float.NaN))
    }

    @Test fun ratioBucketIsEighthStops() {
        assertEquals(8, hdrRatioBucket(2f))
        assertEquals(16, hdrRatioBucket(4f))
        assertEquals(18, hdrRatioBucket(4.93f))
        assertTrue(hdrRatioBucket(1.02f) >= 1)   // 刚有一点余量也要与「没有」区分开
        var last = 0
        var r = 1.02f
        while (r < 16f) {
            val b = hdrRatioBucket(r)
            assertTrue("单调 $r", b >= last)
            last = b
            r *= 1.01f
        }
    }
}
