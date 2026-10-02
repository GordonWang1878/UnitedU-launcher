package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeKeyStateTest {
    /** 开着且在跑 → ON;开着没跑 → NOT_RUNNING;没开且受限 → RESTRICTED;其余 OFF。受限只在没开时有意义。 */
    @Test fun statusTable() {
        assertEquals(HomeKeyStatus.ON, homeKeyStatus(enabled = true, running = true, restricted = false))
        assertEquals(HomeKeyStatus.ON, homeKeyStatus(enabled = true, running = true, restricted = true))
        assertEquals(HomeKeyStatus.NOT_RUNNING, homeKeyStatus(enabled = true, running = false, restricted = false))
        assertEquals(HomeKeyStatus.NOT_RUNNING, homeKeyStatus(enabled = true, running = false, restricted = true))
        assertEquals(HomeKeyStatus.RESTRICTED, homeKeyStatus(enabled = false, running = false, restricted = true))
        assertEquals(HomeKeyStatus.OFF, homeKeyStatus(enabled = false, running = false, restricted = false))
        assertEquals(HomeKeyStatus.OFF, homeKeyStatus(enabled = false, running = true, restricted = false))
    }

    /** 不是默认桌面就画;是默认桌面但服务开着也画(得让人能关掉);A95L(默认桌面、没开)不画。 */
    @Test fun capsuleVisibility() {
        assertTrue(showHomeKeyCapsule(isDefaultHome = false, enabled = false))
        assertTrue(showHomeKeyCapsule(isDefaultHome = true, enabled = true))
        assertFalse(showHomeKeyCapsule(isDefaultHome = true, enabled = false))
    }

    /** 四种状态各有自己的小字与说明;不共用,免得改一处漏一处。 */
    @Test fun statusTexts() {
        val labels = HomeKeyStatus.entries.map { homeKeyStatusRes(it) }
        val notes = HomeKeyStatus.entries.map { homeKeyNoteRes(it) }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals(notes.size, notes.toSet().size)
    }

    @Test fun heartbeatRoundTrip() {
        val h = HomeKeyHeartbeat(connected = true, bootCount = 17)
        assertEquals("1 17", formatHeartbeat(h))
        assertEquals(h, parseHeartbeat("1 17"))
        assertEquals(HomeKeyHeartbeat(false, 3), parseHeartbeat(" 0 3\n"))
        assertNull(parseHeartbeat(null))
        assertNull(parseHeartbeat(""))
        assertNull(parseHeartbeat("garbage"))
        assertNull(parseHeartbeat("1 x"))
        assertNull(parseHeartbeat("2 5"))
    }

    /** 「在运行」= 这次开机连上过且没断;上次开机的心跳不算(重启后服务没起来,系统开关却还显示开着)。 */
    @Test fun runningNeedsThisBoot() {
        assertTrue(isRunning(HomeKeyHeartbeat(true, 5), bootCount = 5))
        assertFalse(isRunning(HomeKeyHeartbeat(true, 4), bootCount = 5))
        assertFalse(isRunning(HomeKeyHeartbeat(false, 5), bootCount = 5))
        assertFalse(isRunning(null, bootCount = 5))
        assertTrue(isRunning(HomeKeyHeartbeat(true, -1), bootCount = -1))
    }

    @Test fun enabledSettingMatchesPackage() {
        assertFalse(enabledServiceSetting(null, "com.uniteduone.launcher"))
        assertFalse(enabledServiceSetting("", "com.uniteduone.launcher"))
        assertTrue(enabledServiceSetting("com.uniteduone.launcher/com.uniteduone.launcher.HomeKeyService", "com.uniteduone.launcher"))
        assertTrue(enabledServiceSetting("com.a/com.a.Svc:com.uniteduone.launcher/com.uniteduone.launcher.HomeKeyService:test.b/.C", "com.uniteduone.launcher"))
        assertFalse(enabledServiceSetting("com.uniteduone.launcher2/com.uniteduone.launcher2.X", "com.uniteduone.launcher"))
        assertTrue(enabledServiceSetting(" com.uniteduone.launcher /com.uniteduone.launcher.HomeKeyService", "com.uniteduone.launcher"))
    }
}
