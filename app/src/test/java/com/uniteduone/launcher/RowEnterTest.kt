package com.uniteduone.launcher

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Ruling R129**:首页换行时新焦点行晚一拍淡入(照 Google 实测,
 * `docs/design/vertical-motion/2026-09-29-google-row-entry.md`)。只断言常量、动画规格与纯函数
 * [GtvLayout.rowEnterStart],不渲染 Compose(同 [VerticalMotionTest])。
 */
class RowEnterTest {
    private val sizes = GtvCardSize.values().toList()
    private val heights = listOf(540f, 720f, 1080f)

    @Test fun `R129 数值——延迟 140、淡入 250 FastOutSlowIn、门槛 0点5`() {
        assertEquals(140, GtvLayout.ROW_ENTER_DELAY_MS)
        assertEquals(250, GtvLayout.ROW_ENTER_FADE_MS)
        assertEquals(0.5f, GtvLayout.ROW_ENTER_VISIBLE_MIN, 0f)
        val s = Theme.homeRowEnterSpec()
        assertEquals(250, s.durationMillis)
        assertEquals(140, s.delay)
        assertEquals(FastOutSlowInEasing, s.easing)
        // 与整页位移同一次按键起算、位移曲线不变(R96 仍是 450 ms)
        assertEquals(450, GtvLayout.VMOTION_A_MS)
    }

    @Test fun `R129 曲线——140 ms 前停在起点,0点9 约在 300 ms,390 ms 到满`() {
        val anim = TargetBasedAnimation(Theme.homeRowEnterSpec(), Float.VectorConverter, 0f, 1f)
        fun at(ms: Long) = anim.getValueFromNanos(ms * 1_000_000L)
        assertEquals(0f, at(0), 0f)
        assertEquals(0f, at(140), 0.001f)
        assertEquals(0.5f, at(228), 0.02f)
        assertEquals(0.9f, at(299), 0.02f)
        assertEquals(1f, at(390), 0.001f)
        assertEquals(390L, anim.durationNanos / 1_000_000L)
        // 单调不减
        var prev = 0f
        for (ms in 0L..400L step 10) { val v = at(ms); assertTrue(v >= prev - 1e-6f); prev = v }
    }

    @Test fun `下键——下一行静止时恒在屏外,从 0 淡入(三档 × 标题开关 × 三种屏高 × 前几行)`() {
        for (s in sizes) for (t in listOf(false, true)) for (h in heights) for (n in 0..4) {
            // 焦点在行 n、页面静止,按下键:行 n + 1 的卡顶 = 焦点线 + pitch(R52:恒在屏外)
            val top = GtvLayout.restCardTop(n + 1, s, t, h) + GtvLayout.rowShiftY(n, s, t)
            assertTrue("$s $t $h $n top=$top", top >= h)
            assertEquals(0f, GtvLayout.rowEnterStart(top, h, multiplier = 1f)!!, 0f)
        }
    }

    @Test fun `上键——上一行静止时在焦点线上方一行、全亮,按规则不淡入`() {
        // R52 的焦点线在屏幕下部,焦点行正上方那一行静止卡顶 = 焦点线 − pitch,远在淡出带(70–110)之下。
        // 所以「换行前看不见」在单按上键时不成立;只有按住 / 连按、上一行还在淡出带里时才会淡入(下一条)。
        for (s in sizes) for (t in listOf(false, true)) for (h in heights) for (n in 1..4) {
            val top = GtvLayout.restCardTop(n - 1, s, t, h) + GtvLayout.rowShiftY(n, s, t)
            assertTrue("$s $t $h $n top=$top", top >= 110f)
            assertNull(GtvLayout.rowEnterStart(top, h, multiplier = 1f))
        }
    }

    @Test fun `淡出带里——透明度低于 0点5 才淡入,起点 = 换行前的透明度`() {
        // 卡顶 80:topFadeAlpha 0.25 → 从 0.25 起步
        assertEquals(0.25f, GtvLayout.rowEnterStart(80f, 540f, 1f)!!, 1e-5f)
        // 卡顶 89.9:≈ 0.4975 → 仍淡入;90:0.5 → 看得见,不淡入
        assertNotNull(GtvLayout.rowEnterStart(89.9f, 540f, 1f))
        assertNull(GtvLayout.rowEnterStart(90f, 540f, 1f))
        // 卡顶在顶栏之上 / 屏幕上方之外:0
        assertEquals(0f, GtvLayout.rowEnterStart(40f, 540f, 1f)!!, 0f)
        assertEquals(0f, GtvLayout.rowEnterStart(-200f, 540f, 1f)!!, 0f)
        // 「新应用」提示显示时零点下移到 92:卡顶 95 → (95 − 92) / 18
        assertEquals(3f / 18f, GtvLayout.rowEnterStart(95f, 540f, 1f, clearOfNewAppsHint = true)!!, 1e-5f)
        assertNull(GtvLayout.rowEnterStart(95f, 540f, 1f, clearOfNewAppsHint = false))
    }

