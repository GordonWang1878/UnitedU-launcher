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

    @Test fun everyIconIdHasItsOwnLabel() {
        // 选择器里的名字:每个 id 对到自己那条 row_icon_<id>;漏写的 id 会静默掉进 else(= 「应用」),两个 id 共用一条
        for (id in ROW_ICON_IDS) {
            assertEquals(id, R.string::class.java.getField("row_icon_$id").getInt(null), rowIconLabel(id))
        }
        assertEquals(ROW_ICON_IDS.size, ROW_ICON_IDS.map { rowIconLabel(it) }.toSet().size)
    }

    @Test fun storedIdWinsOverTheName() {
        assertEquals("games", effectiveRowIconId("VIDEO", "games"))
        assertEquals("movie", effectiveRowIconId("VIDEO", null))
        assertEquals("movie", effectiveRowIconId("VIDEO", "bogus"))
    }
}
