package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ui-pending #16:系统设置的解析 / 格式化纯函数(`SystemStatus.kt`);R56 起屏保三项合成一行摘要。 */
class SystemStatusTest {

    @Test fun enabledFlagOnlyAcceptsZeroAndOne() {
        assertEquals(true, parseEnabledFlag("1"))
        assertEquals(false, parseEnabledFlag("0"))
        assertEquals(true, parseEnabledFlag(" 1\n"))
        assertNull(parseEnabledFlag(null))
        assertNull(parseEnabledFlag(""))
        assertNull(parseEnabledFlag("true"))
    }

    @Test fun dreamComponentParsing() {
        assertEquals(
            "com.uniteduone.launcher" to "com.uniteduone.launcher.UnitedUDream",
            parseDreamComponent("com.uniteduone.launcher/.UnitedUDream"),
        )
        assertEquals(
            "com.google.android.apps.tv.dreamx" to "com.google.android.apps.tv.dreamx.service.Backdrop",
            parseDreamComponent("com.google.android.apps.tv.dreamx/.service.Backdrop"),
        )
        // 全名类、多个组件取第一个
        assertEquals("a.b" to "x.y.Z", parseDreamComponent("a.b/x.y.Z,c.d/.E"))
        assertEquals("c.d" to "c.d.E", parseDreamComponent(" ,c.d/.E"))
        assertNull(parseDreamComponent(null))
        assertNull(parseDreamComponent(""))
        assertNull(parseDreamComponent("no-slash"))
        assertNull(parseDreamComponent("/.Cls"))
        assertNull(parseDreamComponent("pkg/"))
    }

    @Test fun timeoutUnits() {
        assertEquals(TimeoutDisplay.Minutes(5), timeoutDisplay(300_000))
        assertEquals(TimeoutDisplay.Minutes(63), timeoutDisplay(3_780_000))
        assertEquals(TimeoutDisplay.Hours(1), timeoutDisplay(3_600_000))
        assertEquals(TimeoutDisplay.Hours(4), timeoutDisplay(14_400_000))
        assertEquals(TimeoutDisplay.Seconds(15), timeoutDisplay(15_000))
        assertEquals(TimeoutDisplay.Seconds(2), timeoutDisplay(1_500))
        assertEquals(TimeoutDisplay.Never, timeoutDisplay(Int.MAX_VALUE.toLong()))
        assertNull(timeoutDisplay(null))
        assertNull(timeoutDisplay(0))
        assertNull(timeoutDisplay(-1))
    }

    @Test fun unitScaleTolerance() {
        assertTrue(isUnitScale(1f))
        assertTrue(isUnitScale("1.0".toFloat()))
        assertFalse(isUnitScale(1.25f))
        assertFalse(isUnitScale(0f))
    }

    @Test fun animNoticeRules() {
        assertNull(animScaleNotice(1f, 1f, 1f))
        // 窗口 / 过渡读不到按 1× 算,不单独出行
        assertNull(animScaleNotice(1f, null, null))
        assertEquals(AnimScaleNotice.Animator(1.25f), animScaleNotice(1.25f, 1f, 1f))
        // 动画程序优先:三项都不是 1 时说动画程序那一项
        assertEquals(AnimScaleNotice.Animator(0.5f), animScaleNotice(0.5f, 2f, 2f))
        assertEquals(AnimScaleNotice.WindowOnly(0.5f, 1f), animScaleNotice(1f, 1f, 0.5f))
        assertEquals(AnimScaleNotice.WindowOnly(1f, 1.5f), animScaleNotice(1f, 1.5f, null))
        assertEquals(AnimScaleNotice.Unreadable, animScaleNotice(null, 1f, 1f))
    }

    @Test fun scaleFormatting() {
        assertEquals("1.25", formatScale(1.25f))
        assertEquals("0.5", formatScale(0.5f))
        assertEquals("1", formatScale(1f))
        assertEquals("10", formatScale(10f))
        assertEquals("0", formatScale(0f))
        assertEquals("1.5", formatScale("1.5".toFloat()))
    }

    // ---- R56:「系统屏保 ▸」行的摘要 ----

    private val on5 = SystemUiStatus(
        screensaverEnabled = true, screensaverSource = DreamSource.Ours, screensaverStart = TimeoutDisplay.Minutes(5),
    )

    @Test fun summaryWhenOnListsSourceAndStart() {
        assertEquals(
            listOf(
                HintPart.Res(R.string.settings_on),
                HintPart.Res(R.string.app_name),
                HintPart.Res(R.string.settings_sys_minutes, listOf(5)),
            ),
            screensaverSummary(on5),
        )
        // 别的应用的 Dream:名字原样当字
        assertEquals(HintPart.Text("Backdrop"), screensaverSummary(on5.copy(screensaverSource = DreamSource.Other("Backdrop")))[1])
        assertEquals(HintPart.Res(R.string.settings_sys_never), screensaverSummary(on5.copy(screensaverStart = TimeoutDisplay.Never))[2])
        assertEquals(HintPart.Res(R.string.settings_sys_hours, listOf(1)), screensaverSummary(on5.copy(screensaverStart = TimeoutDisplay.Hours(1)))[2])
        assertEquals(HintPart.Res(R.string.settings_seconds, listOf(15)), screensaverSummary(on5.copy(screensaverStart = TimeoutDisplay.Seconds(15)))[2])
    }

    @Test fun summaryWhenOffIsJustOff() {
        assertEquals(listOf(HintPart.Res(R.string.settings_off)), screensaverSummary(on5.copy(screensaverEnabled = false)))
    }

    /** 读不到的部分省略,不猜;全读不到 = 空 = 不显示值。 */
    @Test fun summaryOmitsUnreadableParts() {
        assertEquals(
            listOf(HintPart.Res(R.string.settings_on), HintPart.Res(R.string.settings_sys_minutes, listOf(5))),
            screensaverSummary(on5.copy(screensaverSource = null)),
        )
        assertEquals(listOf(HintPart.Res(R.string.settings_on)), screensaverSummary(SystemUiStatus(screensaverEnabled = true)))
        // 开关读不到,来源 / 时间读得到:照样给出
        assertEquals(
            listOf(HintPart.Res(R.string.app_name), HintPart.Res(R.string.settings_sys_minutes, listOf(5))),
            screensaverSummary(on5.copy(screensaverEnabled = null)),
        )
        assertTrue(screensaverSummary(SystemUiStatus.UNKNOWN).isEmpty())
    }

    /** 候选链的顺序与退路(理由见 DREAM_SETTINGS_PAGES 的 KDoc)。 */
    @Test fun systemPageChains() {
        assertEquals("android.settings.DREAM_SETTINGS", DREAM_SETTINGS_PAGES.first().action)
        assertEquals(
            "com.android.tv.settings.device.display.daydream.DaydreamActivity",
            DREAM_SETTINGS_PAGES[1].cls,
        )
        assertEquals("com.google.android.tv.settings.ambient", DREAM_SETTINGS_PAGES[2].action)
        assertEquals("android.settings.APPLICATION_DEVELOPMENT_SETTINGS", ANIMATION_SETTINGS_PAGES.first().action)
        // 两条链都以系统设置首页收尾
        assertEquals("android.settings.SETTINGS", DREAM_SETTINGS_PAGES.last().action)
        assertEquals("android.settings.SETTINGS", ANIMATION_SETTINGS_PAGES.last().action)
    }
}
