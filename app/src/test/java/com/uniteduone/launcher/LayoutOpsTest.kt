package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LayoutOpsTest {
    private val three = listOf(
        LayoutRow("VIDEO", apps = listOf("a", "b")),
        LayoutRow("LIVE", icon = "tv", apps = listOf("c")),
        LayoutRow("MUSIC"),
    )

    @Test fun addInsertsAnEmptyRowBelowWithTheDefaultIcon() {
        val next = addRowBelow(three, 0, "New Row")
        assertEquals(listOf("VIDEO", "New Row", "LIVE", "MUSIC"), next.map { it.name })
        assertEquals(LayoutRow("New Row", icon = "apps"), next[1])
    }

    @Test fun addStopsAtFiveRowsAndOnBadIndex() {
        val five = three + LayoutRow("X") + LayoutRow("Y")
        assertSame(five, addRowBelow(five, 0, "Z"))
        assertSame(three, addRowBelow(three, 3, "Z"))
        assertSame(three, addRowBelow(three, -1, "Z"))
    }

    @Test fun deleteRemovesButNeverTheLastRow() {
        assertEquals(listOf("VIDEO", "MUSIC"), deleteRow(three, 1).map { it.name })
        val one = listOf(LayoutRow("ONLY", apps = listOf("a")))
        assertSame(one, deleteRow(one, 0))
        assertSame(three, deleteRow(three, 5))
    }

    @Test fun renameTrimsAndRejectsBlank() {
        assertEquals("影视", renameRow(three, 0, "  影视 ")[0].name)
        assertSame(three, renameRow(three, 0, "   "))
        assertSame(three, renameRow(three, 9, "X"))
        assertEquals(MAX_TITLE_CHARS, renameRow(three, 0, "x".repeat(100))[0].name.length)
    }

    @Test fun setIconAcceptsOnlyKnownIds() {
        assertEquals("games", setRowIcon(three, 2, "games")[2].icon)
        assertSame(three, setRowIcon(three, 2, "bogus"))
        assertSame(three, setRowIcon(three, 7, "games"))
    }

    @Test fun swapMovesRowsAndKeepsTheirContent() {
        val next = swapRows(three, 0, 1)
        assertEquals(listOf("LIVE", "VIDEO", "MUSIC"), next.map { it.name })
        assertEquals(listOf("a", "b"), next[1].apps)
        assertSame(three, swapRows(three, 0, 3))
        assertSame(three, swapRows(three, 1, 1))
    }
}
