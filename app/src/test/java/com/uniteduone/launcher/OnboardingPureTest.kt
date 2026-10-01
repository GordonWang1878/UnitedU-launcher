package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首次引导(spec §8)里不碰 Android 的那一半:第 2 步写什么、列什么,`onCreate` 的三态判定,
 * 返回键的步骤走向,以及 Bundle 步骤号的还原。界面与 Activity 只做接线,规则全钉在这里。
 */
class OnboardingPureTest {

    private val fixture = listOf(
        LayoutRow("VIDEO", apps = listOf("com.v1", "com.v2", "com.v3")),
        LayoutRow("LIVE", apps = listOf("com.l1", "com.l2")),
        LayoutRow("MUSIC", apps = listOf("com.m1", "com.m2")),
    )

    // ---- 第 2 步:写盘的两种布局 ------------------------------------------------

    @Test fun plannedLayoutFiltersEachRowToInstalledKeepingRowAndAppOrder() {
        val planned = plannedLayout(fixture, setOf("com.m2", "com.v3", "com.v1", "com.m1"))
        assertEquals(
            listOf(
                LayoutRow("VIDEO", apps = listOf("com.v1", "com.v3")),
                LayoutRow("LIVE", apps = emptyList()),
                LayoutRow("MUSIC", apps = listOf("com.m1", "com.m2")),
            ),
            planned,
        )
    }

    @Test fun plannedLayoutKeepsEmptyRowsSoTheEditPageStillHasTheirAddButtons() {
        val planned = plannedLayout(fixture, setOf("com.l2"))
        assertEquals(listOf("VIDEO", "LIVE", "MUSIC"), planned.map { it.name })
        assertEquals(listOf(emptyList(), listOf("com.l2"), emptyList<String>()), planned.map { it.apps })
    }

    @Test fun plannedLayoutNeverAddsInstalledAppsThatAreNotInTheDefaultTable() {
        // 零推荐(spec §0):已装但不在分类表里的应用一个都不铺。
        val planned = plannedLayout(fixture, setOf("com.v2", "com.some.other.app", "com.uniteduone.launcher"))
        assertEquals(listOf("com.v2"), planned.flatMap { it.apps })
    }

    @Test fun plannedLayoutWithNothingInstalledIsAllEmptyAndEqualsSkipped() {
        val planned = plannedLayout(DEFAULT_LAYOUT, emptySet())
        assertEquals(listOf("VIDEO", "LIVE", "MUSIC"), planned.map { it.name })
        assertTrue(planned.all { it.apps.isEmpty() })
        // 「继续」在一个都没装的机器上与「跳过」写下同一份文件(brief:plan 为空时继续 = 跳过)。
        assertEquals(skippedLayout(DEFAULT_LAYOUT), planned)
    }

    @Test fun skippedLayoutKeepsTheThreeDefaultRowsWithNoApps() {
        val skipped = skippedLayout(DEFAULT_LAYOUT)
        assertEquals(listOf("VIDEO", "LIVE", "MUSIC"), skipped.map { it.name })
        assertEquals(List(3) { emptyList<String>() }, skipped.map { it.apps })
    }

    @Test fun defaultLayoutIsTheBuiltInThreeRowTable() {
        // R160(2026-10-01 Gordon):影视加腾讯(云视听极光)、去掉 NewTV极光;直播只留央视频、咪视界;音乐只留网易云、QQ 音乐。
        assertEquals(listOf("VIDEO", "LIVE", "MUSIC"), DEFAULT_LAYOUT.map { it.name })
        assertEquals(
            listOf(
                listOf("com.ktcp.video", "com.gitvdemo.video", "com.cibn.tv", "com.starcor.mango", "com.xiaodianshi.tv.yst"),
                listOf("com.newtv.cboxtv", "cn.miguvideo.migutv"),
                listOf("com.netease.cloudmusic.tv", "com.tencent.qqmusictv"),
            ),
            DEFAULT_LAYOUT.map { it.apps },
        )
    }

    // ---- 第 2 步:屏幕上列出的计划 ----------------------------------------------

    @Test fun planViewHidesEmptyRowsAndPairsEachPackageWithItsLabel() {
        val planned = plannedLayout(fixture, setOf("com.v1", "com.m2"))
        val view = planView(planned, mapOf("com.v1" to "Video One", "com.m2" to "Music Two"))
        assertEquals(
            listOf(
                "VIDEO" to listOf("com.v1" to "Video One"),
                "MUSIC" to listOf("com.m2" to "Music Two"),
            ),
            view.map { it.name to it.apps },
        )
    }

