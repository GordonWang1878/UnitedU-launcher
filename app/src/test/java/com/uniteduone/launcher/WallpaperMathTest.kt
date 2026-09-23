package com.uniteduone.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperMathTest {

    // (nextWallpaper / rotationDelayMs 的三条随「壁纸自动切换」删掉,R61。)

    // ---- R61:旧内置图清理 + 播种钩子 ----

    @Test fun legacySeededWallpapersAreExactlyTheSixOldBuiltins() {
        assertEquals(6, LEGACY_SEEDED_WALLPAPERS.size)
        assertTrue(LEGACY_SEEDED_WALLPAPERS.all { it.startsWith("unitedu-") && it.endsWith(".jpg") })
        assertTrue("unitedu-00-neutral.jpg" in LEGACY_SEEDED_WALLPAPERS)
        assertTrue("unitedu-05-green.jpg" in LEGACY_SEEDED_WALLPAPERS)
        // 按完整文件名认,不按前缀:以后同前缀的新内置图、用户的图都不算
        assertFalse("unitedu-06-sunrise.jpg" in LEGACY_SEEDED_WALLPAPERS)
        assertFalse("legacy-wallpaper.jpg" in LEGACY_SEEDED_WALLPAPERS)
    }

    @Test fun cleanupResetsOnlyLegacyWallpaperFile() {
        assertEquals("", wallpaperFileAfterLegacyCleanup("unitedu-00-neutral.jpg"))
        assertEquals("", wallpaperFileAfterLegacyCleanup("unitedu-03-blue.jpg"))
        assertEquals("sea.jpg", wallpaperFileAfterLegacyCleanup("sea.jpg"))
        assertEquals("unitedu-06-sunrise.jpg", wallpaperFileAfterLegacyCleanup("unitedu-06-sunrise.jpg"))
        assertEquals("", wallpaperFileAfterLegacyCleanup(""))
    }

    @Test fun seedPlanSkipsAlreadySeededAndKeepsOrder() {
        assertEquals(emptyList<String>(), seedPlan(emptyList(), emptySet()))
        assertEquals(listOf("a", "b"), seedPlan(listOf("a", "b"), emptySet()))
        // 铺过的(哪怕被用户删了)不再铺;清单新加的补铺
        assertEquals(listOf("c"), seedPlan(listOf("a", "b", "c"), setOf("a", "b")))
    }

    @Test fun defaultSeedPickIsRandomAmongSeededAndNullWhenEmpty() {
        assertNull(pickDefaultSeed(emptyList(), kotlin.random.Random(1)))
        assertEquals("only.jpg", pickDefaultSeed(listOf("only.jpg"), kotlin.random.Random(1)))
        val files = listOf("a.jpg", "b.jpg", "c.jpg")
        val picks = (0 until 200).map { pickDefaultSeed(files, kotlin.random.Random(it))!! }.toSet()
        assertEquals(files.toSet(), picks)   // 三张都选得到,不是恒取第一张
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
