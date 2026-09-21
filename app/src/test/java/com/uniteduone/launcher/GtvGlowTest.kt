package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ruling R28(owner 真机反馈 Round 7):焦点柔光的衰减形状,以及「柔光只是绘制、不进布局」
 * 这条不变量。数值出处见 [GtvLayout.APP_FOCUS_GLOW_DP] 一族常量的 KDoc。
 *
 * **这个测试覆盖不到什么**(如实记录):它验证的是纯函数 [GtvLayout.focusGlowAlpha] 与几何
 * 公式,不渲染 Compose——`GtvFocusStroke.drawFocusGlow` 把圈画到错误的半径上、或者哪天有人
 * 把柔光从 `drawBehind` 挪进布局,这里照样全绿。那一类回归只能靠装机截图逐像素比剖面发现。
 */
class GtvGlowTest {
    /** 模拟器实测剖面(1920×1080 @ density 2.0):d = 超出描边外缘的距离 dp → 高出背景的亮度 /255。 */
    private val measured = listOf(
        2f to 44f, 5f to 36.5f, 8f to 33f, 11f to 30f, 14f to 26.6f,
        17f to 23.6f, 20f to 20.7f, 23f to 18f, 26f to 15.9f, 29f to 13.7f,
    )

    @Test fun `衰减形状贴合实测剖面(任务点名的 d=2-8-14-20-29 五点)`() {
        // 比的是**归一化之后的比例**,不是绝对亮度:alpha 与「亮度增量」之间隔着一次合成
        // (增量 ≈ alpha × (前景亮度 − 背景亮度)),而前景是用户主题色、不是固定的白
        // (B6 裁定:画法照 Google、颜色用用户色),绝对值本来就不该对上。见
        // GtvLayout.APP_FOCUS_GLOW_PEAK_ALPHA 的 KDoc 第 2 条。
        val base = GtvLayout.focusGlowAlpha(2f)
        for (d in listOf(2f, 8f, 14f, 20f, 29f)) {
            val expected = measured.first { it.first == d }.second / 44f
            val actual = GtvLayout.focusGlowAlpha(d) / base
            assertTrue(
                "d=${d}dp:曲线给 $actual,实测比例 $expected,相对误差超过 5%",
                kotlin.math.abs(actual - expected) / expected < 0.05f,
            )
        }
    }

    @Test fun `整张实测表的相对误差都在 7% 以内(不止点名的五点)`() {
        val base = GtvLayout.focusGlowAlpha(2f)
        for ((d, lum) in measured) {
            val expected = lum / 44f
            val actual = GtvLayout.focusGlowAlpha(d) / base
            // 最大偏差在 d=5 处(约 6%),其余各点 ≤3%——半衰期 16dp 是拿表两端定标出来的
            // (44/13.7 = 3.212 倍、跨 27dp → 16.04dp),中间点是这条曲线的自然结果,不是拟合残差。
            assertTrue(
                "d=${d}dp:曲线给 $actual,实测比例 $expected,相对误差超过 7%",
                kotlin.math.abs(actual - expected) / expected < 0.07f,
            )
        }
    }

    @Test fun `半衰期 16dp——每隔 16dp 亮度减半`() {
        assertEquals(0.5f, GtvLayout.focusGlowAlpha(16f) / GtvLayout.focusGlowAlpha(0f), 0.001f)
        assertEquals(0.5f, GtvLayout.focusGlowAlpha(30f) / GtvLayout.focusGlowAlpha(14f), 0.001f)
    }

    @Test fun `紧贴描边处是峰值,铺到 30dp 之外归零`() {
        assertEquals(GtvLayout.APP_FOCUS_GLOW_PEAK_ALPHA, GtvLayout.focusGlowAlpha(0f), 1e-6f)
        assertEquals(0f, GtvLayout.focusGlowAlpha(GtvLayout.APP_FOCUS_GLOW_DP + 0.01f), 1e-6f)
        assertEquals(0f, GtvLayout.focusGlowAlpha(-1f), 1e-6f)
        // 末圈还有可见度(≈0.048),是渐隐到看不见,不是画到一半被砍断
        assertTrue(GtvLayout.focusGlowAlpha(29f) > 0.04f)
    }

