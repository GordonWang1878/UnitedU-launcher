package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MoveTest {
    private fun app(p: String) = AppEntry(packageName = p, label = p, card = null, isWide = false)
    private fun row(name: String, layoutRow: Int, vararg pkgs: String, kind: RowKind = RowKind.APPS) =
        Row(name = name, apps = pkgs.map { app(it) }, kind = kind, layoutRow = layoutRow)
    private fun names(rows: List<Row>) = rows.map { r -> r.apps.map { it.packageName } }

    private val inputs = row("Inputs", -1, "HW2", "HW3", kind = RowKind.INPUTS)
    private val home = listOf(inputs, row("VIDEO", 0, "a", "b", "c"), row("MUSIC", 2, "d"))

    @Test fun leftAndRightSwapWithinTheRowAndStopAtTheEnds() {
        val (r1, p1) = moveCard(home, MovePos(1, 1), MoveDir.LEFT)
        assertEquals(listOf("b", "a", "c"), names(r1)[1]); assertEquals(MovePos(1, 0), p1)
        val (r2, p2) = moveCard(home, MovePos(1, 0), MoveDir.LEFT)
        assertSame(home, r2); assertEquals(MovePos(1, 0), p2)
        val (r3, p3) = moveCard(home, MovePos(1, 2), MoveDir.RIGHT)
        assertSame(home, r3); assertEquals(MovePos(1, 2), p3)
    }

    @Test fun downLandsInTheSameColumnOrAtTheRowEnd() {
        val (r, p) = moveCard(home, MovePos(1, 2), MoveDir.DOWN)
        assertEquals(listOf(listOf("HW2", "HW3"), listOf("a", "b"), listOf("d", "c")), names(r))
        assertEquals(MovePos(2, 1), p)
    }

    @Test fun upSkipsTheInputRow() {
        val (r, p) = moveCard(home, MovePos(1, 0), MoveDir.UP)
        assertSame(home, r); assertEquals(MovePos(1, 0), p)
    }

    @Test fun emptiedSourceRowDisappearsAndTheTargetIndexFollows() {
        // d 在 MUSIC 第 0 列,上移落到 VIDEO 第 0 列;MUSIC 被移空、从工作副本去掉,VIDEO 行号仍是 1
        val (r, p) = moveCard(home, MovePos(2, 0), MoveDir.UP)
        assertEquals(listOf(listOf("HW2", "HW3"), listOf("d", "a", "b", "c")), names(r))
        assertEquals(MovePos(1, 0), p)
    }

    @Test fun emptiedRowAboveTheTargetShiftsTheTargetUp() {
        val rows = listOf(row("A", 0, "a"), row("B", 1, "b1", "b2"))
        val (r, p) = moveCard(rows, MovePos(0, 0), MoveDir.DOWN)
        assertEquals(listOf(listOf("a", "b1", "b2")), names(r))
        assertEquals(MovePos(0, 0), p)
    }

    @Test fun mergeKeepsUnrenderedPackagesAndEmptiedRows() {
        val disk = listOf(
            LayoutRow("VIDEO", apps = listOf("a", "x", "b", "c")),   // x 没装,首页不显示
            LayoutRow("EMPTY"),
            LayoutRow("MUSIC", icon = "music", apps = listOf("d")),
        )
        val original = listOf(row("VIDEO", 0, "a", "b", "c"), row("MUSIC", 2, "d"))
        val working = listOf(row("VIDEO", 0, "b", "a"), row("MUSIC", 2, "d", "c"))
        assertEquals(
            listOf(
                LayoutRow("VIDEO", apps = listOf("b", "a", "x")),
                LayoutRow("EMPTY"),
                LayoutRow("MUSIC", icon = "music", apps = listOf("d", "c")),
            ),
            mergeMove(disk, original, working),
        )
    }

    @Test fun mergeWritesAnEmptiedRowAsEmpty() {
        val disk = listOf(LayoutRow("VIDEO", apps = listOf("a")), LayoutRow("MUSIC", apps = listOf("d")))
        val original = listOf(row("VIDEO", 0, "a"), row("MUSIC", 1, "d"))
        val working = listOf(row("MUSIC", 1, "d", "a"))   // VIDEO 被移空、已从工作副本里去掉
        assertEquals(
            listOf(LayoutRow("VIDEO"), LayoutRow("MUSIC", apps = listOf("d", "a"))),
            mergeMove(disk, original, working),
        )
    }

    @Test fun mergeLeavesAnUntouchedRowExactlyAsOnDisk() {
        // 这次只在 MUSIC 里搬;VIDEO 可见顺序没变,中间那个没装的 x 必须原地不动(不被挪到行尾)
        val disk = listOf(
            LayoutRow("VIDEO", apps = listOf("a", "x", "b")),
            LayoutRow("MUSIC", icon = "music", apps = listOf("d", "e")),
        )
        val original = listOf(row("VIDEO", 0, "a", "b"), row("MUSIC", 1, "d", "e"))
        val working = listOf(row("VIDEO", 0, "a", "b"), row("MUSIC", 1, "e", "d"))
        val merged = mergeMove(disk, original, working)
        assertSame(disk[0], merged[0])
        assertEquals(LayoutRow("MUSIC", icon = "music", apps = listOf("e", "d")), merged[1])
    }

    @Test fun mergeJudgesARowByItsVisibleOrderNotByWhetherTheCardPassedThrough() {
        // 卡从 VIDEO 搬出去又搬回原位:可见顺序与原来相同,这一行照磁盘原样,x 仍在中间
        val disk = listOf(LayoutRow("VIDEO", apps = listOf("a", "x", "b")), LayoutRow("MUSIC", apps = listOf("d")))
        val original = listOf(row("VIDEO", 0, "a", "b"), row("MUSIC", 1, "d"))
        val working = listOf(row("VIDEO", 0, "a", "b"), row("MUSIC", 1, "d"))
        assertEquals(disk, mergeMove(disk, original, working))
    }

    @Test fun changingRowIntoARowThatAlreadyHasTheAppIsANoOp() {
        val rows = listOf(row("VIDEO", 0, "a", "b"), row("MUSIC", 1, "c", "a"), row("LIVE", 2, "e"))
        // a 往下:MUSIC 已经有 a → 原地不动,也不越过 MUSIC 跳到 LIVE
        val (r1, p1) = moveCard(rows, MovePos(0, 0), MoveDir.DOWN)
        assertSame(rows, r1); assertEquals(MovePos(0, 0), p1)
        // MUSIC 的 a 往上:VIDEO 已经有 a → 原地不动
        val (r2, p2) = moveCard(rows, MovePos(1, 1), MoveDir.UP)
        assertSame(rows, r2); assertEquals(MovePos(1, 1), p2)
        // 左右不受影响
        val (r3, p3) = moveCard(rows, MovePos(1, 1), MoveDir.LEFT)
        assertEquals(listOf("a", "c"), names(r3)[1]); assertEquals(MovePos(1, 0), p3)
    }
}
