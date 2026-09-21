package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ruling R27(owner 真机反馈 Round 7):浏览位移的曲线 [Theme.BrowseEasing] 与时长
 * [GtvLayout.BROWSE_SHIFT_MS]。
 *
 * **这个测试覆盖不到什么**(如实记录):它只断言常量与纯函数的数值,不渲染 Compose——
 * 某个调用点哪天又改回 `Theme.MotionInMs`/`MotionEasing`,这里照样全绿。那一类回归只能靠
 * 读调用点(`HomeScreen` 两处、`EditScreen` 一处)或装机逐帧比对发现。
 */
class GtvMotionTest {
    @Test fun `browse 位移时长 = 250ms(R27)`() {
        // 出处见 GtvLayout.BROWSE_SHIFT_MS 的 KDoc:lb_browse_rows_anim_duration = 250 与设计 token
        // gtvm3_sys_motion_duration_medium1 = 250 两个同源候选值;**不是**旧的 Theme.MotionInMs(300)。
        assertEquals(250, GtvLayout.BROWSE_SHIFT_MS)
        assertTrue("BROWSE_SHIFT_MS 不该等于 Material 通用值 300", GtvLayout.BROWSE_SHIFT_MS != Theme.MotionInMs)
    }

    @Test fun `browse 曲线两端仍是 0 和 1`() {
        assertEquals(0f, Theme.BrowseEasing.transform(0f), 1e-4f)
        assertEquals(1f, Theme.BrowseEasing.transform(1f), 1e-4f)
    }

    @Test fun `browse 曲线比 Material 通用减速曲线起步猛得多(R27 的全部意义所在)`() {
        // tv_easing_browse = cubic-bezier(0.18, 1, 0.22, 1):控制点的 y 在 18% 处就到 1,位移前段
        // 几乎一步到位、尾巴长长收住;Material 的 (0, 0, 0.2, 1) 起步慢。两者在 1/4 进度处的差距
        // 约 0.85 vs 0.57 —— 这就是 owner 说「动效很不一样」的量化形态。
        for (t in listOf(0.1f, 0.25f, 0.5f)) {
            assertTrue(
                "t=$t 时 browse(${Theme.BrowseEasing.transform(t)}) 应明显快于 motion(${Theme.MotionEasing.transform(t)})",
                Theme.BrowseEasing.transform(t) > Theme.MotionEasing.transform(t) + 0.1f,
            )
        }
        // 1/4 进度处的具体数值留痕(手算 bezier:x=0.25 落在 u≈0.465,y = 3u − 3u² + u³ ≈ 0.85)
        assertEquals(0.85f, Theme.BrowseEasing.transform(0.25f), 0.03f)
    }
}
