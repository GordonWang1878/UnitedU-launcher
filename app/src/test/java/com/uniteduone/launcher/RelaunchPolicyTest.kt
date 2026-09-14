package com.uniteduone.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaunchPolicyTest {
    @Test fun relaunchesOnlyWhenDefaultHomeAndOverlayGranted() {
        assertTrue(shouldRelaunchHome(isDefaultHome = true, canDrawOverlays = true))
        assertFalse(shouldRelaunchHome(isDefaultHome = true, canDrawOverlays = false))
        assertFalse(shouldRelaunchHome(isDefaultHome = false, canDrawOverlays = true))
        assertFalse(shouldRelaunchHome(isDefaultHome = false, canDrawOverlays = false))
    }
}
