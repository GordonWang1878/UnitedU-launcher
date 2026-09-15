package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // 7 到 6 和 8 的距离相等(都是 1);minByOrNull 保留遇到的第一个最小值,
        // 而 VALID_CARDS_PER_ROW 是 [5, 6, 8],所以这是个确定性行为,钉死成 6。
        assertEquals(6, parseSettings("""{"cardsPerRow": 7}""").cardsPerRow)
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

    @Test fun wellFormedJsonObjectAcceptsExactlyOneTopLevelObject() {
        assertTrue(isWellFormedJsonObject("""{"rowCount":5}"""))
        assertTrue(isWellFormedJsonObject("{}"))
        assertTrue(isWellFormedJsonObject(Settings().toJson()))
        // 尾部空白无所谓——真正落盘的 toJson() 输出就带一个结尾换行。
        assertTrue(isWellFormedJsonObject("""{"rowCount":5}""" + "\n"))
    }

    @Test fun wellFormedJsonObjectRejectsConcatenatedObjects() {
        // 追加式损坏:两个本身都合法的对象首尾拼在一起——花括号配平、首尾字符也对,
        // 但顶层对象闭合了不止一次,必须判损坏,否则 parseSettings 的最左匹配正则
        // 会悄悄取到第一段(旧值),这份坏文件就永远续命下去了。
        assertFalse(isWellFormedJsonObject("""{"rowCount":5}{"cardsPerRow":8}"""))
        assertFalse(isWellFormedJsonObject("{}{}"))
        assertFalse(isWellFormedJsonObject("{} {}"))
    }

    @Test fun wellFormedJsonObjectRejectsStructurallyBrokenText() {
        assertFalse(isWellFormedJsonObject(""))
        assertFalse(isWellFormedJsonObject("{not json"))
        assertFalse(isWellFormedJsonObject("{}}"))
        assertFalse(isWellFormedJsonObject("""{"a": "unterminated"""))
    }

    @Test fun wallpaperFieldsDefaultWhenAbsent() {
        val s = parseSettings("{}")
        assertEquals("", s.wallpaperFile)
        assertEquals(0L, s.wallpaperRotateMs)
        assertEquals(0L, s.wallpaperRotatedAt)
        assertFalse(s.wallpaperThemed)
        assertEquals(0, s.wallpaperBlur)
        assertEquals(0, s.wallpaperDim)
    }

    @Test fun wallpaperFileRejectsPathEscapes() {
        // settings.json 用户可手改;文件名只能指向 library/wallpapers/ 里的一个条目
        assertEquals("", parseSettings("""{"wallpaperFile": "../x.jpg"}""").wallpaperFile)
        assertEquals("", parseSettings("""{"wallpaperFile": "a/b.jpg"}""").wallpaperFile)
        assertEquals("", parseSettings("""{"wallpaperFile": "a\\b.jpg"}""").wallpaperFile)
        assertEquals("", parseSettings("""{"wallpaperFile": "   "}""").wallpaperFile)
        assertEquals("unitedu-00-neutral.jpg",
            parseSettings("""{"wallpaperFile": "unitedu-00-neutral.jpg"}""").wallpaperFile)
    }

    @Test fun wallpaperRotateMsMustBeOneOfAllowedValues() {
        assertEquals(0L, parseSettings("""{"wallpaperRotateMs": 12345}""").wallpaperRotateMs)
        for (v in listOf(0L, 300_000L, 1_800_000L, 86_400_000L)) {
            assertEquals(v, parseSettings("""{"wallpaperRotateMs": $v}""").wallpaperRotateMs)
        }
    }

    @Test fun wallpaperRotatedAtNeverNegative() {
        assertEquals(0L, parseSettings("""{"wallpaperRotatedAt": -5}""").wallpaperRotatedAt)
        assertEquals(1_700_000_000_000L,
            parseSettings("""{"wallpaperRotatedAt": 1700000000000}""").wallpaperRotatedAt)
    }

    @Test fun blurAndDimClampAndSnapToTens() {
        assertEquals(0, parseSettings("""{"wallpaperBlur": -20}""").wallpaperBlur)
        assertEquals(100, parseSettings("""{"wallpaperBlur": 250}""").wallpaperBlur)
        assertEquals(50, parseSettings("""{"wallpaperBlur": 54}""").wallpaperBlur)
        assertEquals(60, parseSettings("""{"wallpaperBlur": 55}""").wallpaperBlur)
        assertEquals(100, parseSettings("""{"wallpaperDim": 96}""").wallpaperDim)
        assertEquals(0, parseSettings("""{"wallpaperDim": "x"}""").wallpaperDim)
    }

    @Test fun wallpaperFieldsRoundTrip() {
        val s = Settings(
            wallpaperFile = "sea.jpg",
            wallpaperRotateMs = 1_800_000L,
            wallpaperRotatedAt = 1_700_000_000_000L,
            wallpaperThemed = true,
            wallpaperBlur = 30,
            wallpaperDim = 70,
        )
        assertEquals(s, parseSettings(s.toJson()))
    }

    @Test fun newAppsSeenAtDefaultsToZeroAndNeverNegative() {
        assertEquals(0L, parseSettings("{}").newAppsSeenAt)
        assertEquals(0L, parseSettings("""{"newAppsSeenAt": -1}""").newAppsSeenAt)
        val s = Settings(newAppsSeenAt = 1_700_000_000_000L)
        assertEquals(s, parseSettings(s.toJson()))
    }
}
