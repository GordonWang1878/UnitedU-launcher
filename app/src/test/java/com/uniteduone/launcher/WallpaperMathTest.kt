package com.uniteduone.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperMathTest {

    @Test fun nextWallpaperCyclesInNameOrder() {
        val names = listOf("c.jpg", "a.jpg", "b.jpg")
        assertEquals("b.jpg", nextWallpaper(names, "a.jpg"))
        assertEquals("a.jpg", nextWallpaper(names, "c.jpg"))   // 末尾回到开头
    }

    @Test fun nextWallpaperUnknownCurrentStartsFromFirst() {
        assertEquals("a.jpg", nextWallpaper(listOf("b.jpg", "a.jpg"), "zzz.jpg"))
        assertEquals("a.jpg", nextWallpaper(listOf("b.jpg", "a.jpg"), ""))
    }

    @Test fun nextWallpaperEmptyAndSingle() {
        assertNull(nextWallpaper(emptyList(), "a.jpg"))
        assertEquals("only.jpg", nextWallpaper(listOf("only.jpg"), "only.jpg"))
    }

    @Test fun rotationDelayClampsToZeroAndOneInterval() {
        assertEquals(0L, rotationDelayMs(rotatedAt = 0L, intervalMs = 300_000L, nowMs = 1_000_000L))      // 早过期
        assertEquals(200_000L, rotationDelayMs(rotatedAt = 900_000L, intervalMs = 300_000L, nowMs = 1_000_000L))
        assertEquals(300_000L, rotationDelayMs(rotatedAt = 5_000_000L, intervalMs = 300_000L, nowMs = 1_000_000L)) // 时钟回拨
    }

    @Test fun blurTargetWidthIsMonotonicAndDistinctAcrossElevenSteps() {
        val widths = (0..100 step 10).map { blurTargetWidth(it) }
        assertEquals(1920, widths.first())
        assertEquals(120, widths.last())
        for (i in 1 until widths.size) assertTrue("step $i", widths[i] < widths[i - 1])
    }

    @Test fun colorMatrixIsIdentityWhenUnthemedAndUndimmed() {
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        assertArrayEquals(identity, wallpaperColorMatrix(themed = false, accentRgb = 0, brightness = 0), 1e-6f)
    }

    @Test fun colorMatrixBrightnessScalesRgbDiagonalOnly() {
        val m = wallpaperColorMatrix(themed = false, accentRgb = 0, brightness = -50)
        assertEquals(0.5f, m[0], 1e-6f)
        assertEquals(0.5f, m[6], 1e-6f)
        assertEquals(0.5f, m[12], 1e-6f)
        assertEquals(1f, m[18], 1e-6f)   // alpha 不动
        val up = wallpaperColorMatrix(themed = false, accentRgb = 0, brightness = 50)
        assertEquals(1.5f, up[0], 1e-6f)
        assertEquals(1.5f, up[12], 1e-6f)
        assertEquals(1f, up[18], 1e-6f)
        assertEquals(1.5f, wallpaperColorMatrix(themed = false, accentRgb = 0, brightness = 90)[0], 1e-6f)   // 夹到 +50
    }

    @Test fun colorMatrixThemedMapsWhiteToAccent() {
        // 白 (1,1,1) 经去色 = 亮度 1,再染色 → 恰好等于主题色
        val m = wallpaperColorMatrix(themed = true, accentRgb = 0xC0A73A, brightness = 0)
        assertEquals(0xC0 / 255f, m[0] + m[1] + m[2], 1e-4f)
        assertEquals(0xA7 / 255f, m[5] + m[6] + m[7], 1e-4f)
        assertEquals(0x3A / 255f, m[10] + m[11] + m[12], 1e-4f)
    }

    @Test fun colorMatrixThemedUsesRec709LumaLikeAndroidSetSaturation() {
        val m = wallpaperColorMatrix(themed = true, accentRgb = 0xFFFFFF, brightness = 0)
        assertEquals(0.213f, m[0], 1e-6f)
        assertEquals(0.715f, m[1], 1e-6f)
        assertEquals(0.072f, m[2], 1e-6f)
    }

    @Test fun cacheKeyChangesWhenAnyParamChanges() {
        val base = wallpaperCacheKey("/a.jpg", 1L, 2L, false, 0, 0, 0)
        assertEquals(40, base.length)
        assertEquals(base, wallpaperCacheKey("/a.jpg", 1L, 2L, false, 0, 0, 0))
        val variants = listOf(
            wallpaperCacheKey("/b.jpg", 1L, 2L, false, 0, 0, 0),
            wallpaperCacheKey("/a.jpg", 9L, 2L, false, 0, 0, 0),
            wallpaperCacheKey("/a.jpg", 1L, 3L, false, 0, 0, 0),
            wallpaperCacheKey("/a.jpg", 1L, 2L, true, 0, 0, 0),
            wallpaperCacheKey("/a.jpg", 1L, 2L, true, 0xC0A73A, 0, 0),
            wallpaperCacheKey("/a.jpg", 1L, 2L, false, 0, 10, 0),
            wallpaperCacheKey("/a.jpg", 1L, 2L, false, 0, 0, 10),
            wallpaperCacheKey("/a.jpg", 1L, 2L, false, 0, 0, -10),
        )
        for (k in variants) assertNotEquals(base, k)
    }

    @Test fun specDropsAccentWhenNotThemedAndKnowsIdentity() {
        val s = Settings(wallpaperThemed = false, wallpaperBlur = 10)
        assertEquals(0, wallpaperSpecOf(s, 0xC0A73A).accentRgb)          // 换预设不触发无谓重处理
        assertEquals(0xC0A73A, wallpaperSpecOf(s.copy(wallpaperThemed = true), 0xC0A73A).accentRgb)
        assertTrue(wallpaperSpecOf(Settings(), 0xC0A73A).isIdentity)
        assertFalse(wallpaperSpecOf(s, 0).isIdentity)
        assertFalse(wallpaperSpecOf(Settings(wallpaperThemed = true), 0).isIdentity)
    }

    /**
     * 跟随壁纸主色 + 主题化:spec **不带** accent,改打 followColor 标志由 load 就地取 Palette。
     * 这一条挡的是「每张图渲两遍」——spec 带 accent 时它就依赖异步到达的取色结果,
     * 换图那一刻先用旧主色渲一遍、取色落地后再渲一遍,还留下一份永不命中的缓存。
     */
    @Test fun specDefersAccentToPaletteWhenFollowingWallpaperColor() {
        val s = Settings(wallpaperThemed = true, followWallpaperColor = true)
        val spec = wallpaperSpecOf(s, 0xC0A73A)
        assertTrue(spec.followColor)
        assertEquals(0, spec.accentRgb)
        assertFalse(spec.isIdentity)          // themed 仍然要走管线
    }

    @Test fun specKeepsPresetAccentWhenNotFollowingWallpaperColor() {
        val s = Settings(wallpaperThemed = true, followWallpaperColor = false)
        val spec = wallpaperSpecOf(s, 0xC0A73A)
        assertFalse(spec.followColor)
        assertEquals(0xC0A73A, spec.accentRgb)
    }
}
