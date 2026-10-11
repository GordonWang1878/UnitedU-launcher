package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class NotesLanguageTest {
    // 线上 1.2.0-beta.2 的原文
    private val both = "Beta:编辑桌面进出、搬卡片更顺滑;频道说明更清楚。\nBeta: smoother Edit Home Screen and card moves; clearer channel notes."

    @Test fun chineseUiKeepsChineseLine() =
        assertEquals("Beta:编辑桌面进出、搬卡片更顺滑;频道说明更清楚。", notesForLanguage(both, chinese = true))

    @Test fun englishUiKeepsEnglishLine() =
        assertEquals("Beta: smoother Edit Home Screen and card moves; clearer channel notes.", notesForLanguage(both, chinese = false))

    @Test fun blankLinesAndCrlfIgnored() =
        assertEquals("修复 A", notesForLanguage("修复 A\r\n\r\n  Fix A  \n", chinese = true))

    @Test fun singleLanguageNotesShownAsIs() {
        assertEquals("只写了中文", notesForLanguage("只写了中文", chinese = false))
        assertEquals("English only", notesForLanguage("English only", chinese = true))
    }

    @Test fun emptyStaysEmpty() = assertEquals("", notesForLanguage("", chinese = true))
}
