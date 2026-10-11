package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** R164 / R165 spec §4:应用行 1–5 行(只数应用行),频道行 0–5 行,各自判。 */
class ChannelRowRulesTest {
    private fun app(vararg pkgs: String) = LayoutRow("movie", pkgs.toList())
    private fun ch(n: Int) = LayoutRow(CHANNEL_ROW_ICON, channel = ChannelRef("p$n", "k$n", "c$n"))

    @Test fun countsAreSeparate() {
        val rows = listOf(app("a"), ch(1), app(), ch(2), ch(3))
        assertEquals(2, appRowCount(rows))
        assertEquals(3, channelRowCount(rows))
    }

    @Test fun channelRowIconIsALegalRowIcon() {
        assertTrue(isRowIconId(CHANNEL_ROW_ICON))
    }

    @Test fun appRowsFillToFiveRegardlessOfChannelRows() {
        val fourApps = List(4) { app() } + List(5) { ch(it) }
        val added = appendAppRow(fourApps)
        assertEquals(10, added.size)
        assertEquals(LayoutRow(NEW_ROW_ICON), added.last())
        val fiveApps = List(5) { app() } + List(2) { ch(it) }
        assertSame(fiveApps, appendAppRow(fiveApps))
    }

    @Test fun channelRowsFillToFiveAndAreAppendedLast() {
        val rows = listOf(app("a"), ch(1))
        val ref = ChannelRef("com.cibn.tv", "k", "酷喵推荐")
        val added = appendChannelRow(rows, ref)
        assertEquals(LayoutRow(CHANNEL_ROW_ICON, emptyList(), ref), added.last())
        val full = listOf(app("a")) + List(MAX_CHANNEL_ROWS) { ch(it) }
        assertFalse(canAddChannelRow(full))
        assertSame(full, appendChannelRow(full, ref))
    }

    @Test fun theSameChannelIsNotAddedTwice() {
        val ref = ChannelRef("p1", "k1", "c1")
        val rows = listOf(app("a"), ch(1))
        assertSame(rows, appendChannelRow(rows, ref))
    }

    @Test fun lastAppRowCannotBeDeletedButChannelRowsAlwaysCan() {
        val rows = listOf(app("a"), ch(1), ch(2))
        assertSame(rows, deleteRow(rows, 0))
        assertEquals(listOf(app("a"), ch(2)), deleteRow(rows, 1))
    }

    @Test fun addToRowRefusesAChannelRow() {
        val rows = listOf(app("a"), ch(1))
        assertSame(rows, addToRow(rows, 1, "com.x"))
        assertEquals(listOf("a", "com.x"), addToRow(rows, 0, "com.x")[0].apps)
    }

    @Test fun setRowIconLeavesChannelRowsAlone() {
        val rows = listOf(app("a"), ch(1))
        assertSame(rows, setRowIcon(rows, 1, "music"))
    }

    @Test fun appRowIndicesSkipChannelRows() {
        assertEquals(listOf(0, 2), appRowIndices(listOf(app("a"), ch(1), app("b"), ch(2))))
    }

    /** Review Focus 5:旧版本写出 7 个应用行。读得回、不能再加应用行、删行照常。 */
    @Test fun legacySevenAppRowsAreReadableButFull() {
        val rows = List(7) { app("p$it") }
        assertFalse(canAddAppRow(rows))
        assertSame(rows, appendAppRow(rows))
        assertEquals(6, deleteRow(rows, 3).size)
    }
}
