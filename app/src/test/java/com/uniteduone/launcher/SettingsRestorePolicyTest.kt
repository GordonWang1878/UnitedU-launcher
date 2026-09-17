package com.uniteduone.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRestorePolicyTest {
    @Test fun restoresOnlyWhenBundleSaysOpenAndRecreateWasSelfTriggered() {
        assertTrue(shouldRestoreSettingsFromBundle(bundleSaysOpen = true, selfTriggeredRecreate = true, onboardingOpen = false))
        assertFalse(shouldRestoreSettingsFromBundle(bundleSaysOpen = true, selfTriggeredRecreate = false, onboardingOpen = false))
        assertFalse(shouldRestoreSettingsFromBundle(bundleSaysOpen = false, selfTriggeredRecreate = true, onboardingOpen = false))
        assertFalse(shouldRestoreSettingsFromBundle(bundleSaysOpen = false, selfTriggeredRecreate = false, onboardingOpen = false))
    }

    /**
     * 终审 I2:引导写盘失败时 `endOnboarding` 照样收起引导,盘上 `onboardingDone` 仍是 false;
     * 之后在设置页切语言 → 自己触发的 recreate → 新实例按盘判出「要引导」,Bundle 又说设置页开着。
     * 两层浮层各带一套焦点账本,会互相抢焦点——引导在场时设置页一律不种回来。
     */
    @Test fun neverRestoresWhileOnboardingIsShowing() {
        assertFalse(shouldRestoreSettingsFromBundle(bundleSaysOpen = true, selfTriggeredRecreate = true, onboardingOpen = true))
        assertFalse(shouldRestoreSettingsFromBundle(bundleSaysOpen = true, selfTriggeredRecreate = false, onboardingOpen = true))
        assertFalse(shouldRestoreSettingsFromBundle(bundleSaysOpen = false, selfTriggeredRecreate = true, onboardingOpen = true))
        assertFalse(shouldRestoreSettingsFromBundle(bundleSaysOpen = false, selfTriggeredRecreate = false, onboardingOpen = true))
    }
}
