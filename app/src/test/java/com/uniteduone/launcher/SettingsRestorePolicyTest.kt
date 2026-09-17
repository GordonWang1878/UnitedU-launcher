package com.uniteduone.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRestorePolicyTest {
    @Test fun restoresOnlyWhenBundleSaysOpenAndRecreateWasSelfTriggered() {
        assertTrue(shouldRestoreSettingsFromBundle(bundleSaysOpen = true, selfTriggeredRecreate = true))
        assertFalse(shouldRestoreSettingsFromBundle(bundleSaysOpen = true, selfTriggeredRecreate = false))
        assertFalse(shouldRestoreSettingsFromBundle(bundleSaysOpen = false, selfTriggeredRecreate = true))
        assertFalse(shouldRestoreSettingsFromBundle(bundleSaysOpen = false, selfTriggeredRecreate = false))
    }
}
