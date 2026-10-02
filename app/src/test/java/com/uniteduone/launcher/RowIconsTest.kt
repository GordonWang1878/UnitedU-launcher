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

    /** R163:行没有名字了,旧名字只在读盘时给没存图标的老行回落用([rowIconFromDisk])。 */
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

    @Test fun effectiveIdIsTheStoredOneOrTheDefault() {
        assertEquals("games", effectiveRowIconId("games"))
        assertEquals(NEW_ROW_ICON, effectiveRowIconId(null))
        assertEquals(NEW_ROW_ICON, effectiveRowIconId("bogus"))
        assertEquals(NEW_ROW_ICON, effectiveRowIconId(""))
        assertEquals(NEW_ROW_ICON, effectiveRowIconId("MOVIE"))   // 区分大小写,与 isRowIconId 一致
        for (id in ROW_ICON_IDS) assertEquals(id, effectiveRowIconId(id))
    }

    /** 读盘:存了合法 id 就用;老文件(带 name)没存 / 存了非法 id → 按旧名字;新文件(没有 name)→ 默认图标。 */
    @Test fun diskIconStoredIdWinsThenTheOldNameThenTheDefault() {
        assertEquals("games", rowIconFromDisk("VIDEO", "games"))       // 存了合法 id:不看名字
        assertEquals("movie", rowIconFromDisk("VIDEO", null))          // 老文件没存 icon:按旧名字
        assertEquals("movie", rowIconFromDisk("VIDEO", "bogus"))       // 非法 id 同样
        assertEquals("music", rowIconFromDisk("MUSIC", ""))
        assertEquals("tv", rowIconFromDisk("LIVE", null))
        assertEquals("tv", rowIconFromDisk("影视", null))              // 其它名字一律 tv(与 R133 之前一致)
        assertEquals(NEW_ROW_ICON, rowIconFromDisk(null, null))        // 新文件:没有 name 也没有合法 icon
        assertEquals(NEW_ROW_ICON, rowIconFromDisk(null, "bogus"))
        assertEquals("kids", rowIconFromDisk(null, "kids"))
    }
}
