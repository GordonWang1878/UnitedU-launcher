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

    private fun HomeKeyPresses.down(downTime: Long, decision: HomeKeyAction): HomeKeyAction = onDown(downTime) { decision }

    /** 终审:同一个 downTime 的重复 DOWN(有的遥控器连发,过滤器看到的 repeatCount 恒为 0)只拉一次;新的一下(新 downTime)照拉。 */
    @Test fun repeatedDownOfOnePressLaunchesOnce() {
        val p = HomeKeyPresses()
        assertEquals(HomeKeyAction.CONSUME_AND_LAUNCH, p.down(100, HomeKeyAction.CONSUME_AND_LAUNCH))
        // 重复的 DOWN:不再问策略(策略此刻仍会答 CONSUME_AND_LAUNCH,拿它就拉起第二次),只吃
        repeat(3) { assertEquals(HomeKeyAction.CONSUME, p.onDown(100) { error("同一次按下不该再判") }) }
        assertTrue(p.onUp())
        assertEquals("下一次按下(新 downTime)照拉", HomeKeyAction.CONSUME_AND_LAUNCH, p.down(250, HomeKeyAction.CONSUME_AND_LAUNCH))
        assertTrue(p.onUp())
    }

    /** 终审:松开跟着它的按下走,不按松开那一刻的状态重判(见 [HomeKeyPresses]);没见过按下的松开放行。 */
    @Test fun upFollowsItsDown() {
        val p = HomeKeyPresses()
        assertFalse("没见过按下的松开放行", p.onUp())
        assertEquals(HomeKeyAction.PASS, p.down(100, HomeKeyAction.PASS))
        assertFalse("按下放行了,松开也放行", p.onUp())
        assertEquals(HomeKeyAction.CONSUME_AND_LAUNCH, p.down(200, HomeKeyAction.CONSUME_AND_LAUNCH))
        assertTrue("按下吃了,松开就吃", p.onUp())
        assertFalse("一次松开只认一次", p.onUp())
        // 长按:第一次见到的 DOWN 就带 repeat > 0(策略答 CONSUME)——同样吃掉、松开跟着吃
        assertEquals(HomeKeyAction.CONSUME, p.down(300, HomeKeyAction.CONSUME))
        assertTrue(p.onUp())
    }

    /** 终审:放行过的那次按下,重复的 DOWN 也放行——屏保里的 HOME 退出屏保后 dreaming 翻 false,重复的 DOWN 不能把 UnitedU 拉起来。 */
    @Test fun passedPressStaysPassedWhenRepeated() {
        val p = HomeKeyPresses()
        assertEquals(HomeKeyAction.PASS, p.down(100, HomeKeyAction.PASS))
        assertEquals(HomeKeyAction.PASS, p.onDown(100) { error("放行过的那次按下不该再判") })
        assertFalse(p.onUp())
        // 之后一次新的按下(此刻不在屏保里)照常拉起,配对状态不被前一次留下的放行带歪
        assertEquals(HomeKeyAction.CONSUME_AND_LAUNCH, p.down(400, HomeKeyAction.CONSUME_AND_LAUNCH))
        assertTrue(p.onUp())
    }

    /** downTime <= 0 是「不知道」:不去重,否则一个恒报 0 的设备第一下之后每一下都被当成重复、HOME 变死键。 */
    @Test fun unknownDownTimeIsNeverDeduped() {
        val p = HomeKeyPresses()
        for (t in listOf(0L, 0L, -1L, -1L)) {
            assertEquals(HomeKeyAction.CONSUME_AND_LAUNCH, p.down(t, HomeKeyAction.CONSUME_AND_LAUNCH))
            assertTrue(p.onUp())
        }
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

    /**
     * 终审:拉起后 1 s 去抖内丢掉的原厂桌面窗口事件要补查一次。`debouncedOnly` 与 `onWindowChanged` 对别的桌面的 HOME Activity 严格互补
     * (去抖内 / 去抖外二选一),别的窗口、自己、不生效、空包名两个都为假;补查时刻一到 `onWindowChanged` 就为真。
     */
    @Test fun debouncedWindowIsRecheckedWhenDebounceEnds() {
        // 去抖内:onWindowChanged 假、debouncedOnly 真;去抖外:反过来
        assertFalse(onWindowChanged(true, launcherx.first, launcherx.second, stock, lastLaunchAt = 9_500, now = 10_000))
        assertTrue(debouncedOnly(true, launcherx.first, launcherx.second, stock, lastLaunchAt = 9_500, now = 10_000))
        assertTrue(onWindowChanged(true, launcherx.first, launcherx.second, stock, lastLaunchAt = 9_000, now = 10_000))
        assertFalse(debouncedOnly(true, launcherx.first, launcherx.second, stock, lastLaunchAt = 9_000, now = 10_000))
        // 两个都不认的窗口:别的 Activity、自己、不生效、空
        val own = "com.uniteduone.launcher" to "com.uniteduone.launcher.MainActivity"
        for ((a, w) in listOf(
            true to (launcherx.first to "android.widget.FrameLayout"),
            true to own,
            false to launcherx,
        )) {
            assertFalse(debouncedOnly(a, w.first, w.second, stock, lastLaunchAt = 9_500, now = 10_000))
        }
        assertFalse(debouncedOnly(true, null, null, stock, 9_500, 10_000))
        assertFalse(debouncedOnly(true, launcherx.first, null, stock, 9_500, 10_000))
        // 补查延迟:拉起 + 去抖 + 50 ms 之后;到点 onWindowChanged 为真;过期的不为负
        assertEquals(550L, recheckDelayMs(lastLaunchAt = 9_500, now = 10_000))
        assertTrue(onWindowChanged(true, launcherx.first, launcherx.second, stock, 9_500, now = 10_000 + recheckDelayMs(9_500, 10_000)))
        assertEquals(1_050L, recheckDelayMs(lastLaunchAt = 5_000, now = 5_000))
        assertEquals(0L, recheckDelayMs(lastLaunchAt = 0, now = 5_000))
    }

    /** 开机 3 分钟内连上才拉;用户在设置里刚打开(开机很久了)不拉——他还在设置页里。 */
    @Test fun launchOnConnectOnlyAtBoot() {
        assertTrue(shouldLaunchOnConnect(active = true, uptimeMs = 30_000))
        assertFalse(shouldLaunchOnConnect(active = true, uptimeMs = BOOT_LAUNCH_WINDOW_MS))
        assertFalse(shouldLaunchOnConnect(active = false, uptimeMs = 30_000))
    }
}