    @Test fun `连按——正在淡入的行按实际透明度判,看得见就不打断,看不见就从当前值接着走`() {
        // 在屏上、位置全亮,乘子 0.3(上一段淡入刚起步)→ 从 0.3 接着走,不掉回 0
        assertEquals(0.3f, GtvLayout.rowEnterStart(300f, 540f, 0.3f)!!, 1e-6f)
        // 乘子 0.7 → 看得见,返回 null(那一段淡入自己走到 1)
        assertNull(GtvLayout.rowEnterStart(300f, 540f, 0.7f))
        // 在屏外、乘子 0.8 → 起点 0(在屏外,拉回 0 看不见)
        assertEquals(0f, GtvLayout.rowEnterStart(560f, 540f, 0.8f)!!, 0f)
        // 恰在底边也算屏外
        assertEquals(0f, GtvLayout.rowEnterStart(540f, 540f, 1f)!!, 0f)
    }

    @Test fun `不闪——凡是要淡入的,起点与换行前画出来的透明度逐值相等,且低于门槛`() {
        // 换行前:这一行不是焦点行,画的是 topFadeAlpha × 乘子(在屏外则什么都看不见);
        // 换行后:焦点行的位置透明度短路成 1,画的是 1 × 起点。两者必须相等,否则就是「闪一下」。
        val tops = (-100..600 step 5).map { it.toFloat() }
        val mults = listOf(0f, 0.1f, 0.3f, 0.49f, 0.5f, 0.8f, 1f)
        for (h in heights) for (top in tops) for (m in mults) for (hint in listOf(false, true)) {
            val start = GtvLayout.rowEnterStart(top, h, m, hint) ?: continue
            val before = if (top >= h) 0f else GtvLayout.topFadeAlpha(top, hint) * m
            assertEquals("h=$h top=$top m=$m", before, start, 1e-6f)
            assertTrue(start < GtvLayout.ROW_ENTER_VISIBLE_MIN)
        }
    }

    // ---- R129b:下键时旧焦点行先淡出、停住,再与新行一起浮现 ----

    @Test fun `R129b 只在下键时旧行淡出`() {
        assertEquals(50, GtvLayout.ROW_EXIT_FADE_MS)
        assertTrue(GtvLayout.rowExitOnChange(oldRow = 0, newRow = 1))
        assertTrue(GtvLayout.rowExitOnChange(oldRow = 2, newRow = 3))
        assertTrue(!GtvLayout.rowExitOnChange(oldRow = 1, newRow = 0))   // 上键不加
        assertTrue(!GtvLayout.rowExitOnChange(oldRow = 1, newRow = 1))
    }

    @Test fun `R129b 曲线——50 ms 线性到 0,停到 140,之后与新行的淡入逐点相同,390 到满`() {
        val exit = TargetBasedAnimation(Theme.homeRowExitSpec(), Float.VectorConverter, 1f, 1f)
        val enter = TargetBasedAnimation(Theme.homeRowEnterSpec(), Float.VectorConverter, 0f, 1f)
        fun ex(ms: Long) = exit.getValueFromNanos(ms * 1_000_000L)
        fun en(ms: Long) = enter.getValueFromNanos(ms * 1_000_000L)
        assertEquals(390L, exit.durationNanos / 1_000_000L)
        assertEquals(1f, ex(0), 1e-6f)
        assertEquals(0.5f, ex(25), 1e-3f)
        assertEquals(0f, ex(50), 1e-6f)
        assertEquals(0f, ex(100), 1e-6f)
        assertEquals(0f, ex(140), 1e-6f)
        for (ms in 140L..390L step 10) assertEquals("ms=$ms", en(ms), ex(ms), 1e-3f)
        assertEquals(1f, ex(390), 1e-6f)
    }

    @Test fun `R129b 正在淡入的旧行从当前值往下走,不先跳高`() {
        for (from in listOf(0f, 0.05f, 0.3f, 0.7f)) {
            val exit = TargetBasedAnimation(Theme.homeRowExitSpec(), Float.VectorConverter, from, 1f)
            var prev = from
            for (ms in 0L..140L step 5) {
                val v = exit.getValueFromNanos(ms * 1_000_000L)
                assertTrue("from=$from ms=$ms v=$v", v <= prev + 1e-6f && v <= from + 1e-6f)
                prev = v
            }
            assertEquals(0f, exit.getValueFromNanos(50L * 1_000_000L), 1e-6f)
            assertEquals(1f, exit.getValueFromNanos(390L * 1_000_000L), 1e-6f)
        }
    }
}
