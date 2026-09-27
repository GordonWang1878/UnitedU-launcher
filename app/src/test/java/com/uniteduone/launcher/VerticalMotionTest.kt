package com.uniteduone.launcher

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vertical-motion 研究(2026-09-27,`docs/design/vertical-motion/README.md`):首页上下换行的曲线开关
 * [GtvLayout.HOME_VERTICAL_MOTION] 与壁纸逐行插值开关 [GtvLayout.WALLPAPER_DIM_PER_ROW]。
 *
 * 默认两者都必须等于现状——这里第一组断言钉的就是「开关装上了,但行为没变」。
 * 同 [GtvMotionTest]:只断言工厂参数与纯函数,不渲染 Compose。
 */
class VerticalMotionTest {
    private val sizes = GtvCardSize.values().toList()

    @Test fun `默认 = 现状——纵向曲线是 R38 那根弹簧,行图标同一根,壁纸不逐行插值`() {
        assertEquals(GtvLayout.HomeVerticalMotion.SPRING_220, GtvLayout.HOME_VERTICAL_MOTION)
        assertEquals(Theme.browseShiftSpec(), Theme.homeVerticalShiftSpec())
        assertEquals(Theme.rowIconFocusSpec(), Theme.homeRowIconSpec())
        assertFalse(GtvLayout.WALLPAPER_DIM_PER_ROW)
        // 壁纸逐行插值跟着曲线开关走:现状关,换成任何新曲线就开(切推荐方案只改一个常量)
        assertEquals(GtvLayout.HOME_VERTICAL_MOTION != GtvLayout.HomeVerticalMotion.SPRING_220, GtvLayout.WALLPAPER_DIM_PER_ROW)
        for (s in listOf(0f, -40f, -131.0625f, -200f, -262.125f, -600f)) {
            assertEquals(GtvLayout.wallpaperAlpha(s), GtvLayout.homeWallpaperAlpha(s, 131.0625f), 0f)
        }
    }

    @Test fun `方案 A——tween 450 FastOutSlowIn,位移与行图标同一条`() {
        val m = GtvLayout.HomeVerticalMotion.TWEEN_450
        assertEquals(450, GtvLayout.VMOTION_A_MS)
        val s = Theme.homeVerticalShiftSpec(m) as TweenSpec
        assertEquals(450, s.durationMillis)
        assertEquals(0, s.delay)
        assertEquals(FastOutSlowInEasing, s.easing)
        val i = Theme.homeRowIconSpec(m) as TweenSpec
        assertEquals(s.durationMillis, i.durationMillis)
        assertEquals(s.easing, i.easing)
    }

    @Test fun `方案 B——tween 550 CubicBezier(0点35, 0, 0点15, 1)`() {
        val m = GtvLayout.HomeVerticalMotion.TWEEN_550_SOFT
        assertEquals(550, GtvLayout.VMOTION_B_MS)
        assertEquals(CubicBezierEasing(0.35f, 0f, 0.15f, 1f), Theme.HomeVerticalSoftEasing)
        val s = Theme.homeVerticalShiftSpec(m) as TweenSpec
        assertEquals(550, s.durationMillis)
        assertEquals(Theme.HomeVerticalSoftEasing, s.easing)
        val i = Theme.homeRowIconSpec(m) as TweenSpec
        assertEquals(550, i.durationMillis)
        assertEquals(Theme.HomeVerticalSoftEasing, i.easing)
    }

    @Test fun `方案 C——临界阻尼弹簧,刚度 110`() {
        val m = GtvLayout.HomeVerticalMotion.SPRING_110
        assertEquals(110f, GtvLayout.VMOTION_C_STIFFNESS, 0f)
        val s = Theme.homeVerticalShiftSpec(m) as SpringSpec
        assertEquals(110f, s.stiffness, 0f)
        assertEquals(Spring.DampingRatioNoBouncy, s.dampingRatio, 0f)
        assertEquals(GtvLayout.BROWSE_SPRING_THRESHOLD_DP.dp, s.visibilityThreshold)
        val i = Theme.homeRowIconSpec(m) as SpringSpec
        assertEquals(110f, i.stiffness, 0f)
        assertEquals(Spring.DampingRatioNoBouncy, i.dampingRatio, 0f)
    }

