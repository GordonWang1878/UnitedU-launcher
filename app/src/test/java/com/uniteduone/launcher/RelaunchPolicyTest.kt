package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaunchPolicyTest {
    /** R151:默认桌面 + 悬浮窗权限是前提;在屏幕上或用户刚发起更新,两者有一个才拉。 */
    @Test fun relaunchTruthTable() {
        for (home in listOf(true, false)) for (overlay in listOf(true, false))
            for (onScreen in listOf(true, false)) for (started in listOf(true, false)) {
                val want = home && overlay && (onScreen || started)
                assertEquals("home=$home overlay=$overlay onScreen=$onScreen started=$started", want,
                    shouldRelaunchHome(home, overlay, onScreen, started))
            }
    }

    /** 用户在别的应用里(不在屏幕上、也不是从关于页发起)时绝不拉——会盖到正在看的内容上。 */
    @Test fun neverPullsOverAnotherApp() {
        assertFalse(shouldRelaunchHome(isDefaultHome = true, canDrawOverlays = true, wasOnScreen = false, userStartedUpdate = false))
    }

    @Test fun recentUpdateRequestWindow() {
        val t = 1_000_000_000L
        assertFalse("没有标记", isRecentUpdateRequest(0L, t))
        assertTrue("刚交给安装器", isRecentUpdateRequest(t, t + 60_000))
        assertTrue("正好到窗口边", isRecentUpdateRequest(t, t + UPDATE_REQUEST_WINDOW_MS))
        assertFalse("超出窗口", isRecentUpdateRequest(t, t + UPDATE_REQUEST_WINDOW_MS + 1))
        assertFalse("时钟倒退", isRecentUpdateRequest(t, t - 1))
    }

    /** R162:不是默认桌面但主页键接管开着,更新后也拉回(intent 点名自己,见 RelaunchAfterUpdate)。 */
    @Test fun takeoverCountsAsHome() {
        assertTrue(homeOrTakeover(isDefaultHome = true, takeoverEnabled = false))
        assertTrue(homeOrTakeover(isDefaultHome = false, takeoverEnabled = true))
        assertFalse(homeOrTakeover(isDefaultHome = false, takeoverEnabled = false))
    }
}
