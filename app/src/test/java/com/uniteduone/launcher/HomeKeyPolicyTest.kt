package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeKeyPolicyTest {
    private val launcherx = "com.google.android.apps.tv.launcherx" to "com.google.android.apps.tv.launcherx.home.HomeActivity"
    private val tvlauncher = "com.google.android.tvlauncher" to "com.google.android.tvlauncher.MainActivity"
    private val stock = setOf(launcherx, tvlauncher)

    /** R162:不生效 / 屏保 → 放行;第一下按下 → 吃掉并拉起;松开、长按重复 → 只吃掉。 */
    @Test fun homeKeyTable() {
        assertEquals(HomeKeyAction.PASS, onHomeKey(active = false, dreaming = false, down = true, repeat = 0))
        assertEquals(HomeKeyAction.PASS, onHomeKey(active = true, dreaming = true, down = true, repeat = 0))
        assertEquals(HomeKeyAction.PASS, onHomeKey(active = true, dreaming = true, down = false, repeat = 0))
        assertEquals(HomeKeyAction.CONSUME_AND_LAUNCH, onHomeKey(active = true, dreaming = false, down = true, repeat = 0))
        assertEquals(HomeKeyAction.CONSUME, onHomeKey(active = true, dreaming = false, down = false, repeat = 0))
        assertEquals(HomeKeyAction.CONSUME, onHomeKey(active = true, dreaming = false, down = true, repeat = 3))
    }

    /** 屏保状态:DreamActivity → 在屏保里;别的 Activity → 不在;弹窗 / 非 Activity 类名不改状态。 */
    @Test fun dreamingTracksActivityWindowsOnly() {
        assertTrue(nextDreaming(prev = false, cls = DREAM_ACTIVITY_CLASS))
        assertTrue(nextDreaming(prev = true, cls = "android.widget.FrameLayout"))
        assertTrue(nextDreaming(prev = true, cls = "android.app.Dialog"))
        assertTrue(nextDreaming(prev = true, cls = null))
        assertFalse(nextDreaming(prev = true, cls = "com.android.tv.settings.MainSettings"))
        assertFalse(nextDreaming(prev = false, cls = "com.uniteduone.launcher.MainActivity"))
    }

    @Test fun stockHomesExcludesSelf() {
        val all = listOf(launcherx, "com.uniteduone.launcher" to "com.uniteduone.launcher.MainActivity", tvlauncher)
        assertEquals(stock, stockHomes(all, "com.uniteduone.launcher"))
    }

    /** 只认别的桌面的 HOME Activity 本身;同一个包的别的窗口(弹窗、其它 Activity)不算;自己不算;1 s 内不重复拉。 */
    @Test fun windowChangedOnlyForStockHomeActivity() {
        assertTrue(onWindowChanged(true, launcherx.first, launcherx.second, stock, lastLaunchAt = 0, now = 10_000))
        assertFalse(onWindowChanged(true, launcherx.first, "android.widget.FrameLayout", stock, 0, 10_000))
        assertFalse(onWindowChanged(true, launcherx.first, "com.google.android.apps.tv.launcherx.settings.SettingsActivity", stock, 0, 10_000))
        assertFalse(onWindowChanged(true, "com.uniteduone.launcher", "com.uniteduone.launcher.MainActivity", stock, 0, 10_000))
        assertFalse(onWindowChanged(false, launcherx.first, launcherx.second, stock, 0, 10_000))
        assertFalse(onWindowChanged(true, null, null, stock, 0, 10_000))
        assertFalse("1 s 内第二个窗口事件不再拉", onWindowChanged(true, launcherx.first, launcherx.second, stock, lastLaunchAt = 9_500, now = 10_000))
        assertTrue(onWindowChanged(true, launcherx.first, launcherx.second, stock, lastLaunchAt = 9_000, now = 10_000))
    }

    /** 开机 3 分钟内连上才拉;用户在设置里刚打开(开机很久了)不拉——他还在设置页里。 */
    @Test fun launchOnConnectOnlyAtBoot() {
        assertTrue(shouldLaunchOnConnect(active = true, uptimeMs = 30_000))
        assertFalse(shouldLaunchOnConnect(active = true, uptimeMs = BOOT_LAUNCH_WINDOW_MS))
        assertFalse(shouldLaunchOnConnect(active = false, uptimeMs = 30_000))
    }
}
