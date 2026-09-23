package com.uniteduone.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun themePresetIdFallsBackToDefaultWhenAbsentOrBlank() {
        // R62:默认淡紫 "purple"(此前 "material")
        assertEquals("purple", parseSettings("""{}""").themePresetId)
        assertEquals("purple", parseSettings("""{"themePresetId": ""}""").themePresetId)
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

    @Test fun legacyThemedCardsKeyIsIgnored() {
        // 2026-09-23 删掉「主题化卡片」(gtv spec R58):升级前的 settings.json 里还带着 themedCards 键。
        // 与 wallpaperThemed 同一写法:按未知键忽略,其余字段照常解析,写盘也不再带它。
        val s = parseSettings("""{"themedCards": true, "showTitles": true, "themePresetId": "blue"}""")
        assertEquals(Settings(showTitles = true, themePresetId = "blue"), s)
        assertFalse(s.toJson().contains("themedCards"))
        assertEquals(Settings(), parseSettings("""{"themedCards": false}"""))
    }

    @Test fun wallpaperFieldsDefaultWhenAbsent() {
        val s = parseSettings("{}")
        assertEquals("", s.wallpaperFile)
        assertEquals(0, s.wallpaperBlur)
        assertEquals(0, s.wallpaperBrightness)
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

    @Test fun legacyWallpaperRotateKeysAreIgnored() {
        // 2026-09-23 删掉「壁纸自动切换」(gtv spec R61):升级前的 settings.json 里还带着这两个键。
        // 与 wallpaperThemed 同一写法:按未知键忽略,其余字段照常解析,写盘也不再带它们。
        val s = parseSettings(
            """{"wallpaperRotateMs": 1800000, "wallpaperRotatedAt": 1700000000000, "wallpaperFile": "sea.jpg", "wallpaperBlur": 30}""",
        )
        assertEquals(Settings(wallpaperFile = "sea.jpg", wallpaperBlur = 30), s)
        assertFalse(s.toJson().contains("wallpaperRotate"))
        assertEquals(Settings(), parseSettings("""{"wallpaperRotateMs": 0, "wallpaperRotatedAt": 0}"""))
    }

    @Test fun blurAndDimClampAndSnapToTens() {
        assertEquals(0, parseSettings("""{"wallpaperBlur": -20}""").wallpaperBlur)
        assertEquals(100, parseSettings("""{"wallpaperBlur": 250}""").wallpaperBlur)
        assertEquals(50, parseSettings("""{"wallpaperBlur": 54}""").wallpaperBlur)
        assertEquals(60, parseSettings("""{"wallpaperBlur": 55}""").wallpaperBlur)
        assertEquals(50, parseSettings("""{"wallpaperBrightness": 96}""").wallpaperBrightness)
        assertEquals(-50, parseSettings("""{"wallpaperBrightness": -70}""").wallpaperBrightness)
        assertEquals(-20, parseSettings("""{"wallpaperBrightness": -24}""").wallpaperBrightness)
        assertEquals(30, parseSettings("""{"wallpaperBrightness": 25}""").wallpaperBrightness)
        assertEquals(0, parseSettings("""{"wallpaperBrightness": "x"}""").wallpaperBrightness)
    }

    @Test fun legacyWallpaperDimMigratesToNegativeBrightness() {
        // M3 的 settings.json 只有 wallpaperDim(0–100 压暗);升级后换算成负亮度,超过 50 的压暗夹到 −50
        assertEquals(-30, parseSettings("""{"wallpaperDim": 30}""").wallpaperBrightness)
        assertEquals(-50, parseSettings("""{"wallpaperDim": 70}""").wallpaperBrightness)
        assertEquals(0, parseSettings("""{"wallpaperDim": 0}""").wallpaperBrightness)
        // 两个键都在:新键为准
        assertEquals(20, parseSettings("""{"wallpaperDim": 70, "wallpaperBrightness": 20}""").wallpaperBrightness)
        assertFalse(Settings(wallpaperBrightness = -10).toJson().contains("wallpaperDim"))
    }

    @Test fun wallpaperFieldsRoundTrip() {
        val s = Settings(
            wallpaperFile = "sea.jpg",
            wallpaperBlur = 30,
            wallpaperBrightness = -30,
        )
        assertEquals(s, parseSettings(s.toJson()))
    }

    @Test fun legacyWallpaperThemedKeyIsIgnored() {
        // 2026-09-16 删掉「主题化壁纸」:升级前的 settings.json 里还带着 wallpaperThemed 键。
        // 扁平 tokenizer 只认列出的字段,这个键按未知键忽略,其余字段照常解析,写盘也不再带它。
        val s = parseSettings("""{"wallpaperThemed": true, "wallpaperBlur": 20, "wallpaperBrightness": -10}""")
        assertEquals(Settings(wallpaperBlur = 20, wallpaperBrightness = -10), s)
        assertFalse(s.toJson().contains("wallpaperThemed"))
        assertEquals(Settings(), parseSettings("""{"wallpaperThemed": false}"""))
    }

    @Test fun newAppsSeenAtDefaultsToZeroAndNeverNegative() {
        assertEquals(0L, parseSettings("{}").newAppsSeenAt)
        assertEquals(0L, parseSettings("""{"newAppsSeenAt": -1}""").newAppsSeenAt)
        val s = Settings(newAppsSeenAt = 1_700_000_000_000L)
        assertEquals(s, parseSettings(s.toJson()))
    }

    @Test fun languageParsesAndDefaults() {
        assertEquals("system", parseSettings("{}").language)
        assertEquals("zh-TW", parseSettings("""{"language": "zh-TW"}""").language)
        assertEquals("system", parseSettings("""{"language": "fr"}""").language)
        assertEquals("system", parseSettings("""{"language": 5}""").language)
    }

    @Test fun onboardingDoneIsTriState() {
        assertNull(parseSettings("{}").onboardingDone)
        assertEquals(true, parseSettings("""{"onboardingDone": true}""").onboardingDone)
        assertFalse(Settings().toJson().contains("onboardingDone"))
        assertTrue(Settings(onboardingDone = false).toJson().contains("\"onboardingDone\": false"))
    }

    @Test fun restoredDefaultsKeepsOnlyBaselineAndOnboarding() {
        val cur = Settings(cardsPerRow = 8, showTitles = true, language = "en", wallpaperBlur = 50,
            newAppsSeenAt = 1L, onboardingDone = true)
        val r = restoredDefaults(cur, 999L)
        assertEquals(Settings().cardsPerRow, r.cardsPerRow); assertFalse(r.showTitles)
        assertEquals("system", r.language); assertEquals(0, r.wallpaperBlur)
        assertEquals(999L, r.newAppsSeenAt); assertEquals(true, r.onboardingDone)
        assertEquals(r, parseSettings(r.toJson()))
    }

    @Test fun defaultThemePresetIsPurple() {
        assertEquals("purple", Settings().themePresetId)
        assertEquals("purple", parseSettings("{}").themePresetId)
    }

    /** R62:旧预设 id 读盘时就换成新的,下次写盘不再带旧 id。 */
    @Test fun legacyPresetIdsMigrateOnRead() {
        assertEquals("purple", parseSettings("""{"themePresetId": "material"}""").themePresetId)
        assertEquals("champagne", parseSettings("""{"themePresetId": "gold"}""").themePresetId)
        assertEquals("white", parseSettings("""{"themePresetId": "graphite"}""").themePresetId)
        assertEquals("white", parseSettings("""{"themePresetId": "black"}""").themePresetId)
        assertEquals("blue", parseSettings("""{"themePresetId": "blue"}""").themePresetId)
        assertTrue(parseSettings("""{"themePresetId": "gold"}""").toJson().contains("\"themePresetId\": \"champagne\""))
    }

    @Test fun screensaverFieldsDefaultWhenAbsent() {
        // 不需要迁移(spec §0):旧 settings.json 没有这两个键 → 屏保启动 5 分、轮播 30 秒
        val s = parseSettings("""{"idleAfterMs": 180000}""")
        assertEquals(300_000L, s.screensaverAfterMs)
        assertEquals(30_000L, s.screensaverIntervalMs)
        assertEquals(300_000L, Settings().screensaverAfterMs)
        assertEquals(30_000L, Settings().screensaverIntervalMs)
    }

    @Test fun screensaverAfterMsMustBeOneOfAllowedValues() {
        assertEquals(300_000L, parseSettings("""{"screensaverAfterMs": 12345}""").screensaverAfterMs)
        assertEquals(300_000L, parseSettings("""{"screensaverAfterMs": "x"}""").screensaverAfterMs)
        for (v in listOf(0L, 60_000L, 300_000L, 600_000L, 1_800_000L)) {
            assertEquals(v, parseSettings("""{"screensaverAfterMs": $v}""").screensaverAfterMs)
        }
    }

    @Test fun screensaverIntervalMsMustBeOneOfAllowedValues() {
        assertEquals(30_000L, parseSettings("""{"screensaverIntervalMs": 45000}""").screensaverIntervalMs)
        assertEquals(30_000L, parseSettings("""{"screensaverIntervalMs": 0}""").screensaverIntervalMs)
        for (v in listOf(30_000L, 60_000L, 300_000L)) {
            assertEquals(v, parseSettings("""{"screensaverIntervalMs": $v}""").screensaverIntervalMs)
        }
    }

    @Test fun screensaverFieldsRoundTrip() {
        val s = Settings(screensaverAfterMs = 0L, screensaverIntervalMs = 300_000L)
        assertTrue(s.toJson().contains("\"screensaverAfterMs\": 0"))
        assertTrue(s.toJson().contains("\"screensaverIntervalMs\": 300000"))
        assertEquals(s, parseSettings(s.toJson()))
    }

    @Test fun restoredDefaultsResetsScreensaverFields() {
        val r = restoredDefaults(Settings(screensaverAfterMs = 1_800_000L, screensaverIntervalMs = 60_000L), 1L)
        assertEquals(300_000L, r.screensaverAfterMs)
        assertEquals(30_000L, r.screensaverIntervalMs)
    }

    @Test fun strictParseThrowsOnBrokenSyntaxButAcceptsPartialObjects() {
        assertTrue(runCatching { parseSettingsStrict("{\"rowCount\":5}{\"cardsPerRow\":8}") }.isFailure)
        assertTrue(runCatching { parseSettingsStrict("{半截") }.isFailure)
        assertEquals(parseSettings("{}"), parseSettingsStrict("{}"))
    }

    // ---- R68:卡片淡化两项 ----

    /** 旧文件没有这两个键 → 30 / 75 = R49 原来写死的常量,观感零变化。 */
    @Test fun cardFadeDefaultsMatchR49Constants() {
        val s = parseSettings("{\"cardsPerRow\": 6}")
        assertEquals(30, s.cardSaturation)
        assertEquals(75, s.cardBrightness)
        assertEquals(Math.round(GtvLayout.CARD_FADE_SATURATION * 100), s.cardSaturation)
        assertEquals(Math.round(GtvLayout.CARD_FADE_BRIGHTNESS * 100), s.cardBrightness)
        assertEquals(CardFade.DEFAULT, s.cardFade())
    }

    @Test fun cardFadeClampsAndSnaps() {
        val a = parseSettings("{\"cardSaturation\": 44, \"cardBrightness\": 83}")
        assertEquals(40, a.cardSaturation)
        assertEquals(85, a.cardBrightness)
        val b = parseSettings("{\"cardSaturation\": -5, \"cardBrightness\": 12}")
        assertEquals(0, b.cardSaturation)
        assertEquals(50, b.cardBrightness)
        val c = parseSettings("{\"cardSaturation\": 250, \"cardBrightness\": 400}")
        assertEquals(100, c.cardSaturation)
        assertEquals(100, c.cardBrightness)
        val d = parseSettings("{\"cardSaturation\": \"x\", \"cardBrightness\": true}")
        assertEquals(30, d.cardSaturation)
        assertEquals(75, d.cardBrightness)
    }

    @Test fun cardFadeRoundTripsAndRestores() {
        val s = Settings(cardSaturation = 70, cardBrightness = 95)
        assertEquals(s, parseSettings(s.toJson()))
        val r = restoredDefaults(s, 1L)
        assertEquals(30, r.cardSaturation)
        assertEquals(75, r.cardBrightness)
    }

    /** 缺省参数下的矩阵与 R49 原矩阵逐项相同;100 / 100 是恒等(淡化层整个跳过)。 */
    @Test fun cardFadeMatrixFollowsSettings() {
        assertArrayEquals(GtvLayout.cardFadeMatrix(), CardFade.DEFAULT.matrix(), 1e-6f)
        val id = CardFade(100, 100)
        assertTrue(id.isIdentity)
        val m = id.matrix()
        assertEquals(1f, m[0], 1e-6f); assertEquals(0f, m[1], 1e-6f); assertEquals(1f, m[6], 1e-6f)
        assertFalse(CardFade.DEFAULT.isIdentity)
    }
}
