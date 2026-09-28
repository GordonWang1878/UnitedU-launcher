package com.uniteduone.launcher

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
 * 末尾那条 R49 边界测试看的是 modifier 链的**结构**(元素顺序),同样不渲染;它守得住
 * `gtvFocusFrameOverFade` 里的顺序,守不住有人绕开它、在别处把两者拼反。
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
        // GtvLayout.APP_FOCUS_GLOW_PEAK_INCREMENT 的 KDoc 第 2 条。
        val base = GtvLayout.focusGlowIncrement(2f)
        for (d in listOf(2f, 8f, 14f, 20f, 29f)) {
            val expected = measured.first { it.first == d }.second / 44f
            val actual = GtvLayout.focusGlowIncrement(d) / base
            assertTrue(
                "d=${d}dp:曲线给 $actual,实测比例 $expected,相对误差超过 5%",
                kotlin.math.abs(actual - expected) / expected < 0.05f,
            )
        }
    }

    @Test fun `整张实测表的相对误差都在 7% 以内(不止点名的五点)`() {
        val base = GtvLayout.focusGlowIncrement(2f)
        for ((d, lum) in measured) {
            val expected = lum / 44f
            val actual = GtvLayout.focusGlowIncrement(d) / base
            // 最大偏差在 d=5 处(约 6%),其余各点 ≤3%——半衰期 16dp 是拿表两端定标出来的
            // (44/13.7 = 3.212 倍、跨 27dp → 16.04dp),中间点是这条曲线的自然结果,不是拟合残差。
            assertTrue(
                "d=${d}dp:曲线给 $actual,实测比例 $expected,相对误差超过 7%",
                kotlin.math.abs(actual - expected) / expected < 0.07f,
            )
        }
    }

    @Test fun `半衰期 16dp——每隔 16dp 亮度减半`() {
        assertEquals(0.5f, GtvLayout.focusGlowIncrement(16f) / GtvLayout.focusGlowIncrement(0f), 0.001f)
        assertEquals(0.5f, GtvLayout.focusGlowIncrement(30f) / GtvLayout.focusGlowIncrement(14f), 0.001f)
    }

    @Test fun `紧贴描边处是峰值,铺到 APP_FOCUS_GLOW_DP 之外归零`() {
        assertEquals(GtvLayout.APP_FOCUS_GLOW_PEAK_INCREMENT, GtvLayout.focusGlowIncrement(0f), 1e-6f)
        assertEquals(0f, GtvLayout.focusGlowIncrement(GtvLayout.APP_FOCUS_GLOW_DP + 0.01f), 1e-6f)
        assertEquals(0f, GtvLayout.focusGlowIncrement(-1f), 1e-6f)
        // 收尾段把残留平滑收到 0(2026-09-21 修:原来 30dp 硬截断,实拍是一道看得见的台阶)
        assertTrue(GtvLayout.focusGlowIncrement(29f) > 0.04f)
    }

    @Test fun `圈数正好铺满、不留缝也不溢出`() {
        val rings = (GtvLayout.APP_FOCUS_GLOW_DP / GtvLayout.APP_FOCUS_GLOW_RING_DP).toInt()
        // 2026-09-21 由 15 改成 30:柔光总距离从 30dp 扩到 60dp,因为原来 30dp 处硬截断
        // 在近黑背景上是一道看得见的台阶(见 APP_FOCUS_GLOW_DP 的 KDoc)。多出来的 15 圈
        // 全在收尾段、alpha 已接近 0,只有聚焦中的那一张卡才画。
        assertEquals(30, rings)
        // 最后一圈的中心线 + 半圈宽 = 总距离
        assertEquals(
            GtvLayout.APP_FOCUS_GLOW_DP,
            (rings - 0.5f) * GtvLayout.APP_FOCUS_GLOW_RING_DP + GtvLayout.APP_FOCUS_GLOW_RING_DP / 2f,
            0.001f,
        )
    }

    // ——以下是「柔光不进布局」这条不变量:R28 改动前后,这两个函数必须逐值不变。
    // 任务原话:柔光是视觉溢出,不能加进 focusOverflow / 行高,否则行间距凭空多 APP_FOCUS_GLOW_DP(60dp)。
    @Test fun `柔光不改变 appFocusOverflow(R28 改动前的值逐字不变)`() {
        // 写死在这里当回归闸(整枝审查 C 把 APP_FOCUS_SCALE 1.105 → 1.10 后的值):
        // 153dp 宽的卡 → 153×0.05 + 2 + 2 = 11.65,108 高 → 5.4 + 4 = 9.4(R59 之前的中档宽 / 大档高;
        // R59 时 153 是大档宽、中档 137 → 10.85)。R121 起三档 150 / 122 / 86:大档宽 150 → 11.5、中档宽 122 → 10.1、
        // 大档高 84.375 → 8.219。
        assertEquals(11.65f, GtvLayout.appFocusOverflow(153f), 0.0001f)
        assertEquals(9.4f, GtvLayout.appFocusOverflow(108f), 0.01f)
        assertEquals(11.5f, GtvLayout.appFocusOverflow(GtvLayout.cardWidth(GtvCardSize.LARGE)), 0.0001f)
        assertEquals(10.1f, GtvLayout.appFocusOverflow(GtvLayout.cardWidth(GtvCardSize.MEDIUM)), 0.0001f)
        assertEquals(8.219f, GtvLayout.appFocusOverflow(GtvLayout.cardHeight(GtvCardSize.LARGE)), 0.001f)
        // 公式里只有三项:缩放溢出的一半 + gap + stroke,**没有** APP_FOCUS_GLOW_DP
        for (d in listOf(0f, 122f, 153f, 192f)) {
            // 精确相等就已经排除了「把柔光算进去」——此前这里还跟着一条 `< … + APP_FOCUS_GLOW_DP`
            // 的 assertTrue,被上一条蕴含、恒真,整枝审查 2026-09-22 删掉。
            assertEquals(
                d * (GtvLayout.APP_FOCUS_SCALE - 1f) / 2f + GtvLayout.APP_FOCUS_GAP + GtvLayout.APP_FOCUS_STROKE,
                GtvLayout.appFocusOverflow(d),
                0.0001f,
            )
        }
    }

    @Test fun `柔光不改变行高(rowPitch 与 rowVerticalPad 逐字不变)`() {
        // R25 之后的既有值(GtvLayoutTest 里也断言同一组数,这里重复一遍是为了把「柔光没有
        // 撑开行距」这件事钉在 R28 自己的测试里,以后有人改柔光时先撞到这一条)
        // R48 去掉行标题带(32.5 dp)后、R51 行距 8 → 40 后的值
        // R59 起 153 × 86 这一档叫「大」,数值不变;中档 137 另钉一组。R121 起三档 150 / 122 / 86,数值随卡高换:
        // 大 14 + 84.375 + 40 = 138.375(开标题再 + 8.21875 + 20),中 14 + 68.625 + 40 = 122.625
        assertEquals(138.375f, GtvLayout.rowPitch(GtvCardSize.LARGE, showTitles = false), 0.0001f)
        assertEquals(166.59375f, GtvLayout.rowPitch(GtvCardSize.LARGE, showTitles = true), 0.0001f)   // ui-pending #9 起标题间距 = 聚焦溢出
        assertEquals(-276.75f, GtvLayout.rowShiftY(2, GtvCardSize.LARGE, showTitles = false), 0.0001f)
        assertEquals(122.625f, GtvLayout.rowPitch(GtvCardSize.MEDIUM, showTitles = false), 0.0001f)
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
        // R121 起大档 150:58 + 150×8 + 20×7 = 1398,+ 11.5(overflow)+ 58 − 960 = 507.5
        // (R59 时大档 153 是 531.65)
        assertEquals(-507.5f, GtvLayout.rowShiftX(7, GtvCardSize.LARGE, 960f), 0.0001f)
    }

    @Test fun `收尾段——数据区边界不跳值、外缘归零、全程单调`() {
        // 2026-09-21 模拟器实拍发现的硬边:指数衰减到 30dp 还剩约 13/255 就被截断,在近黑背景上
        // 是一道看得见的台阶(实拍剖面 x=36 处 24.5 → x=28 处 14.0)。修法是数据区之外再挂一条
        // smoothstep 收到 0。这个测试钉住三件事,免得以后有人把收尾段"简化"回硬截断。
        val atData = GtvLayout.focusGlowIncrement(GtvLayout.APP_FOCUS_GLOW_DATA_DP)
        val pureExp = GtvLayout.APP_FOCUS_GLOW_PEAK_INCREMENT *
            Math.pow(2.0, -(GtvLayout.APP_FOCUS_GLOW_DATA_DP / GtvLayout.APP_FOCUS_GLOW_HALF_LIFE_DP).toDouble()).toFloat()
        assertEquals("数据区边界上收尾段必须还没起作用(smoothstep 在 t=0 处为 1)", pureExp, atData, 1e-6f)

        assertEquals("外缘必须真的到 0,不能留残值", 0f, GtvLayout.focusGlowIncrement(GtvLayout.APP_FOCUS_GLOW_DP), 1e-6f)

        var prev = Float.MAX_VALUE
        var d = 0f
        while (d <= GtvLayout.APP_FOCUS_GLOW_DP) {
            val v = GtvLayout.focusGlowIncrement(d)
            assertTrue("d=${d}dp 处 alpha 回升了($prev → $v),柔光必须全程单调递减", v <= prev + 1e-6f)
            prev = v
            d += 0.5f
        }
    }

    @Test fun `收尾段末端在近黑背景上已经看不见`() {
        // 判据来自这次的教训:别拿 alpha 小当"看不见"的证据,要换算到目标背景上的亮度增量。
        val last = GtvLayout.focusGlowIncrement(GtvLayout.APP_FOCUS_GLOW_DP - GtvLayout.APP_FOCUS_GLOW_RING_DP / 2f)
        assertTrue("末圈在近黑底上仍有 ${last * 255} /255,会看出边", last * 255f < 1f)
    }

    @Test fun `alpha 按前景亮度反推——同一个目标增量,主题色越暗 alpha 越大`() {
        // 这条钉住的是本轮最关键的一次口径更正:0.188 是**亮度增量**不是 alpha。
        // 画布 srcOver 在伽马编码空间混合,增量 ≈ alpha × 前景亮度,所以拿增量当 alpha 用,
        // 在默认 accent(亮度约 0.78)下只画出 Google 的约 78%,主题色越暗差得越多。
        val inc = GtvLayout.focusGlowIncrement(0f)
        // 分母是「前景 − 背景」的对比度,不是前景亮度本身(混合式 增量 = alpha × (fg − bg))
        val bg = GtvLayout.APP_FOCUS_GLOW_ASSUMED_BG
        assertEquals(inc / (1f - bg), GtvLayout.focusGlowAlphaFor(0f, 1f), 1e-6f)
        assertEquals(inc / (0.78f - bg), GtvLayout.focusGlowAlphaFor(0f, 0.78f), 1e-6f)
        // 越暗 alpha 越大,单调
        assertTrue(GtvLayout.focusGlowAlphaFor(0f, 0.4f) > GtvLayout.focusGlowAlphaFor(0f, 0.8f))
        // 但有闸,不会要到离谱的 alpha
        // 前景亮度低于背景假定值时对比度为负,直接给上限(而不是算出负 alpha)
        assertEquals(
            GtvLayout.APP_FOCUS_GLOW_MAX_ALPHA,
            GtvLayout.focusGlowAlphaFor(0f, GtvLayout.APP_FOCUS_GLOW_ASSUMED_BG),
            1e-6f,
        )
        // 增量为 0 的地方,alpha 也必须是 0(不能被 MAX_ALPHA 的兜底路径吃掉)
        assertEquals(0f, GtvLayout.focusGlowAlphaFor(GtvLayout.APP_FOCUS_GLOW_DP + 1f, 0.0f), 1e-6f)
    }

    // R49 边界(整枝评审 2026-09-23):聚焦框(柔光 / 聚焦描边 / 搬运描边)必须挂在淡化层外层——挂反了
    // 会被一起去饱和、压暗,框外部分还会被淡化层的离屏图层(以卡片布局框为界)裁掉。按 modifier 链的
    // 元素顺序钉住 AppCard 用的那一处组合。认「哪个元素是淡化层」靠值相等:gtvCardFade 的 lambda
    // 不捕获任何东西,两次调用得到相等的元素(第一条断言守住这个前提)。
    @Test fun `R49 边界——聚焦框在淡化层外层(modifier 链更前),描边与柔光不被淡化`() {
        val fade = Modifier.gtvCardFade()
        assertEquals("前提:gtvCardFade 两次调用得到相等的元素,否则下面的判别失效", fade, Modifier.gtvCardFade())
        for (moving in listOf(false, true)) {
            val chain = Modifier.gtvFocusFrameOverFade(focused = true, accentColor = Color.White, corner = 8.dp, moving = moving)
            val elements = chain.foldIn(emptyList<Modifier.Element>()) { acc, e -> acc + e }
            assertEquals("聚焦框 + 淡化层各一个元素", 2, elements.size)
            assertEquals("淡化层必须在链的最内层(最后)", fade, elements.last())
            assertTrue("聚焦框必须在淡化层之前(外层)", elements.first() != fade)
        }
    }
}
