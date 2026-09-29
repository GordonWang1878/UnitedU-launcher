package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun renameKeepsTheVisibleIcon() {
        // 没存图标的旧行靠名字回落(MUSIC → music):改名时把这个图标存下来,否则 "Kids" 会回落成 tv
        assertEquals("music", renameRow(three, 2, "Kids")[2].icon)
        assertEquals("movie", renameRow(three, 0, "影视")[0].icon)
        // 已经存了图标的行照旧
        assertEquals("tv", renameRow(three, 1, "直播")[1].icon)
        val games = listOf(LayoutRow("Play", icon = "games", apps = listOf("g")))
        assertEquals(LayoutRow("Fun", icon = "games", apps = listOf("g")), renameRow(games, 0, "Fun")[0])
        // 存了非法 id 的行(手改坏的文件)改名时也被纠正,不能把垃圾 id 原样带过去(终审 Minor #4)
        val invalid = listOf(LayoutRow("VIDEO", icon = "bogus", apps = listOf("z")))
        assertEquals("movie", renameRow(invalid, 0, "影视")[0].icon)
        // 别的行不动;空名、与现名相同(去空白后)都原样返回同一个 list——不写盘,也不钉图标
        assertEquals(null, renameRow(three, 2, "Kids")[0].icon)
        assertSame(three, renameRow(three, 2, "  "))
        assertSame(three, renameRow(three, 2, " MUSIC "))
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

    // ---- dropRemovedElsewhere(编辑页整份写回前的合并)----

    private val snap = listOf(
        LayoutRow("VIDEO", apps = listOf("a", "gone", "b")),
        LayoutRow("MUSIC", apps = listOf("new", "c")),
    )

    @Test fun dropsOnlyPackagesThatWereOnDiskAndGotUninstalled() {
        val disk = listOf(LayoutRow("VIDEO", apps = listOf("a", "b")), LayoutRow("MUSIC", apps = listOf("c")))
        val got = dropRemovedElsewhere(snap, disk, knownOnDisk = setOf("a", "gone", "b", "c")) { false }
        assertEquals(
            listOf(LayoutRow("VIDEO", apps = listOf("a", "b")), LayoutRow("MUSIC", apps = listOf("new", "c"))),
            got,
        )
    }

    @Test fun keepsAReinstalledPackageTheUserAddedBack() {
        val disk = listOf(LayoutRow("VIDEO", apps = listOf("a", "b")), LayoutRow("MUSIC", apps = listOf("c")))
        assertSame(snap, dropRemovedElsewhere(snap, disk, setOf("a", "gone", "b", "c")) { it == "gone" })
    }

    @Test fun nothingRemovedElsewhereReturnsTheSameList() {
        assertSame(snap, dropRemovedElsewhere(snap, snap, setOf("a", "gone", "b", "c")) { false })
    }

    /**
     * 2026-09-30 Codex 评审 P2:编辑页开着时某包被卸载、被别处清掉;第一次保存滤掉它,第二次保存不能再把它写回去。
     * 按 EditScreen.persist 的真实顺序模拟两次保存:内存 rows 始终留着这个包(看不见的包留原下标)。
     */
    @Test fun secondSaveDoesNotResurrectPackageRemovedElsewhere() {
        val rows = listOf(LayoutRow("VIDEO", apps = listOf("installed.app", "removed.app")))
        var known = setOf("installed.app", "removed.app")          // 进页时读到的
        var disk = listOf(LayoutRow("VIDEO", apps = listOf("installed.app")))  // 卸载接收器已把它从盘上清掉
        val installed: (String) -> Boolean = { it == "installed.app" }
        repeat(2) { n ->
            val written = dropRemovedElsewhere(rows, disk, known, installed)
            assertEquals("第 ${n + 1} 次保存", listOf("installed.app"), written.flatMap { it.apps })
            disk = written
            known = knownAfterWrite(known, written)
        }
        assertTrue("removed.app" in known)
    }

    @Test fun knownAfterWriteOnlyGrows() {
        assertEquals(setOf("a", "b", "c"), knownAfterWrite(setOf("a", "b"), listOf(LayoutRow("X", apps = listOf("b", "c")))))
    }
}
