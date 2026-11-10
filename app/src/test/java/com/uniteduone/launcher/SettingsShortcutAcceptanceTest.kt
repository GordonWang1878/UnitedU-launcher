package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance tests for issue #1, criteria v1 AC-I1 (accessibility settings entry taken by the empty
 * framework stub Stubs$SettingsStub). Written by 02 Delta, 2026-11-10; checked against PR #7 commit 7cd9a62
 * (AccessibilityShortcut.kt unchanged since 7071f4e).
 * 01 commits this file verbatim: do not change assertions, fixtures, test names, or drop tests.
 *
 * Public API only: resolveSettingsShortcut, isFrameworkSettingsStub, ResolvedActivity, SettingsShortcut.Open /
 * ShowManualSteps, SettingsTarget, SystemPage, ACCESSIBILITY_SETTINGS_PAGES and the two stub constants.
 * Candidates are fed by the test; no PackageManager.
 *
 * Clause ids:
 * - AC-I1-1  stub / unresolved entry must not end on an error: open a working fallback or show manual steps
 * - AC-I1-2  TVs with a working entry behave as today (regression)
 * - AC-I1-3  unit tests for: normal entry / stub entry / no entry ("launch throws" lives in MainActivity, see README)
 */
class SettingsShortcutAcceptanceTest {
    private val accessibilityPage = SystemPage(action = "android.settings.ACCESSIBILITY_SETTINGS")
    private val settingsPage = SystemPage(action = "android.settings.SETTINGS")

    /** The stub reported on the Hisense A4 in issue #1 (package and class both match). */
    private val hisenseStub = ResolvedActivity(
        "com.google.android.tv.frameworkpackagestubs",
        "com.google.android.tv.frameworkpackagestubs.Stubs\$SettingsStub",
    )
    private val tvAccessibility = ResolvedActivity("com.android.tv.settings", "com.android.tv.settings.accessibility.AccessibilityActivity")
    private val tvSettings = ResolvedActivity("com.android.tv.settings", "com.android.tv.settings.MainSettings")
    private val vendorSettings = ResolvedActivity("com.hisense.fixture.settings", "com.hisense.fixture.settings.SettingsActivity")

    // ------------------------------------------------------------ stub detection

    /** AC-I1-1: the reported stub is recognized; constants equal the strings in the report. */
    @Test fun reportedStubIsRecognized() {
        assertEquals("com.google.android.tv.frameworkpackagestubs", TV_FRAMEWORK_STUB_PACKAGE)
        assertEquals("com.google.android.tv.frameworkpackagestubs.Stubs\$SettingsStub", TV_FRAMEWORK_SETTINGS_STUB_CLASS)
        assertTrue(isFrameworkSettingsStub(hisenseStub))
    }

    /** AC-I1-1: same stub class under another package, or any class under the stub package, is a stub. */
    @Test fun stubVariantsAreRecognized() {
        assertTrue(isFrameworkSettingsStub(ResolvedActivity("android", "com.google.android.tv.frameworkpackagestubs.Stubs\$SettingsStub")))
        assertTrue(isFrameworkSettingsStub(ResolvedActivity("com.google.android.tv.frameworkpackagestubs", "com.example.Anything")))
        assertTrue(isFrameworkSettingsStub(ResolvedActivity("android", "com.google.android.tv.frameworkpackagestubs.Stubs\$BrowserStub")))
    }

    /** AC-I1-2: real settings pages are not stubs (including look-alike package prefixes). */
    @Test fun realSettingsAreNotStubs() {
        assertFalse(isFrameworkSettingsStub(tvAccessibility))
        assertFalse(isFrameworkSettingsStub(tvSettings))
        assertFalse(isFrameworkSettingsStub(vendorSettings))
        assertFalse(isFrameworkSettingsStub(ResolvedActivity("com.google.android.tv.settings", "com.google.android.tv.settings.Main")))
        assertFalse(
            isFrameworkSettingsStub(
                ResolvedActivity("com.google.android.tv.frameworkpackagestubsx", "com.google.android.tv.frameworkpackagestubsx.Settings"),
            ),
        )
    }

    // ------------------------------------------------------------ normal entry (regression)

    /** AC-I1-2 / AC-I1-3 normal entry: real accessibility page -> opened first, chain order kept. */
    @Test fun normalEntryOpensAccessibilityFirst() {
        val result = resolveSettingsShortcut(listOf(accessibilityPage, settingsPage)) { page ->
            if (page == accessibilityPage) listOf(tvAccessibility) else listOf(tvSettings)
        }
        assertTrue(result is SettingsShortcut.Open)
        val targets = (result as SettingsShortcut.Open).targets
        assertEquals(listOf(SettingsTarget(accessibilityPage, tvAccessibility), SettingsTarget(settingsPage, tvSettings)), targets)
    }

