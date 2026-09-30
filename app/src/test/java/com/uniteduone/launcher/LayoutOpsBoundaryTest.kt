package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 行操作的边界(LayoutOpsTest / EditVisibleTest / AppsPageTest 之外):空布局、只有一行、首行 / 末行、
 * 越界下标(负数、== size、Int 极值)、重复包名、换到同一位置、移走一行的最后一张。
 * 各函数的约定:不改就返回**同一个** list(Layout.update 据此不写盘)。
 */
class LayoutOpsBoundaryTest {
    private fun row(name: String, vararg apps: String) = LayoutRow(name, apps = apps.toList())

    private val empty = emptyList<LayoutRow>()
    private val one = listOf(row("ONLY", "a"))
    private val four = listOf(row("A", "a"), row("B", "b"), row("C"), row("D", "d"))

    // ---- 空布局 / 只有一行 ----

    @Test fun everyOpOnAnEmptyLayoutReturnsTheSameList() {
        assertSame(empty, addRowBelow(empty, 0, "X"))
        assertSame(empty, addRowBelow(empty, -1, "X"))
        assertSame(empty, deleteRow(empty, 0))
        assertSame(empty, renameRow(empty, 0, "X"))
        assertSame(empty, setRowIcon(empty, 0, "games"))
        assertSame(empty, addToRow(empty, 0, "A", "p"))
        assertSame(empty, swapRows(empty, 0, 0))
        assertSame(empty, withVisibleEdits(empty, empty) { true })
        assertSame(empty, dropRemovedElsewhere(empty, empty, setOf("a")) { false })
        assertEquals(empty, visibleRows(empty) { true })
    }

    @Test fun aSingleRowCanGrowButIsNeverDeleted() {
        assertEquals(listOf("ONLY", "New"), addRowBelow(one, 0, "New").map { it.name })
        assertSame(one, deleteRow(one, 0))
        assertSame(one, deleteRow(one, -1))
        assertSame(one, swapRows(one, 0, 0))
        assertSame(one, swapRows(one, 0, 1))
    }

    // ---- 加行 ----

    @Test fun addBelowTheLastRowAppendsAndTheFifthRowIsTheLastOneAllowed() {
        val five = addRowBelow(four, 3, "E")
        assertEquals(listOf("A", "B", "C", "D", "E"), five.map { it.name })
        assertEquals(LayoutRow("E", icon = NEW_ROW_ICON), five[4])
        assertEquals(four, five.take(4))
        assertSame(five, addRowBelow(five, 4, "F"))
    }

    @Test fun addBelowIndexEqualToSizeIsOutOfRange() {
        assertSame(four, addRowBelow(four, 4, "E"))
        assertSame(four, addRowBelow(four, Int.MAX_VALUE, "E"))
        assertSame(four, addRowBelow(four, Int.MIN_VALUE, "E"))
    }

    @Test fun anOversizedLayoutIsNotGrownButCanStillShrink() {
        // 手改坏的文件可能多于 MAX_ROWS 行:不再往上加,但删行照常
        val six = four + row("E") + row("F")
        assertSame(six, addRowBelow(six, 0, "G"))
        assertEquals(listOf("A", "B", "C", "D", "E"), deleteRow(six, 5).map { it.name })
    }

    // ---- 删行 ----

    @Test fun deleteTheFirstAndTheLastRow() {
        assertEquals(listOf("B", "C", "D"), deleteRow(four, 0).map { it.name })
        assertEquals(listOf("A", "B", "C"), deleteRow(four, 3).map { it.name })
        val two = listOf(row("A", "a"), row("B", "b"))
        assertEquals(listOf(row("B", "b")), deleteRow(two, 0))
        assertEquals(listOf(row("A", "a")), deleteRow(two, 1))
    }

    @Test fun deleteOutOfRangeIsANoOp() {
        assertSame(four, deleteRow(four, 4))
        assertSame(four, deleteRow(four, -1))
        assertSame(four, deleteRow(four, Int.MIN_VALUE))
        assertSame(four, deleteRow(four, Int.MAX_VALUE))
    }

    @Test fun deletingARowLeavesTheOtherRowsUntouched() {
        val out = deleteRow(four, 1)
        assertSame(four[0], out[0])
        assertSame(four[2], out[1])
        assertSame(four[3], out[2])
    }

    // ---- 改名 / 换图标 ----

    @Test fun renameOutOfRangeOrToOnlyControlCharactersIsANoOp() {
        assertSame(four, renameRow(four, -1, "X"))
        assertSame(four, renameRow(four, 4, "X"))
        assertSame(four, renameRow(four, 0, "\u0000\u0001\u007F"))
        assertSame(four, renameRow(four, 0, "\n\t "))
    }

    @Test fun renameKeepsTheAppsAndNeverLeavesHalfAnEmoji() {
        val out = renameRow(four, 0, "a".repeat(MAX_TITLE_CHARS - 1) + "🌊🌊")
        assertEquals(listOf("a"), out[0].apps)
        // 截到第 40 个 UTF-16 单元时正好切在 emoji 中间:连前半一起丢
        assertEquals("a".repeat(MAX_TITLE_CHARS - 1), out[0].name)
        assertSame(four[1], out[1])
    }

