package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    @Test fun blankJsonYieldsDefaults() {
        assertEquals(Settings(), parseSettings(""))
        assertEquals(Settings(), parseSettings("   "))
    }

    @Test fun malformedJsonYieldsDefaults() {
        assertEquals(Settings(), parseSettings("{not json"))
    }

    @Test fun partialJsonKeepsRestAsDefaults() {
        val s = parseSettings("""{"rowCount": 5}""")
        assertEquals(5, s.rowCount)
        assertEquals(Settings().cardsPerRow, s.cardsPerRow)
        assertEquals(Settings().showTitles, s.showTitles)
        assertEquals(Settings().showInputRow, s.showInputRow)
        assertEquals(Settings().themePresetId, s.themePresetId)
        assertEquals(Settings().idleAfterMs, s.idleAfterMs)
        assertEquals(Settings().idleContent, s.idleContent)
    }

    @Test fun rowCountClampsToRange() {
        assertEquals(5, parseSettings("""{"rowCount": 9}""").rowCount)
        assertEquals(1, parseSettings("""{"rowCount": 0}""").rowCount)
        assertEquals(1, parseSettings("""{"rowCount": -3}""").rowCount)
    }

    @Test fun cardsPerRowSnapsToNearestTier() {
        val v = parseSettings("""{"cardsPerRow": 7}""").cardsPerRow
        assertTrue("expected 6 or 8, got $v", v == 6 || v == 8)
        assertEquals(5, parseSettings("""{"cardsPerRow": 5}""").cardsPerRow)
        assertEquals(6, parseSettings("""{"cardsPerRow": 6}""").cardsPerRow)
        assertEquals(8, parseSettings("""{"cardsPerRow": 8}""").cardsPerRow)
        // 999 是可解析的数字,不是"缺失/垃圾",所以走就近夹取(离 8 最近),
        // 不是"缺失/垃圾 → 6"那条规则——那条规则专管解析不出数字的情况。
        assertEquals(8, parseSettings("""{"cardsPerRow": 999}""").cardsPerRow)
        assertEquals(6, parseSettings("""{}""").cardsPerRow)
        assertEquals(6, parseSettings("""{"cardsPerRow": "not a number"}""").cardsPerRow)
    }

    @Test fun idleAfterMsMustBeOneOfAllowedValues() {
        assertEquals(180_000L, parseSettings("""{"idleAfterMs": 12345}""").idleAfterMs)
        for (v in listOf(0L, 60_000L, 180_000L, 300_000L, 600_000L)) {
            assertEquals(v, parseSettings("""{"idleAfterMs": $v}""").idleAfterMs)
        }
    }

    @Test fun themePresetIdFallsBackToGoldWhenAbsentOrBlank() {
        assertEquals("gold", parseSettings("""{}""").themePresetId)
        assertEquals("gold", parseSettings("""{"themePresetId": ""}""").themePresetId)
        assertEquals("sunset", parseSettings("""{"themePresetId": "sunset"}""").themePresetId)
    }

    @Test fun booleansFallBackToDefaultsWhenAbsent() {
        val s = parseSettings("""{"showTitles": true, "showDate": false}""")
        assertEquals(true, s.showTitles)
        assertEquals(false, s.showDate)
        // untouched fields keep their own defaults
        assertEquals(Settings().showInputRow, s.showInputRow)
        assertEquals(Settings().followWallpaperColor, s.followWallpaperColor)
        assertEquals(Settings().clock24hFollowSystem, s.clock24hFollowSystem)
    }

    @Test fun idleContentUnknownNameFallsBackToClockOnly() {
        assertEquals(IdleContent.CLOCK_ONLY, parseSettings("""{"idleContent": "GARBAGE"}""").idleContent)
        assertEquals(IdleContent.CLOCK_ONLY, parseSettings("""{}""").idleContent)
        assertEquals(IdleContent.NO_FADE, parseSettings("""{"idleContent": "NO_FADE"}""").idleContent)
        assertEquals(IdleContent.BLACK, parseSettings("""{"idleContent": "BLACK"}""").idleContent)
    }

    @Test fun roundTripPreservesNonDefaultSettings() {
        val s = Settings(
            rowCount = 5,
            cardsPerRow = 8,
            showTitles = true,
            showInputRow = true,
            themePresetId = "sunset",
            followWallpaperColor = true,
            clock24hFollowSystem = false,
            showDate = false,
            idleAfterMs = 600_000L,
            idleContent = IdleContent.NO_FADE,
        )
        assertEquals(s, parseSettings(s.toJson()))
    }

    @Test fun defaultSettingsRoundTrips() {
        val s = Settings()
        assertEquals(s, parseSettings(s.toJson()))
    }
}
