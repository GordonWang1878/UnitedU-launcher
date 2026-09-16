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

    @Test fun colorMatrixIsIdentityAtZeroBrightness() {
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        assertArrayEquals(identity, wallpaperColorMatrix(brightness = 0), 1e-6f)
    }

    @Test fun colorMatrixBrightnessScalesRgbDiagonalOnly() {
        val m = wallpaperColorMatrix(brightness = -50)
        assertEquals(0.5f, m[0], 1e-6f)
        assertEquals(0.5f, m[6], 1e-6f)
        assertEquals(0.5f, m[12], 1e-6f)
        assertEquals(1f, m[18], 1e-6f)   // alpha 不动
        // 对角之外全零:亮度是纯缩放、不混通道——「去色 → 染主题色」那条分支已随主题化壁纸一起删掉
        for (i in m.indices) if (i !in setOf(0, 6, 12, 18)) assertEquals("m[$i]", 0f, m[i], 1e-6f)
        val up = wallpaperColorMatrix(brightness = 50)
        assertEquals(1.5f, up[0], 1e-6f)
        assertEquals(1.5f, up[12], 1e-6f)
        assertEquals(1f, up[18], 1e-6f)
        assertEquals(1.5f, wallpaperColorMatrix(brightness = 90)[0], 1e-6f)   // 夹到 +50
    }

    @Test fun cacheKeyChangesWhenAnyParamChanges() {
        val base = wallpaperCacheKey("/a.jpg", 1L, 2L, 0, 0)
        assertEquals(40, base.length)
        assertEquals(base, wallpaperCacheKey("/a.jpg", 1L, 2L, 0, 0))
        val variants = listOf(
            wallpaperCacheKey("/b.jpg", 1L, 2L, 0, 0),
            wallpaperCacheKey("/a.jpg", 9L, 2L, 0, 0),
            wallpaperCacheKey("/a.jpg", 1L, 3L, 0, 0),
            wallpaperCacheKey("/a.jpg", 1L, 2L, 10, 0),
            wallpaperCacheKey("/a.jpg", 1L, 2L, 0, 10),
            wallpaperCacheKey("/a.jpg", 1L, 2L, 0, -10),
        )
        for (k in variants) assertNotEquals(base, k)
    }

    /**
     * 主题色不进壁纸管线(2026-09-16 删掉「主题化壁纸」):换预设、开关「跟随壁纸主色」都不能让 spec 变——
     * spec 是 `Wallpaper` 的 produceState key,变了就是一次全量重处理 + 一份新缓存。
     */
    @Test fun specKnowsIdentityAndIgnoresThemeFields() {
        assertTrue(wallpaperSpecOf(Settings()).isIdentity)
        assertFalse(wallpaperSpecOf(Settings(wallpaperBlur = 10)).isIdentity)
        assertFalse(wallpaperSpecOf(Settings(wallpaperBrightness = -10)).isIdentity)
        val base = wallpaperSpecOf(Settings(wallpaperFile = "a.jpg", wallpaperBlur = 20))
        assertEquals(base, wallpaperSpecOf(Settings(wallpaperFile = "a.jpg", wallpaperBlur = 20, themePresetId = "blue")))
        assertEquals(base, wallpaperSpecOf(Settings(wallpaperFile = "a.jpg", wallpaperBlur = 20, followWallpaperColor = true)))
    }
}
