package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

class LayoutOpsTest {
    // R163:行没有名字,测试里用图标 id 认行
    private val three = listOf(
        LayoutRow("movie", apps = listOf("a", "b")),
        LayoutRow("tv", apps = listOf("c")),
        LayoutRow("music"),
    )

    @Test fun addInsertsAnEmptyRowBelowWithTheDefaultIcon() {
        val next = addRowBelow(three, 0)
        assertEquals(listOf("movie", "apps", "tv", "music"), next.map { it.icon })
        assertEquals(LayoutRow(icon = "apps"), next[1])
        assertEquals(NEW_ROW_ICON, next[1].icon)
        assertEquals(emptyList<String>(), next[1].apps)
    }

    @Test fun addStopsAtFiveRowsAndOnBadIndex() {
        val five = three + LayoutRow("games") + LayoutRow("kids")
        assertSame(five, addRowBelow(five, 0))
        assertSame(three, addRowBelow(three, 3))
        assertSame(three, addRowBelow(three, -1))
    }

    /** R165:新行一律追加在最后(「新的一行 → 应用行」),不再有「在下方新建一行」。 */
    @Test fun appendAddsAnEmptyAppRowAtTheEnd() {
        val next = appendAppRow(three)
        assertEquals(listOf("movie", "tv", "music", NEW_ROW_ICON), next.map { it.icon })
        assertEquals(LayoutRow(icon = NEW_ROW_ICON), next.last())
        assertEquals(three, next.take(3))
        assertEquals(3, appRowCount(three))
    }

    @Test fun appendStopsAtFiveAppRows() {
        val five = three + LayoutRow("games") + LayoutRow("kids")
        assertSame(five, appendAppRow(five))
        val six = five + LayoutRow("tools")
        assertSame("手改坏的超量文件不再加", six, appendAppRow(six))
    }

    @Test fun deleteRemovesButNeverTheLastRow() {
        assertEquals(listOf("movie", "music"), deleteRow(three, 1).map { it.icon })
        val one = listOf(LayoutRow("games", apps = listOf("a")))
        assertSame(one, deleteRow(one, 0))
        assertSame(three, deleteRow(three, 5))
    }

    @Test fun setIconAcceptsOnlyKnownIds() {
        assertEquals("games", setRowIcon(three, 2, "games")[2].icon)
        assertSame(three, setRowIcon(three, 2, "bogus"))
        assertSame(three, setRowIcon(three, 7, "games"))
        // 别的行不动
        assertEquals(three[0], setRowIcon(three, 2, "games")[0])
    }

    @Test fun swapMovesRowsAndKeepsTheirContent() {
        val next = swapRows(three, 0, 1)
        assertEquals(listOf("tv", "movie", "music"), next.map { it.icon })
        assertEquals(listOf("a", "b"), next[1].apps)
        assertSame(three, swapRows(three, 0, 3))
        assertSame(three, swapRows(three, 1, 1))
    }

    // ---- dropRemovedElsewhere(编辑页整份写回前的合并)----

    private val snap = listOf(
        LayoutRow("movie", apps = listOf("a", "gone", "b")),
        LayoutRow("music", apps = listOf("new", "c")),
    )

    @Test fun dropsOnlyPackagesThatWereOnDiskAndGotUninstalled() {
        val disk = listOf(LayoutRow("movie", apps = listOf("a", "b")), LayoutRow("music", apps = listOf("c")))
        val got = dropRemovedElsewhere(snap, disk, knownOnDisk = setOf("a", "gone", "b", "c")) { false }
        assertEquals(
            listOf(LayoutRow("movie", apps = listOf("a", "b")), LayoutRow("music", apps = listOf("new", "c"))),
            got,
        )
    }

    @Test fun keepsAReinstalledPackageTheUserAddedBack() {
        val disk = listOf(LayoutRow("movie", apps = listOf("a", "b")), LayoutRow("music", apps = listOf("c")))
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
        val rows = listOf(LayoutRow("movie", apps = listOf("installed.app", "removed.app")))
        var known = setOf("installed.app", "removed.app")          // 进页时读到的
        var disk = listOf(LayoutRow("movie", apps = listOf("installed.app")))  // 卸载接收器已把它从盘上清掉
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
        assertEquals(setOf("a", "b", "c"), knownAfterWrite(setOf("a", "b"), listOf(LayoutRow("games", apps = listOf("b", "c")))))
    }
}
