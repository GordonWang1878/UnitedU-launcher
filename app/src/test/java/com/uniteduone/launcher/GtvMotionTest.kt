package com.uniteduone.launcher

import androidx.compose.animation.core.Spring
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ruling R29(owner 真机反馈 Round 8,取代 R27):浏览位移走临界阻尼弹簧,四处调用点
 * (`HomeScreen` 的行 x/y 位移两处、`EditScreen` 的纵向位移与行内横向位移两处)都读
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

    @Test fun `stiffness 落在拟合区间 550–1200 内(R29 的证据边界)`() {
        // 出处见 GtvLayout.BROWSE_SPRING_STIFFNESS 的 KDoc:12 帧轨迹按 40–60 fps 折算 ω ≈ 23–35 rad/s,
        // stiffness = ω² ≈ 550–1200。这是拟合值,允许按 owner 手感在区间内调;出了区间就不是那条轨迹。
        val k = GtvLayout.BROWSE_SPRING_STIFFNESS
        assertTrue("stiffness=$k 超出拟合区间", k in 550f..1200f)
        assertTrue("不该退回 Compose 默认 StiffnessMedium(1500)", k != Spring.StiffnessMedium)
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

    @Test fun `R27 的 browse 曲线保留为历史记录,形状不变`() {
        assertEquals(250, GtvLayout.BROWSE_SHIFT_MS)
        assertEquals(0f, Theme.BrowseEasing.transform(0f), 1e-4f)
        assertEquals(1f, Theme.BrowseEasing.transform(1f), 1e-4f)
        assertEquals(0.85f, Theme.BrowseEasing.transform(0.25f), 0.03f)
    }
}
