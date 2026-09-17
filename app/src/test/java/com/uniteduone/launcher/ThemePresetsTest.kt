package com.uniteduone.launcher

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePresetsTest {
    @Test fun materialPurpleIsFirstAndDefault() {
        assertEquals("material", ThemePresets.DEFAULT_ID)
        assertEquals("material", ThemePresets.all.first().id)
        assertEquals(Color(0xFFD0BCFF), ThemePresets.all.first().color)
        assertEquals(Color(0xFFE8DCFF), ThemePresets.all.first().highlight)
    }

    @Test fun oldPresetsStillResolve() {
        assertEquals("gold", ThemePresets.byId("gold").id)
        assertEquals(Color(0xFFC0A73A), ThemePresets.byId("gold").color)
        assertEquals(7, ThemePresets.all.size)
    }

    @Test fun unknownIdFallsBackToMaterial() {
        assertEquals("material", ThemePresets.byId("nope").id)
        assertEquals(0, ThemePresets.indexOf("nope"))
    }
}