    @Test fun renameToTheSameNameAfterTruncationIsANoOp() {
        val long = listOf(row("x".repeat(MAX_TITLE_CHARS), "a"))
        assertSame(long, renameRow(long, 0, "x".repeat(MAX_TITLE_CHARS + 5)))
    }

    @Test fun setIconOnTheLastRowAndOutOfRange() {
        val out = setRowIcon(four, 3, "games")
        assertEquals(LayoutRow("D", icon = "games", apps = listOf("d")), out[3])
        assertSame(four[0], out[0])
        assertSame(four, setRowIcon(four, -1, "games"))
        assertSame(four, setRowIcon(four, 4, "games"))
        assertSame(four, setRowIcon(four, 0, ""))
    }

    // ---- 加到某一行 ----

    @Test fun addToRowOutOfRangeOrWithAStaleNameIsANoOp() {
        assertSame(four, addToRow(four, -1, "A", "p"))
        assertSame(four, addToRow(four, 4, "A", "p"))
        // 行名要逐字对上(大小写、空白都算):菜单打开后那一行被改了名
        assertSame(four, addToRow(four, 0, "a", "p"))
        assertSame(four, addToRow(four, 0, " A", "p"))
    }

    @Test fun addToAnEmptyRowAndToTheLastRow() {
        assertEquals(listOf("p"), addToRow(four, 2, "C", "p")[2].apps)
        assertEquals(listOf("d", "p"), addToRow(four, 3, "D", "p")[3].apps)
    }

    @Test fun aPackageInAnotherRowMayBeAddedAgain() {
        // 一行里一个包只能有一张;不同行各有一张是允许的(Layout.read 只按行去重)
        val out = addToRow(four, 0, "A", "d")
        assertEquals(listOf("a", "d"), out[0].apps)
        assertEquals(listOf("d"), out[3].apps)
    }

    // ---- 换行序 ----

    @Test fun swapTheFirstAndTheLastRowAndBack() {
        val out = swapRows(four, 0, 3)
        assertEquals(listOf("D", "B", "C", "A"), out.map { it.name })
        assertEquals(four, swapRows(out, 3, 0))
    }

    @Test fun swapOutOfRangeOrWithItselfIsANoOp() {
        assertSame(four, swapRows(four, -1, 0))
        assertSame(four, swapRows(four, 0, -1))
        assertSame(four, swapRows(four, 4, 3))
        assertSame(four, swapRows(four, 3, 4))
        assertSame(four, swapRows(four, 3, 3))
        assertSame(four, swapRows(four, Int.MIN_VALUE, Int.MAX_VALUE))
    }

    // ---- 编辑页整份写回前的合并 ----

    @Test fun aRemovedPackageIsDroppedFromEveryRowItAppearsIn() {
        val snap = listOf(row("A", "x", "gone"), row("B", "gone", "y"))
        val disk = listOf(row("A", "x"), row("B", "y"))
        assertEquals(disk, dropRemovedElsewhere(snap, disk, setOf("x", "gone", "y")) { false })
    }

    @Test fun removingTheLastAppOfARowKeepsTheEmptyRow() {
        val snap = listOf(row("A", "gone"), row("B", "y"))
        val out = dropRemovedElsewhere(snap, listOf(row("A"), row("B", "y")), setOf("gone", "y")) { false }
        assertEquals(listOf(row("A"), row("B", "y")), out)
        assertSame(snap[1], out[1])
    }

    @Test fun anEmptyDiskDropsOnlyKnownUninstalledPackages() {
        val snap = listOf(row("A", "x", "new"))
        assertEquals(listOf(row("A", "new")), dropRemovedElsewhere(snap, emptyList(), setOf("x")) { false })
        assertSame(snap, dropRemovedElsewhere(snap, emptyList(), emptySet()) { false })
    }

    @Test fun knownAfterWriteWithEmptyInputs() {
        assertEquals(emptySet<String>(), knownAfterWrite(emptySet(), emptyList()))
        assertEquals(setOf("a"), knownAfterWrite(setOf("a"), emptyList()))
        assertEquals(setOf("a"), knownAfterWrite(emptySet(), listOf(row("A", "a", "a"))))
    }

    // ---- 可见那份合回整份 ----

    @Test fun aRowWhoseAppsAreAllHiddenKeepsThemAheadOfNewVisibleApps() {
        val shown: (String) -> Boolean = { it.startsWith("v") }
        val full = listOf(row("A", "h1", "h2"))
        assertEquals(listOf("h1", "h2", "v1"), withVisibleEdits(full, listOf(row("A", "v1")), shown)[0].apps)
        assertSame(full, withVisibleEdits(full, visibleRows(full, shown), shown))
    }

    @Test fun movingTheOnlyVisibleAppOutLeavesTheHiddenOnes() {
        val shown: (String) -> Boolean = { it.startsWith("v") }
        val full = listOf(row("A", "h1", "v1", "h2"), row("B"))
        val merged = withVisibleEdits(full, listOf(row("A"), row("B", "v1")), shown)
        assertEquals(listOf(listOf("h1", "h2"), listOf("v1")), merged.map { it.apps })
    }
}
