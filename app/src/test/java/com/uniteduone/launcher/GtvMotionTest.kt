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

    @Test fun `stiffness 落在拟合区间 300–400 内(R33 按真实 pts 重估,取代 R29 的 550–1200)`() {
        // 出处见 GtvLayout.BROWSE_SPRING_STIFFNESS 的 KDoc:screenrecord + ffprobe pts,整页位移
        // 120 ms 79% / 213 ms 93% / 285 ms 98% / ~430 ms 停稳;临界阻尼弹簧以停稳时刻为准拟合
        // stiffness ≈ 300–400,取 350。这是拟合值,允许按 owner 手感在区间内调;出了区间就不是那条轨迹。
        val k = GtvLayout.BROWSE_SPRING_STIFFNESS
        assertTrue("stiffness=$k 超出拟合区间", k in 300f..400f)
        assertTrue("不该退回 Compose 默认 StiffnessMedium(1500)", k != Spring.StiffnessMedium)
        // 停稳时刻:Google ~430 ms;这根弹簧在 430 ms 应已到 99.5% 以上,且 250 ms(R29 的 700 停下的时刻)
        // 还没到 97%——「慢慢往上走」的尾巴要留住。
        val omega = kotlin.math.sqrt(k.toDouble())
        fun x(t: Double) = 1.0 - (1.0 + omega * t) * kotlin.math.exp(-omega * t)
        assertTrue("430 ms 应已停稳,实际 ${x(0.43)}", x(0.43) > 0.995)
        assertTrue("250 ms 不该已经停稳(700 的手感),实际 ${x(0.25)}", x(0.25) < 0.97)
        assertTrue("213 ms 应在 90% 附近(Google 93%),实际 ${x(0.213)}", x(0.213) in 0.88..0.96)
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

    // Ruling R34(owner 反馈 Round 9):app 卡片进焦放大 1200 ms 减速曲线,失焦仍 150 ms——不对称。
    @Test fun `R34 进焦放大 1200 ms(focused_frame_animator_duration_ms),失焦 150 ms,不对称`() {
        assertEquals(1200, GtvLayout.FOCUS_SCALE_IN_MS)
        assertEquals(150, GtvLayout.FOCUS_FADE_OUT_MS)
        assertEquals("内容卡描边 / 菜单药丸仍读 card_focus 150", 150, GtvLayout.FOCUS_FADE_IN_MS)
        assertTrue("进焦必须明显慢于失焦", GtvLayout.FOCUS_SCALE_IN_MS > 4 * GtvLayout.FOCUS_FADE_OUT_MS)
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

    @Test fun `R30 放大延迟 = 弹簧走到 80% 的时刻,随 stiffness 350 重算为 160 ms`() {
        val omega = kotlin.math.sqrt(GtvLayout.BROWSE_SPRING_STIFFNESS.toDouble())
        fun x(t: Double) = 1.0 - (1.0 + omega * t) * kotlin.math.exp(-omega * t)
        val t = GtvLayout.FOCUS_AFTER_SHIFT_DELAY_MS / 1000.0
        assertTrue("延迟时刻位移应在 75–85%,实际 ${x(t)}", x(t) in 0.75..0.85)
        assertEquals(160, GtvLayout.FOCUS_AFTER_SHIFT_DELAY_MS)
    }

    @Test fun `R27 的 browse 曲线保留为历史记录,形状不变`() {
        assertEquals(250, GtvLayout.BROWSE_SHIFT_MS)
        assertEquals(0f, Theme.BrowseEasing.transform(0f), 1e-4f)
        assertEquals(1f, Theme.BrowseEasing.transform(1f), 1e-4f)
        assertEquals(0.85f, Theme.BrowseEasing.transform(0.25f), 0.03f)
    }
}
