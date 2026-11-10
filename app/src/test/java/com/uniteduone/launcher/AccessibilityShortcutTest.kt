package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍设置入口(issue #1)。候选由测试喂进来,不碰 PackageManager。
 * 空结果 [SettingsShortcut.ShowManualSteps] = 屏幕上改显示手动步骤。
 */
class AccessibilityShortcutTest {
    private val accessibility = SystemPage(action = "android.settings.ACCESSIBILITY_SETTINGS")
    private val settings = SystemPage(action = "android.settings.SETTINGS")
    private val stub = ResolvedActivity(
        packageName = TV_FRAMEWORK_STUB_PACKAGE,
        className = TV_FRAMEWORK_SETTINGS_STUB_CLASS,
    )
    private val realAccessibility = ResolvedActivity(
        packageName = "com.android.tv.settings",
        className = "com.android.tv.settings.system.AccessibilityActivity",
    )
    private val realSettings = ResolvedActivity(
        packageName = "com.android.tv.settings",
        className = "com.android.tv.settings.MainSettings",
    )

    @Test fun stubClassAndPackageAreRecognized() {
        assertTrue(isFrameworkSettingsStub(stub))
        assertTrue(isFrameworkSettingsStub(ResolvedActivity("android", TV_FRAMEWORK_SETTINGS_STUB_CLASS)))
        assertTrue(
            isFrameworkSettingsStub(
                ResolvedActivity(TV_FRAMEWORK_STUB_PACKAGE, "$TV_FRAMEWORK_STUB_PACKAGE.Stubs\$SomethingElse"),
            ),
        )
        assertFalse(isFrameworkSettingsStub(realSettings))
        assertFalse(isFrameworkSettingsStub(ResolvedActivity("com.example.stub", "com.example.stub.Settings")))
    }

    /** 无障碍这一页只有桩时,落到候选链里下一个真实设置页。 */
    @Test fun skipsStubAndFallsBackToNextIntent() {
        val result = resolveSettingsShortcut(listOf(accessibility, settings)) { page ->
            if (page == accessibility) listOf(stub) else listOf(realSettings)
        }
        val open = result as SettingsShortcut.Open
        assertEquals(settings, open.targets.single().page)
        assertEquals(realSettings, open.targets.single().activity)
    }

    /** 同一条 intent 上桩排在前面、后面还有真页面:用真页面,不因为默认匹配是桩就整页放弃。 */
    @Test fun skipsStubOnTheSameIntent() {
        val result = resolveSettingsShortcut(listOf(accessibility, settings)) { page ->
            if (page == accessibility) listOf(stub, realAccessibility) else listOf(realSettings)
        }
        val open = result as SettingsShortcut.Open
        assertEquals(accessibility, open.targets.first().page)
        assertEquals(realAccessibility, open.targets.first().activity)
        assertEquals(realSettings, open.targets[1].activity)
    }

    @Test fun skipsActivitiesThatAreNotExported() {
        val hidden = realAccessibility.copy(exported = false)
        val result = resolveSettingsShortcut(listOf(accessibility, settings)) { page ->
            if (page == accessibility) listOf(hidden, stub) else listOf(realSettings)
        }
        assertEquals(realSettings, (result as SettingsShortcut.Open).targets.single().activity)
    }

    @Test fun noRealActivityShowsManualSteps() {
        assertEquals(
            SettingsShortcut.ShowManualSteps,
            resolveSettingsShortcut(ACCESSIBILITY_SETTINGS_PAGES) { listOf(stub) },
        )
        assertEquals(
            SettingsShortcut.ShowManualSteps,
            resolveSettingsShortcut(ACCESSIBILITY_SETTINGS_PAGES) { emptyList() },
        )
    }

    /** 两页都是真的:顺序与 [ACCESSIBILITY_SETTINGS_PAGES] 一致,调用方按这个顺序试,弹回再换下一个。 */
    @Test fun realChainKeepsPageOrder() {
        val result = resolveSettingsShortcut(ACCESSIBILITY_SETTINGS_PAGES) { page ->
            listOf(
                if (page.action == "android.settings.ACCESSIBILITY_SETTINGS") realAccessibility else realSettings,
            )
        }
        val open = result as SettingsShortcut.Open
        assertEquals(accessibility.action, open.targets[0].page.action)
        assertEquals(settings.action, open.targets[1].page.action)
        assertEquals(realAccessibility, open.targets[0].activity)
        assertEquals(realSettings, open.targets[1].activity)
    }
}