    /** AC-I1-2: production chain unchanged (accessibility, then settings home); each page queried once, in order. */
    @Test fun productionChainIsAccessibilityThenSettingsQueriedInOrder() {
        assertEquals(listOf("android.settings.ACCESSIBILITY_SETTINGS", "android.settings.SETTINGS"), ACCESSIBILITY_SETTINGS_PAGES.map { it.action })
        val asked = mutableListOf<SystemPage>()
        resolveSettingsShortcut(ACCESSIBILITY_SETTINGS_PAGES) { page -> asked += page; listOf(tvSettings) }
        assertEquals(ACCESSIBILITY_SETTINGS_PAGES, asked)
    }

    // ------------------------------------------------------------ stub entry

    /** AC-I1-1 / AC-I1-3 stub entry (the Hisense A4 case): accessibility page is only the stub -> open settings home instead. */
    @Test fun stubEntryFallsBackToNextRealPage() {
        val result = resolveSettingsShortcut(listOf(accessibilityPage, settingsPage)) { page ->
            if (page == accessibilityPage) listOf(hisenseStub) else listOf(tvSettings)
        }
        assertEquals(SettingsShortcut.Open(listOf(SettingsTarget(settingsPage, tvSettings))), result)
    }

    /** AC-I1-1: default match is the stub but a real activity follows -> use the real one, one target per page. */
    @Test fun stubAheadOfRealActivityOnSamePageIsSkipped() {
        val result = resolveSettingsShortcut(listOf(accessibilityPage)) { listOf(hisenseStub, vendorSettings, tvAccessibility) }
        assertEquals(SettingsShortcut.Open(listOf(SettingsTarget(accessibilityPage, vendorSettings))), result)
    }

    /** AC-I1-1: a non-exported activity cannot be launched, so it is never a target. */
    @Test fun nonExportedActivityIsNotATarget() {
        val hidden = tvAccessibility.copy(exported = false)
        val result = resolveSettingsShortcut(listOf(accessibilityPage, settingsPage)) { page ->
            if (page == accessibilityPage) listOf(hidden) else listOf(tvSettings)
        }
        assertEquals(SettingsShortcut.Open(listOf(SettingsTarget(settingsPage, tvSettings))), result)
    }

    // ------------------------------------------------------------ no entry

    /** AC-I1-1 / AC-I1-3 no entry: nothing resolves -> manual steps, nothing launched. */
    @Test fun noCandidatesShowsManualSteps() {
        assertEquals(SettingsShortcut.ShowManualSteps, resolveSettingsShortcut(ACCESSIBILITY_SETTINGS_PAGES) { emptyList() })
        assertEquals(SettingsShortcut.ShowManualSteps, resolveSettingsShortcut(emptyList()) { listOf(tvSettings) })
    }

    /** AC-I1-1: only stubs / non-exported on every page -> manual steps. */
    @Test fun onlyStubsOrHiddenShowsManualSteps() {
        assertEquals(SettingsShortcut.ShowManualSteps, resolveSettingsShortcut(ACCESSIBILITY_SETTINGS_PAGES) { listOf(hisenseStub) })
        assertEquals(
            SettingsShortcut.ShowManualSteps,
            resolveSettingsShortcut(ACCESSIBILITY_SETTINGS_PAGES) { listOf(hisenseStub, tvSettings.copy(exported = false)) },
        )
    }

    /**
     * AC-I1-1 / AC-I1-2 exhaustive: two pages, each gets 0-2 ordered picks from {stub, hidden, real A, real B}.
     * - manual steps <=> no page has an exported non-stub activity;
     * - Open: never a stub or hidden target; at most one target per page (its first usable one); page order kept.
     */
    @Test fun exhaustiveCombinationsNeverOpenStubAndKeepOrder() {
        val pool = listOf(hisenseStub, tvAccessibility.copy(exported = false), tvAccessibility, vendorSettings)
        val lists = buildList {
            add(emptyList<ResolvedActivity>())
            pool.forEach { a -> add(listOf(a)) }
            pool.forEach { a -> pool.forEach { b -> if (a != b) add(listOf(a, b)) } }
        }
        val pages = listOf(accessibilityPage, settingsPage)
        var cases = 0
        for (first in lists) for (second in lists) {
            cases++
            val byPage = mapOf(accessibilityPage to first, settingsPage to second)
            val result = resolveSettingsShortcut(pages) { byPage.getValue(it) }
            val expected = pages.mapNotNull { p ->
                byPage.getValue(p).firstOrNull { it.exported && !isFrameworkSettingsStub(it) }?.let { SettingsTarget(p, it) }
            }
            if (expected.isEmpty()) {
                assertEquals("first=$first second=$second", SettingsShortcut.ShowManualSteps, result)
            } else {
                assertEquals("first=$first second=$second", SettingsShortcut.Open(expected), result)
                (result as SettingsShortcut.Open).targets.forEach {
                    assertTrue(it.activity.exported)
                    assertFalse(it.activity.packageName == TV_FRAMEWORK_STUB_PACKAGE)
                    assertFalse(it.activity.className == TV_FRAMEWORK_SETTINGS_STUB_CLASS)
                }
            }
        }
        assertEquals(17 * 17, cases)
    }
}
