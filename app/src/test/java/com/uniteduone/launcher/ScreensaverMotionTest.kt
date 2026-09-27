package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** Ruling R95:屏保推拉摇移的随机参数与过渡时长。 */
class ScreensaverMotionTest {
    private val eps = 1e-4f

    @Test fun everyComboAndPanNeverShowsAnEdge() {
        for (combo in 0 until ScreensaverMotion.COMBOS) {
            for (pan in listOf(ScreensaverMotion.PAN_MIN, 0.065f, ScreensaverMotion.PAN_MAX)) {
                val m = ScreensaverMotion.motionFor(combo, pan)
                assertTrue("combo $combo pan $pan: $m", m.covers())
                // 中途也不露边(凸性论证之外再抽样核一遍)
                for (k in 0..20) {
                    val p = k / 20f
                    val room = (m.scaleAt(p) - 1f) / 2f + 1e-6f
                    assertTrue(abs(m.xAt(p)) <= room && abs(m.yAt(p)) <= room)
                }
            }
        }
    }

    @Test fun thousandRandomMotionsAllCoverAndStayInRange() {
        val r = Random(42)
        var prev: KenBurns? = null
        repeat(1000) {
            val m = ScreensaverMotion.random(r, prev)
            assertTrue(m.covers())
            // 平移距离 = 画面的 5–8%,两条轴一样(沿对角线走)
            val dx = abs(m.endX - m.startX)
            val dy = abs(m.endY - m.startY)
            assertTrue("dx $dx", dx >= ScreensaverMotion.PAN_MIN - eps && dx <= ScreensaverMotion.PAN_MAX + eps)
            assertEquals(dx, dy, eps)
            // 缩放幅度 ≥ 15%(推或拉)
            val ratio = maxOf(m.startScale, m.endScale) / minOf(m.startScale, m.endScale)
            assertTrue("ratio $ratio", ratio >= 1.149f)
            assertTrue(minOf(m.startScale, m.endScale) >= 1f + ScreensaverMotion.PAN_MAX / 2f - eps)
            prev = m
        }
    }

    @Test fun consecutivePhotosNeverRepeatTheCombo() {
        val r = Random(7)
        var prev = ScreensaverMotion.random(r)
        repeat(500) {
            val m = ScreensaverMotion.random(r, prev)
            assertNotEquals(prev.combo, m.combo)
            prev = m
        }
    }

    @Test fun allEightCombosShowUp() {
        val r = Random(1)
        val seen = (0 until 400).map { ScreensaverMotion.random(r).combo }.toSet()
        assertEquals((0 until 8).toSet(), seen)
    }

    @Test fun comboBitsMeanZoomDirectionAndDiagonal() {
        val zoomIn = ScreensaverMotion.motionFor(0, 0.08f)
        assertEquals(ScreensaverMotion.ZOOM_NEAR, zoomIn.startScale, eps)
        assertEquals(ScreensaverMotion.ZOOM_FAR, zoomIn.endScale, eps)
        assertTrue(zoomIn.endX > zoomIn.startX && zoomIn.endY > zoomIn.startY)   // 往右下
        val zoomOutUpLeft = ScreensaverMotion.motionFor(7, 0.08f)
        assertTrue(zoomOutUpLeft.startScale > zoomOutUpLeft.endScale)
        assertTrue(zoomOutUpLeft.endX < zoomOutUpLeft.startX && zoomOutUpLeft.endY < zoomOutUpLeft.startY)
    }

    @Test fun pathLeansTowardTheZoomedEnd() {
        // 推近(1.06 → 1.22)走 8%:近端余量只有 3%,起点夹在 −0.03,终点 +0.05
        val (a, b) = ScreensaverMotion.placeOnAxis(1.06f, 1.22f, 0.08f)
        assertEquals(-0.03f, a, eps)
        assertEquals(0.05f, b, eps)
        // 余量够时以中心对称
        val (c, d) = ScreensaverMotion.placeOnAxis(1.2f, 1.2f, 0.08f)
        assertEquals(-0.04f, c, eps)
        assertEquals(0.04f, d, eps)
    }

    @Test fun infeasiblePanIsShortenedNotExposed() {
        // 两端都几乎不放大:走不完 8%,退而夹进余量,绝不露边
        val (a, b) = ScreensaverMotion.placeOnAxis(1.02f, 1.02f, 0.08f)
        assertTrue(abs(a) <= 0.01f + eps && abs(b) <= 0.01f + eps)
    }

    @Test fun transitionTimings() {
        assertEquals(1400, ScreensaverMotion.TRANSITION_MS)
        assertTrue(ScreensaverMotion.TRANSITION_MS in 1200..1500)
        assertEquals(ScreensaverTransition.CROSSFADE, ScreensaverMotion.TRANSITION)
        // 运动覆盖整个间隔 + 下一张淡入的那段
        assertEquals(31_400L, ScreensaverMotion.durationMs(30_000L))
        assertEquals(61_400L, ScreensaverMotion.durationMs(60_000L))
    }

    @Test fun zoomFadeSettlesToOneAndNeverShrinks() {
        assertEquals(1f, ScreensaverMotion.transitionScale(ScreensaverTransition.CROSSFADE, 0f), eps)
        assertEquals(ScreensaverMotion.ZOOM_FADE_FROM, ScreensaverMotion.transitionScale(ScreensaverTransition.ZOOM_FADE, 0f), eps)
        assertEquals(1f, ScreensaverMotion.transitionScale(ScreensaverTransition.ZOOM_FADE, 1f), eps)
        for (k in 0..10) assertTrue(ScreensaverMotion.transitionScale(ScreensaverTransition.ZOOM_FADE, k / 10f) >= 1f)
    }

    @Test fun decodePlanStopsAtScreenCover() {
        // 4K 16:9:2 的幂正好落到 1920×1080,不再缩
        assertEquals(Triple(2, 0, 0), ScreensaverMotion.decodePlan(3840, 2160, 1920, 1080))
        // 3000×2000:2 的幂采样落不下去(1500 < 1920),原尺寸 F16 要 48 MB;按宽 3000 → 1920 缩成 1920×1280
        assertEquals(Triple(1, 3000, 1920), ScreensaverMotion.decodePlan(3000, 2000, 1920, 1080))
        // 竖图 2500×3500:按宽缩(宽决定 cover)
        assertEquals(Triple(1, 2500, 1920), ScreensaverMotion.decodePlan(2500, 3500, 1920, 1080))
        // 超宽全景 6000×1500:采样 1(3000×750 高度不够),按高 1500 → 1080
        assertEquals(Triple(1, 1500, 1080), ScreensaverMotion.decodePlan(6000, 1500, 1920, 1080))
        // 4032×3024 手机照片:采样 2 → 2016×1512,只比屏宽大 5%,不值得再缩一遍
        assertEquals(Triple(2, 0, 0), ScreensaverMotion.decodePlan(4032, 3024, 1920, 1080))
        // 比屏幕小:不放大
        assertEquals(Triple(1, 0, 0), ScreensaverMotion.decodePlan(1280, 720, 1920, 1080))
        // 读不出尺寸
        assertEquals(Triple(1, 0, 0), ScreensaverMotion.decodePlan(0, 0, 1920, 1080))
    }
}
