package com.uniteduone.launcher

import androidx.compose.animation.core.Spring
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ruling R29(owner 真机反馈 Round 8,取代 R27):浏览位移走临界阻尼弹簧,三处调用点
 * (`HomeScreen` 的行 x/y 位移两处、`EditScreen` 的行内横向位移一处;R165 起编辑页纵向位移按 spec 走 200 ms FastOutSlowIn)都读
 * [Theme.browseShiftSpec],stiffness 来自同一个常量 [GtvLayout.BROWSE_SPRING_STIFFNESS]。
 *
 * **这个测试覆盖不到什么**(如实记录):它只断言那个工厂的参数与纯常量,不渲染 Compose——
 * 某个调用点哪天绕开工厂自己写 `spring(...)`/`tween(...)`,这里照样全绿。那一类回归只能靠
 * 读调用点(grep `animationSpec =` 在这四处应全是 `Theme.browseShiftSpec()`)或装机逐帧比对发现。
 */
class GtvMotionTest {
    @Test fun `browse 位移是临界阻尼弹簧,stiffness 读 BROWSE_SPRING_STIFFNESS(R29)`() {
        val spec = Theme.browseShiftSpec()
        assertEquals(GtvLayout.BROWSE_SPRING_STIFFNESS, spec.stiffness, 0f)
        assertEquals("临界阻尼,不过冲", Spring.DampingRatioNoBouncy, spec.dampingRatio, 0f)
        assertEquals(GtvLayout.BROWSE_SPRING_THRESHOLD_DP.dp, spec.visibilityThreshold)
    }

    @Test fun `R38 stiffness 220——owner 手感值,停稳(99点7%)落在 500–700 ms,不再钉 Google 的 430 ms`() {
        // R33 按 Google pts 拟合 300–400(430 ms 停稳);R38(owner Round 10)「整体向上滚动时稍微慢一点,
        // 带一点阻尼感」→ 220,主动偏离 Google。判据改为停稳时刻 500–700 ms(99.7% ≈ 322 dp 页移里差 1 dp)。
        val k = GtvLayout.BROWSE_SPRING_STIFFNESS
        assertEquals(220f, k, 0f)
        assertTrue("不该退回 Compose 默认 StiffnessMedium(1500)", k != Spring.StiffnessMedium)
        val omega = kotlin.math.sqrt(k.toDouble())
        fun x(t: Double) = 1.0 - (1.0 + omega * t) * kotlin.math.exp(-omega * t)
        val settle = (1..2000).map { it / 1000.0 }.first { x(it) > 0.997 }
        assertTrue("停稳时刻 $settle s 应在 0.5–0.7 s(owner 手感值)", settle in 0.5..0.7)
        assertTrue("430 ms(Google 停稳时刻)这里还没停稳,实际 ${x(0.43)}", x(0.43) < 0.995)
        assertTrue("但 430 ms 也不能太慢(> 95%),实际 ${x(0.43)}", x(0.43) > 0.95)
        assertTrue("尾巴要留住:250 ms 不到 92%,实际 ${x(0.25)}", x(0.25) < 0.92)
    }

    @Test fun `临界阻尼弹簧的轨迹先加速后减速(R27 的硬减速曲线做不到这一点)`() {
        // 临界阻尼弹簧的归一化位移 x(t) = 1 − (1 + ωt)·e^(−ωt):起步斜率为 0(先加速),
        // 之后单调减速。用这条解析式验证「先加速后减速」这个 R29 的全部意义所在,
        // 并与 R27 的 tv_easing_browse 对照:后者在 1/4 进度处已走 85%,前者远没有。
        val omega = kotlin.math.sqrt(GtvLayout.BROWSE_SPRING_STIFFNESS.toDouble())
        fun x(t: Double) = 1.0 - (1.0 + omega * t) * kotlin.math.exp(-omega * t)
        val dt = 0.005
        val v = (0..60).map { i -> (x((i + 1) * dt) - x(i * dt)) / dt }
        val peak = v.indices.maxByOrNull { v[it] }!!
        assertTrue("速度峰值应在中段而不是第 0 帧(先加速)", peak > 0)
        assertTrue("峰值之后应持续减速", (peak until v.lastIndex).all { v[it + 1] <= v[it] + 1e-9 })
        assertTrue("峰值之前应持续加速", (0 until peak).all { v[it + 1] >= v[it] - 1e-9 })
        // 12 帧走到 ≈ 0.995:ωt ≈ 7 处临界阻尼解析式应已收敛到 99% 以上(KDoc 里 ω 的推导基准)。
        assertTrue(x(7.0 / omega) > 0.99)
        // 对照:R27 的硬减速曲线在 1/4 进度处已走 85%,弹簧同一相对位置(ωt ≈ 1.75)只走到约 50%。
        assertTrue(x(1.75 / omega) < 0.6)
    }

