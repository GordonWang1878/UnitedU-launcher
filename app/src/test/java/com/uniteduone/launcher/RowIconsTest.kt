package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowIconsTest {
    @Test fun twelveIdsInPickerOrder() {
        assertEquals(
            listOf("movie", "tv", "live", "music", "games", "kids", "tools", "education", "sports", "news", "photos", "apps"),
            ROW_ICON_IDS,
        )
        assertEquals("apps", NEW_ROW_ICON)
    }

    @Test fun unknownOrNullIdIsNotAnIcon() {
        assertTrue(isRowIconId("movie"))
        assertFalse(isRowIconId("MOVIE"))
        assertFalse(isRowIconId(""))
        assertFalse(isRowIconId(null))
    }

    @Test fun legacyNamesKeepTodaysIcons() {
        assertEquals("movie", legacyRowIconId("VIDEO"))
        assertEquals("movie", legacyRowIconId("video"))
        assertEquals("tv", legacyRowIconId("LIVE"))
        assertEquals("music", legacyRowIconId("MUSIC"))
        assertEquals("tv", legacyRowIconId("影视"))
    }

    @Test fun storedIdWinsOverTheName() {
        assertEquals("games", effectiveRowIconId("VIDEO", "games"))
        assertEquals("movie", effectiveRowIconId("VIDEO", null))
        assertEquals("movie", effectiveRowIconId("VIDEO", "bogus"))
    }
}