    @Test fun planViewFallsBackToPackageNameWhenLabelIsBlankOrMissing() {
        val view = planView(listOf(LayoutRow("LIVE", apps = listOf("com.l1", "com.l2"))), mapOf("com.l1" to "  "))
        assertEquals(listOf("LIVE" to listOf("com.l1" to "com.l1", "com.l2" to "com.l2")), view.map { it.name to it.apps })
    }

    /** R140 复审:行名本地化之后(「影视」),计划列表的图标照行里存的 id 画,不按行名回落成电视图标。 */
    @Test fun planViewCarriesTheRowIcon() {
        val names = mapOf("VIDEO" to "影视", "LIVE" to "直播", "MUSIC" to "音乐")
        val rows = localizedDefaultRows(plannedLayout(DEFAULT_LAYOUT, DEFAULT_LAYOUT.flatMap { it.apps }.toSet()), names)
        val view = planView(rows, emptyMap())
        assertEquals(listOf("影视", "直播", "音乐"), view.map { it.name })
        assertEquals(listOf("movie", "tv", "music"), view.map { it.icon })
        // 按行名回落的话,中文行名三行都会落成 tv——带着 id 画才对
        assertEquals(listOf("tv", "tv", "tv"), view.map { legacyRowIconId(it.name) })
    }

    @Test fun planViewOfAnAllEmptyLayoutIsEmpty() {
        assertTrue(planView(skippedLayout(DEFAULT_LAYOUT), emptyMap()).isEmpty())
    }

    // ---- onCreate 三态判定 ------------------------------------------------------

    @Test fun missingFlagWithLayoutJsonPresentIsAnOldUserWrittenTrue() {
        assertEquals(true, onboardingDoneToWrite(current = null, layoutJsonExists = true))
    }

    @Test fun missingFlagWithoutLayoutJsonIsAFreshInstallWrittenFalse() {
        assertEquals(false, onboardingDoneToWrite(current = null, layoutJsonExists = false))
    }

    @Test fun anAlreadyDecidedFlagIsNeverRewritten() {
        // 已判定过就不再看 layout.json:引导期间首页的 Layout.read 会把默认布局写出来,
        // 若每次冷启动都重判,新用户第二次启动就会被误判成老用户。
        assertNull(onboardingDoneToWrite(current = false, layoutJsonExists = true))
        assertNull(onboardingDoneToWrite(current = false, layoutJsonExists = false))
        assertNull(onboardingDoneToWrite(current = true, layoutJsonExists = true))
        assertNull(onboardingDoneToWrite(current = true, layoutJsonExists = false))
    }

    @Test fun onboardingShowsOnlyForAnExplicitFalse() {
        assertTrue(shouldShowOnboarding(false))
        assertFalse(shouldShowOnboarding(true))
        // null = 没判定(外置存储没挂,写不了盘):不打扰。
        assertFalse(shouldShowOnboarding(null))
    }

    // ---- 步骤走向 ---------------------------------------------------------------

    @Test fun backGoesToThePreviousStepAndEndsTheGuideOnStepOne() {
        assertEquals(2, onboardingBack(3))
        assertEquals(1, onboardingBack(2))
        assertNull(onboardingBack(1))
    }

    @Test fun savedStepIsRestoredAndClampedIntoRange() {
        assertEquals(1, restoredOnboardingStep(0))   // Bundle 里没有这个键时 getInt 给 0
        assertEquals(1, restoredOnboardingStep(1))
        assertEquals(2, restoredOnboardingStep(2))
        assertEquals(3, restoredOnboardingStep(3))
        assertEquals(3, restoredOnboardingStep(9))
        assertEquals(1, restoredOnboardingStep(-4))
    }

    // ---- 第 1 步:四个语言按钮 ---------------------------------------------------

    @Test fun languageButtonsMapOneToOneOntoValidLanguages() {
        // 第 1 步复用设置页语言行的四个文案;两处读同一张表,数目必须与合法取值一致。
        assertEquals(VALID_LANGUAGES.size, LANGUAGE_OPTION_RES.size)
        assertEquals(4, LANGUAGE_OPTION_RES.distinct().size)
    }

    @Test fun languageIndexFollowsValidLanguagesAndFallsBackToSystem() {
        assertEquals(0, languageIndex("system"))
        assertEquals(1, languageIndex("zh-CN"))
        assertEquals(2, languageIndex("zh-TW"))
        assertEquals(3, languageIndex("en"))
        assertEquals(0, languageIndex("fr"))
    }
}