    // Ruling R34(owner 反馈 Round 9):app 卡片进焦放大走减速曲线,失焦仍 150 ms——不对称。
    // Ruling R37(owner 反馈 Round 10):进焦 1200 → 600 ms(Google 原值 ~1200,owner 手感「矫枉过正」)。
    @Test fun `R34+R37 进焦放大 600 ms(Google 原值 1200,owner 手感缩短),失焦 150 ms,不对称`() {
        assertEquals(600, GtvLayout.FOCUS_SCALE_IN_MS)
        assertEquals(150, GtvLayout.FOCUS_FADE_OUT_MS)
        assertEquals("内容卡描边 / 菜单药丸仍读 card_focus 150", 150, GtvLayout.FOCUS_FADE_IN_MS)
        assertTrue("进焦必须明显慢于失焦", GtvLayout.FOCUS_SCALE_IN_MS >= 4 * GtvLayout.FOCUS_FADE_OUT_MS)
        assertEquals("终值倍率不因录像里的运动模糊改动", 1.10f, GtvLayout.APP_FOCUS_SCALE, 1e-6f)
    }

    @Test fun `R34 进焦曲线是减速型(前段快后段慢),不是 AccelerateDecelerate`() {
        val e = Theme.AppFocusScaleInEasing
        assertEquals(0f, e.transform(0f), 1e-4f)
        assertEquals(1f, e.transform(1f), 1e-4f)
        // 减速型:速度单调递减——前半段走的路程多于后半段,且 1/4 进度已过约 40%。
        val dt = 0.02f
        val v = (0 until 50).map { i -> (e.transform((i + 1) * dt) - e.transform(i * dt)) / dt }
        assertTrue("速度应单调递减(允许数值噪声)", (0 until v.lastIndex).all { v[it + 1] <= v[it] + 1e-3f })
        assertTrue("前段快:1/4 进度应已走 40% 以上,实际 ${e.transform(0.25f)}", e.transform(0.25f) > 0.4f)
        // 对照:AccelerateDecelerate 在 1/4 进度只走约 15%(两头慢),两条曲线不能互换。
        assertTrue(Theme.AppFocusEasing.transform(0.25f) < 0.2f)
    }

    @Test fun `R30+R37 放大延迟 80 ms(owner 手感,不再钉「弹簧到 80%」),按当前刚度位移约 33%`() {
        // R37:Google ~200 / R30 拟合 160 → owner 真机说迟滞过强,缩到 80。延迟时刻的位移百分比只是
        // KDoc 里的记录值,不再是设计判据;这里钉住它防止有人改了 stiffness 忘了更新 KDoc。
        assertEquals(80, GtvLayout.FOCUS_AFTER_SHIFT_DELAY_MS)
        val omega = kotlin.math.sqrt(GtvLayout.BROWSE_SPRING_STIFFNESS.toDouble())
        fun x(t: Double) = 1.0 - (1.0 + omega * t) * kotlin.math.exp(-omega * t)
        val t = GtvLayout.FOCUS_AFTER_SHIFT_DELAY_MS / 1000.0
        assertEquals("KDoc 写的是 33%(R38 stiffness 220;R37 按 350 算时是 44%)", 0.33, x(t), 0.02)
        assertTrue("放大必须在位移停稳之前起步(叠着走)", x(t) < 0.9)
    }

    @Test fun `R47 行图标焦点态(R48 前是行标题)与整页位移同一根弹簧,约 0点3 s 到 95%(Google 同起同止)`() {
        val t = Theme.rowIconFocusSpec()
        assertEquals(Theme.browseShiftSpec().stiffness, t.stiffness, 0f)
        assertEquals(Spring.DampingRatioNoBouncy, t.dampingRatio, 0f)
        val omega = kotlin.math.sqrt(t.stiffness.toDouble())
        fun x(s: Double) = 1.0 - (1.0 + omega * s) * kotlin.math.exp(-omega * s)
        val t95 = (1..2000).map { it / 1000.0 }.first { x(it) >= 0.95 }
        // Google 两段实测:位移与标题都在 +0.29–0.31 s 到 95%;R38 刚度 220 → 0.32 s。
        assertTrue("标题到 95% 的时刻 $t95 s 应与 Google 的 ~0.3 s 同量级", t95 in 0.25..0.4)
        // 焦点卡放大在标题收尾之前就起步(Google +0.02–0.07 s),不是等标题到位再放大。
        assertTrue(GtvLayout.FOCUS_AFTER_SHIFT_DELAY_MS / 1000.0 < t95 / 2)
    }

    @Test fun `R27 的 browse 曲线保留为历史记录,形状不变`() {
        assertEquals(250, GtvLayout.BROWSE_SHIFT_MS)
        assertEquals(0f, Theme.BrowseEasing.transform(0f), 1e-4f)
        assertEquals(1f, Theme.BrowseEasing.transform(1f), 1e-4f)
        assertEquals(0.85f, Theme.BrowseEasing.transform(0.25f), 0.03f)
    }
}
