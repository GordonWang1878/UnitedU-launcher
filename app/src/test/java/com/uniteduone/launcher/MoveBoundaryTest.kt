package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 搬卡的边界(MoveTest 之外):越界 / 负数位置在四个方向上都不动、空首页、唯一一张卡、最后一行往下、
 * 左右来回复原、首页行号不变;编辑页穿过空行;合并时磁盘比工作副本短、不写出重复包。
 */
class MoveBoundaryTest {
    private fun app(p: String) = AppEntry(packageName = p, label = p, card = null, isWide = false)
    private fun row(name: String, layoutRow: Int, vararg pkgs: String) =
        Row(name = name, apps = pkgs.map { app(it) }, layoutRow = layoutRow)
    private fun names(rows: List<Row>) = rows.map { r -> r.apps.map { it.packageName } }

    private val home = listOf(row("VIDEO", 0, "a", "b", "c"), row("MUSIC", 2, "d"))

    // ---- 首页 ----

    @Test fun outOfRangePositionsAreNoOpsInEveryDirection() {
        val bad = listOf(MovePos(-1, 0), MovePos(0, -1), MovePos(2, 0), MovePos(0, 3), MovePos(1, 1), MovePos(Int.MIN_VALUE, Int.MAX_VALUE))
        for (pos in bad) {
            for (dir in MoveDir.entries) {
                val (r, p) = moveCard(home, pos, dir)
                assertSame("$pos $dir", home, r)
                assertEquals("$pos $dir", pos, p)
            }
        }
    }

    @Test fun anEmptyHomeIsANoOp() {
        val empty = emptyList<Row>()
        for (dir in MoveDir.entries) {
            val (r, p) = moveCard(empty, MovePos(0, 0), dir)
            assertSame(empty, r)
            assertEquals(MovePos(0, 0), p)
        }
    }

    @Test fun theOnlyCardOnTheHomeCannotMove() {
        val one = listOf(row("ONLY", 0, "a"))
        for (dir in MoveDir.entries) {
            val (r, p) = moveCard(one, MovePos(0, 0), dir)
            assertSame(one, r)
            assertEquals(MovePos(0, 0), p)
        }
    }

    @Test fun downFromTheLastRowStaysPut() {
        val (r, p) = moveCard(home, MovePos(1, 0), MoveDir.DOWN)
        assertSame(home, r)
        assertEquals(MovePos(1, 0), p)
    }

    @Test fun leftThenRightRestoresTheRow() {
        val (r1, p1) = moveCard(home, MovePos(0, 2), MoveDir.LEFT)
        val (r2, p2) = moveCard(r1, p1, MoveDir.RIGHT)
        assertEquals(names(home), names(r2))
        assertEquals(MovePos(0, 2), p2)
    }

    @Test fun movingKeepsTheLayoutRowNumbersOfTheRenderedRows() {
        // 首页不显示空行:渲染行号 0、1 对应 layout.json 的 0、3,搬完仍是 0、3
        val rows = listOf(row("A", 0, "a", "b"), row("B", 3, "c"))
        val (r, p) = moveCard(rows, MovePos(0, 1), MoveDir.DOWN)
        assertEquals(listOf(0, 3), r.map { it.layoutRow })
        assertEquals(MovePos(1, 1), p)
    }

    @Test fun emptyingTheTopRowKeepsTheLayoutRowNumbersOfTheRest() {
        // 源行被移空、从工作副本里去掉:剩下的行仍带着自己在 layout.json 里的行号(放下时按它合并)
        val rows = listOf(row("A", 0, "a"), row("B", 1, "b"), row("C", 2, "c"))
        val (r, p) = moveCard(rows, MovePos(0, 0), MoveDir.DOWN)
        assertEquals(listOf(listOf("a", "b"), listOf("c")), names(r))
        assertEquals(listOf(1, 2), r.map { it.layoutRow })
        assertEquals(MovePos(0, 0), p)
    }

    // ---- 编辑页 ----

    private val layout = listOf(
        LayoutRow("VIDEO", apps = listOf("a", "b")),
        LayoutRow("EMPTY"),
        LayoutRow("MUSIC", apps = listOf("d")),
    )

    @Test fun editNegativeOrCardlessPositionsAreNoOps() {
        for (pos in listOf(MovePos(-1, 0), MovePos(0, -1), MovePos(1, 0), MovePos(3, 0))) {
            for (dir in MoveDir.entries) {
                val (r, p) = moveInLayout(layout, pos, dir)
                assertSame("$pos $dir", layout, r)
                assertEquals(pos, p)
            }
        }
    }

    @Test fun editMovingThroughAnEmptyRowKeepsEveryRow() {
        val (r1, p1) = moveInLayout(layout, MovePos(0, 1), MoveDir.DOWN)
        assertEquals(MovePos(1, 0), p1)
        val (r2, p2) = moveInLayout(r1, p1, MoveDir.DOWN)
        assertEquals(MovePos(2, 0), p2)
        assertEquals(listOf(listOf("a"), emptyList(), listOf("b", "d")), r2.map { it.apps })
        assertEquals(listOf("VIDEO", "EMPTY", "MUSIC"), r2.map { it.name })
    }

    @Test fun editEmptiedRowCanBeRefilledAndStaysInPlace() {
        val one = listOf(LayoutRow("A", apps = listOf("a")), LayoutRow("B"))
        val (r1, p1) = moveInLayout(one, MovePos(0, 0), MoveDir.DOWN)
        assertEquals(listOf(emptyList(), listOf("a")), r1.map { it.apps })
        val (r2, p2) = moveInLayout(r1, p1, MoveDir.UP)
        assertEquals(one, r2)
        assertEquals(MovePos(0, 0), p2)
    }

    // ---- 放下时的合并 ----

    @Test fun mergeWithAnEmptyDiskIsEmpty() {
        assertEquals(emptyList<LayoutRow>(), mergeMove(emptyList(), home, home))
    }

    @Test fun mergeIgnoresRenderedRowsThatNoLongerExistOnDisk() {
        val disk = listOf(LayoutRow("VIDEO", apps = listOf("a", "b", "c")))
        assertEquals(disk, mergeMove(disk, home, listOf(row("VIDEO", 0, "a", "b", "c"), row("MUSIC", 2, "c", "d"))))
    }

    @Test fun mergeNeverWritesAPackageTwice() {
        // 工作副本里出现了磁盘上首页没显示的 x(不该发生):distinct 保证一行里不写出两个 x
        val disk = listOf(LayoutRow("VIDEO", apps = listOf("a", "x")))
        val merged = mergeMove(disk, listOf(row("VIDEO", 0, "a")), listOf(row("VIDEO", 0, "x", "a")))
        assertEquals(listOf("x", "a"), merged[0].apps)
    }
}