    @Test fun `圈数正好铺满、不留缝也不溢出`() {
        val rings = (GtvLayout.APP_FOCUS_GLOW_DP / GtvLayout.APP_FOCUS_GLOW_RING_DP).toInt()
        assertEquals(15, rings)
        // 最后一圈的中心线 + 半圈宽 = 总距离
        assertEquals(
            GtvLayout.APP_FOCUS_GLOW_DP,
            (rings - 0.5f) * GtvLayout.APP_FOCUS_GLOW_RING_DP + GtvLayout.APP_FOCUS_GLOW_RING_DP / 2f,
            0.001f,
        )
    }

    // ——以下是「柔光不进布局」这条不变量:R28 改动前后,这两个函数必须逐值不变。
    // 任务原话:柔光是视觉溢出,不能加进 focusOverflow / 行高,否则行间距凭空多 30dp。
    @Test fun `柔光不改变 appFocusOverflow(R28 改动前的值逐字不变)`() {
        // Round 4 起的既有值,写死在这里当回归闸:153dp 宽的中档卡 → 12.0325,LARGE 卡高 → 9.67
        assertEquals(12.0325f, GtvLayout.appFocusOverflow(GtvLayout.cardWidth(GtvCardSize.MEDIUM)), 0.0001f)
        assertEquals(9.67f, GtvLayout.appFocusOverflow(GtvLayout.cardHeight(GtvCardSize.LARGE)), 0.01f)
        // 公式里只有三项:缩放溢出的一半 + gap + stroke,**没有** APP_FOCUS_GLOW_DP
        for (d in listOf(0f, 122f, 153f, 192f)) {
            assertEquals(
                d * (GtvLayout.APP_FOCUS_SCALE - 1f) / 2f + GtvLayout.APP_FOCUS_GAP + GtvLayout.APP_FOCUS_STROKE,
                GtvLayout.appFocusOverflow(d),
                0.0001f,
            )
            assertTrue(
                "appFocusOverflow($d) 看起来把 30dp 柔光算进去了",
                GtvLayout.appFocusOverflow(d) < d * (GtvLayout.APP_FOCUS_SCALE - 1f) / 2f +
                    GtvLayout.APP_FOCUS_GAP + GtvLayout.APP_FOCUS_STROKE + GtvLayout.APP_FOCUS_GLOW_DP,
            )
        }
    }

    @Test fun `柔光不改变行高(rowPitch 与 rowVerticalPad 逐字不变)`() {
        // R25 之后的既有值(GtvLayoutTest 里也断言同一组数,这里重复一遍是为了把「柔光没有
        // 撑开行距」这件事钉在 R28 自己的测试里,以后有人改柔光时先撞到这一条)
        assertEquals(140.5625f, GtvLayout.rowPitch(GtvCardSize.MEDIUM, showTitles = false), 0.0001f)
        assertEquals(164.5625f, GtvLayout.rowPitch(GtvCardSize.MEDIUM, showTitles = true), 0.0001f)
        assertEquals(-281.125f, GtvLayout.rowShiftY(2, GtvCardSize.MEDIUM, showTitles = false), 0.0001f)
        for (size in GtvCardSize.values()) {
            assertEquals(
                (GtvLayout.FOCUS_OUTSET + GtvLayout.FOCUS_STROKE),
                Theme.gtvCardMetrics(size).rowVerticalPad.value,
                0.0001f,
            )
        }
    }

    @Test fun `柔光不改变行位移判据(rowShiftX 逐字不变)`() {
        assertEquals(0f, GtvLayout.rowShiftX(2, GtvCardSize.MEDIUM, 960f), 0.0001f)
        assertEquals(-532.0325f, GtvLayout.rowShiftX(7, GtvCardSize.MEDIUM, 960f), 0.0001f)
    }
}
