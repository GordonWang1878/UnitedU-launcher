package com.uniteduone.launcher

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ruling R62(2026-09-23 傍晚):五个黑底上很浅、低饱和的预设,淡紫默认;旧 id 迁移。 */
class ThemePresetsTest {
    @Test fun fiveLightPresetsInSwatchOrder() {
        assertEquals(listOf("white", "champagne", "blue", "purple", "green"), ThemePresets.all.map { it.id })
        assertEquals(
            listOf(Color(0xFFF2F2F2), Color(0xFFE4D1AB), Color(0xFFB2C7DC), Color(0xFFC5B6DF), Color(0xFFBBD5B3)),
            ThemePresets.all.map { it.color },
        )
    }

    @Test fun purpleIsDefault() {
        assertEquals("purple", ThemePresets.DEFAULT_ID)
        assertEquals("purple", Settings().themePresetId)
        assertEquals(Color(0xFFC5B6DF), LocalThemeColorsDefault.accent)
    }

    /** highlight 写成显式 hex,但必须等于 highlightFrom(accent) 算出来的值(跟随壁纸时走的是同一条算法)。 */
    @Test fun highlightIsHighlightFromAccent() {
        for (p in ThemePresets.all) assertEquals(p.id, highlightFrom(p.color), p.highlight)
    }

    @Test fun legacyIdsMigrate() {
        assertEquals("purple", ThemePresets.migrateId("material"))
        assertEquals("champagne", ThemePresets.migrateId("gold"))
        assertEquals("white", ThemePresets.migrateId("graphite"))
        assertEquals("white", ThemePresets.migrateId("black"))
        // 同名保留的原样
        for (id in listOf("white", "champagne", "blue", "purple", "green")) assertEquals(id, ThemePresets.migrateId(id))
        // indexOf / byId 认旧 id
        assertEquals(ThemePresets.all.indexOfFirst { it.id == "purple" }, ThemePresets.indexOf("material"))
        assertEquals("champagne", ThemePresets.byId("gold").id)
        assertEquals("white", ThemePresets.byId("graphite").id)
        assertEquals("white", ThemePresets.byId("black").id)
    }

    @Test fun unknownIdFallsBackToDefault() {
        assertEquals("purple", ThemePresets.byId("nope").id)
        assertEquals(3, ThemePresets.indexOf("nope"))
    }

    // LocalThemeColors 的默认值是 staticCompositionLocalOf 的 lambda,JVM 上取不到;这里按它的定义式算一遍。
    private val LocalThemeColorsDefault get() = ThemePresets.byId(ThemePresets.DEFAULT_ID).colors()
}