    @Test fun `README 的曲线数据——A 起步比现状软但到 95% 用时几乎一样,B 更慢,C 尾巴最长`() {
        fun spring(k: Double) = { t: Double -> val w = kotlin.math.sqrt(k); 1 - (1 + w * t) * kotlin.math.exp(-w * t) }
        fun tween(ms: Int, e: androidx.compose.animation.core.Easing) =
            { t: Double -> e.transform((t * 1000 / ms).coerceIn(0.0, 1.0).toFloat()).toDouble() }
        val cur = spring(GtvLayout.BROWSE_SPRING_STIFFNESS.toDouble())
        val a = tween(GtvLayout.VMOTION_A_MS, FastOutSlowInEasing)
        val b = tween(GtvLayout.VMOTION_B_MS, Theme.HomeVerticalSoftEasing)
        val c = spring(GtvLayout.VMOTION_C_STIFFNESS.toDouble())
        fun t95(f: (Double) -> Double) = (1..2000).map { it / 1000.0 }.first { f(it) >= 0.95 }
        // 80 ms(= FOCUS_AFTER_SHIFT_DELAY_MS,焦点卡开始放大的时刻)时已走的比例
        val at = GtvLayout.FOCUS_AFTER_SHIFT_DELAY_MS / 1000.0
        assertEquals(0.333, cur(at), 0.01)
        assertEquals(0.10, a(at), 0.02)
        assertEquals(0.086, b(at), 0.02)
        assertEquals(0.205, c(at), 0.01)
        assertEquals(0.320, t95(cur), 0.005)
        assertEquals(0.327, t95(a), 0.01)
        assertEquals(0.390, t95(b), 0.01)
        assertEquals(0.452, t95(c), 0.005)
    }

    @Test fun `壁纸逐行插值——各行静止 alpha 与现状逐值相同`() {
        for (size in sizes) for (titles in listOf(false, true)) {
            val pitch = GtvLayout.rowPitch(size, titles)
            for (row in -1..6) {
                val s = GtvLayout.rowShiftY(row, size, titles)
                assertEquals("$size titles=$titles row=$row",
                    GtvLayout.wallpaperAlpha(s), GtvLayout.wallpaperAlphaPerRow(s, pitch), 1e-5f)
            }
        }
    }

    @Test fun `壁纸逐行插值——每次换行按位移进度匀速变化,单调,不低于 0点2`() {
        val pitch = GtvLayout.rowPitch(GtvCardSize.MEDIUM, false)   // 131.0625
        for (n in 0..3) {
            val a0 = GtvLayout.wallpaperAlpha(n * pitch)
            val a1 = GtvLayout.wallpaperAlpha((n + 1) * pitch)
            for (q in listOf(0.25f, 0.5f, 0.75f)) {
                assertEquals("行 $n → ${n + 1} 进度 $q", a0 + (a1 - a0) * q,
                    GtvLayout.wallpaperAlphaPerRow(-(n + q) * pitch, pitch), 1e-5f)
            }
        }
        // 行 1 → 2 的中点:现状已暗到底(0.2),逐行插值还在半路(0.454 与 0.2 的中点)
        val mid = -1.5f * pitch
        assertEquals(0.2f, GtvLayout.wallpaperAlpha(mid), 1e-6f)
        assertEquals((GtvLayout.wallpaperAlpha(-pitch) + 0.2f) / 2f, GtvLayout.wallpaperAlphaPerRow(mid, pitch), 1e-5f)
        val samples = (0..60).map { GtvLayout.wallpaperAlphaPerRow(-it * 10f, pitch) }
        assertTrue(samples.zipWithNext().all { (x, y) -> y <= x + 1e-6f })
        assertTrue(samples.all { it >= GtvLayout.WALLPAPER_BROWSE_ALPHA - 1e-6f })
        // 符号无关;pitch 非正时退回 wallpaperAlpha
        assertEquals(GtvLayout.wallpaperAlphaPerRow(-100f, pitch), GtvLayout.wallpaperAlphaPerRow(100f, pitch), 0f)
        assertEquals(GtvLayout.wallpaperAlpha(-150f), GtvLayout.wallpaperAlphaPerRow(-150f, 0f), 0f)
        assertEquals(GtvLayout.wallpaperAlphaPerRow(-150f, pitch), GtvLayout.homeWallpaperAlpha(-150f, pitch, perRow = true), 0f)
    }
}
