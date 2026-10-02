package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MoveTest {
    private fun app(p: String) = AppEntry(packageName = p, label = p, card = null, isWide = false)
    // R163:行没有名字,测试里用图标 id 认行(movie / music / tv = 原来的 VIDEO / MUSIC / LIVE)
    private fun row(icon: String, layoutRow: Int, vararg pkgs: String) =
        Row(icon = icon, apps = pkgs.map { app(it) }, layoutRow = layoutRow)
    private fun names(rows: List<Row>) = rows.map { r -> r.apps.map { it.packageName } }

    private val home = listOf(row("movie", 0, "a", "b", "c"), row("music", 2, "d"))

    @Test fun leftAndRightSwapWithinTheRowAndStopAtTheEnds() {
        val (r1, p1) = moveCard(home, MovePos(0, 1), MoveDir.LEFT)
        assertEquals(listOf("b", "a", "c"), names(r1)[0]); assertEquals(MovePos(0, 0), p1)
        val (r2, p2) = moveCard(home, MovePos(0, 0), MoveDir.LEFT)
        assertSame(home, r2); assertEquals(MovePos(0, 0), p2)
        val (r3, p3) = moveCard(home, MovePos(0, 2), MoveDir.RIGHT)
        assertSame(home, r3); assertEquals(MovePos(0, 2), p3)
    }

    @Test fun downLandsInTheSameColumnOrAtTheRowEnd() {
        val (r, p) = moveCard(home, MovePos(0, 2), MoveDir.DOWN)
        assertEquals(listOf(listOf("a", "b"), listOf("d", "c")), names(r))
        assertEquals(MovePos(1, 1), p)
    }

    /** 最上一行往上:没有行了,不动(R92 前这里测的是「跳过置顶的输入源行」,首页没有这一行了)。 */
    @Test fun upFromTheTopRowStaysPut() {
        val (r, p) = moveCard(home, MovePos(0, 0), MoveDir.UP)
        assertSame(home, r); assertEquals(MovePos(0, 0), p)
    }

    @Test fun emptiedSourceRowDisappearsAndTheTargetIndexFollows() {
        // d 在音乐行第 0 列,上移落到影片行第 0 列;音乐行被移空、从工作副本去掉,影片行号仍是 0
        val (r, p) = moveCard(home, MovePos(1, 0), MoveDir.UP)
        assertEquals(listOf(listOf("d", "a", "b", "c")), names(r))
        assertEquals(MovePos(0, 0), p)
    }

    @Test fun emptiedRowAboveTheTargetShiftsTheTargetUp() {
        val rows = listOf(row("movie", 0, "a"), row("music", 1, "b1", "b2"))
        val (r, p) = moveCard(rows, MovePos(0, 0), MoveDir.DOWN)
        assertEquals(listOf(listOf("a", "b1", "b2")), names(r))
        assertEquals(MovePos(0, 0), p)
    }

    @Test fun mergeKeepsUnrenderedPackagesAndEmptiedRows() {
        val disk = listOf(
            LayoutRow("movie", apps = listOf("a", "x", "b", "c")),   // x 没装,首页不显示
            LayoutRow("games"),                                       // 空行
            LayoutRow("music", apps = listOf("d")),
        )
        val original = listOf(row("movie", 0, "a", "b", "c"), row("music", 2, "d"))
        val working = listOf(row("movie", 0, "b", "a"), row("music", 2, "d", "c"))
        assertEquals(
            listOf(
                LayoutRow("movie", apps = listOf("b", "a", "x")),
                LayoutRow("games"),
                LayoutRow("music", apps = listOf("d", "c")),
            ),
            mergeMove(disk, original, working),
        )
    }

    @Test fun mergeWritesAnEmptiedRowAsEmpty() {
        val disk = listOf(LayoutRow("movie", apps = listOf("a")), LayoutRow("music", apps = listOf("d")))
        val original = listOf(row("movie", 0, "a"), row("music", 1, "d"))
        val working = listOf(row("music", 1, "d", "a"))   // 影片行被移空、已从工作副本里去掉
        assertEquals(
            listOf(LayoutRow("movie"), LayoutRow("music", apps = listOf("d", "a"))),
            mergeMove(disk, original, working),
        )
    }

    @Test fun mergeLeavesAnUntouchedRowExactlyAsOnDisk() {
        // 这次只在音乐行里搬;影片行可见顺序没变,中间那个没装的 x 必须原地不动(不被挪到行尾)
        val disk = listOf(
            LayoutRow("movie", apps = listOf("a", "x", "b")),
            LayoutRow("music", apps = listOf("d", "e")),
        )
        val original = listOf(row("movie", 0, "a", "b"), row("music", 1, "d", "e"))
        val working = listOf(row("movie", 0, "a", "b"), row("music", 1, "e", "d"))
        val merged = mergeMove(disk, original, working)
        assertSame(disk[0], merged[0])
        assertEquals(LayoutRow("music", apps = listOf("e", "d")), merged[1])
    }

    @Test fun mergeJudgesARowByItsVisibleOrderNotByWhetherTheCardPassedThrough() {
        // 卡从影片行搬出去又搬回原位:可见顺序与原来相同,这一行照磁盘原样,x 仍在中间
        val disk = listOf(LayoutRow("movie", apps = listOf("a", "x", "b")), LayoutRow("music", apps = listOf("d")))
        val original = listOf(row("movie", 0, "a", "b"), row("music", 1, "d"))
        val working = listOf(row("movie", 0, "a", "b"), row("music", 1, "d"))
        assertEquals(disk, mergeMove(disk, original, working))
    }

    @Test fun changingRowIntoARowThatAlreadyHasTheAppIsANoOp() {
        val rows = listOf(row("movie", 0, "a", "b"), row("music", 1, "c", "a"), row("tv", 2, "e"))
        // a 往下:音乐行已经有 a → 原地不动,也不越过它跳到下一行
        val (r1, p1) = moveCard(rows, MovePos(0, 0), MoveDir.DOWN)
        assertSame(rows, r1); assertEquals(MovePos(0, 0), p1)
        // 音乐行的 a 往上:影片行已经有 a → 原地不动
        val (r2, p2) = moveCard(rows, MovePos(1, 1), MoveDir.UP)
        assertSame(rows, r2); assertEquals(MovePos(1, 1), p2)
        // 左右不受影响
        val (r3, p3) = moveCard(rows, MovePos(1, 1), MoveDir.LEFT)
        assertEquals(listOf("a", "c"), names(r3)[1]); assertEquals(MovePos(1, 0), p3)
    }

    private val layout = listOf(
        LayoutRow("movie", apps = listOf("a", "b", "c")),
        LayoutRow(NEW_ROW_ICON),                // 编辑页里新建的空行
        LayoutRow("music", apps = listOf("d")),
    )

    @Test fun editLeftRightSwapAndStopAtTheEnds() {
        val (r, p) = moveInLayout(layout, MovePos(0, 1), MoveDir.RIGHT)
        assertEquals(listOf("a", "c", "b"), r[0].apps); assertEquals(MovePos(0, 2), p)
        val (r2, p2) = moveInLayout(layout, MovePos(0, 0), MoveDir.LEFT)
        assertSame(layout, r2); assertEquals(MovePos(0, 0), p2)
    }

    @Test fun editDownLandsInAnEmptyRow() {
        val (r, p) = moveInLayout(layout, MovePos(0, 2), MoveDir.DOWN)
        assertEquals(listOf("a", "b"), r[0].apps)
        assertEquals(listOf("c"), r[1].apps)
        assertEquals(MovePos(1, 0), p)
    }

    @Test fun editEmptiedSourceRowStays() {
        val (r, p) = moveInLayout(layout, MovePos(2, 0), MoveDir.UP)
        assertEquals(3, r.size)
        assertEquals(listOf("d"), r[1].apps)
        assertEquals(emptyList<String>(), r[2].apps)
        assertEquals(MovePos(1, 0), p)
    }

    @Test fun editUpIntoARowThatHasTheAppIsANoOp() {
        val rows = listOf(LayoutRow("movie", apps = listOf("x", "y")), LayoutRow("music", apps = listOf("y")))
        val (r, p) = moveInLayout(rows, MovePos(1, 0), MoveDir.UP)
        assertSame(rows, r); assertEquals(MovePos(1, 0), p)
    }

    @Test fun editNoRowInThatDirectionIsANoOp() {
        val (r, p) = moveInLayout(layout, MovePos(0, 0), MoveDir.UP)
        assertSame(layout, r); assertEquals(MovePos(0, 0), p)
        val (r2, p2) = moveInLayout(layout, MovePos(2, 0), MoveDir.DOWN)
        assertSame(layout, r2); assertEquals(MovePos(2, 0), p2)
    }

    @Test fun editColumnClampsToTheShorterRow() {
        val rows = listOf(LayoutRow("movie", apps = listOf("a", "b", "c")), LayoutRow("music", apps = listOf("d")))
        val (r, p) = moveInLayout(rows, MovePos(0, 2), MoveDir.DOWN)
        assertEquals(listOf("d", "c"), r[1].apps)   // 第 2 列越过 B 的行尾 → 放在行尾
        assertEquals(MovePos(1, 1), p)
    }

    @Test fun editRightAtTheRowEndIsANoOp() {
        val (r, p) = moveInLayout(layout, MovePos(0, 2), MoveDir.RIGHT)
        assertSame(layout, r); assertEquals(MovePos(0, 2), p)
    }

    @Test fun editVerticalMoveIntoTheMiddleOfALongerRowKeepsTheColumn() {
        val rows = listOf(
            LayoutRow("movie", apps = listOf("a0", "a1")),
            LayoutRow("music", apps = listOf("b0", "b1", "b2", "b3")),
        )
        // 第 1 列没有越过 B 的行尾(4 张)→ 原列保留,落在 B 中间,不是被夹到行尾
        val (r, p) = moveInLayout(rows, MovePos(0, 1), MoveDir.DOWN)
        assertEquals(listOf("a0"), r[0].apps)
        assertEquals(listOf("b0", "a1", "b1", "b2", "b3"), r[1].apps)
        assertEquals(MovePos(1, 1), p)
    }

    @Test fun editUntouchedRowsKeepIdentityAndTheMovedFromRowKeepsItsIcon() {
        val rows = listOf(
            LayoutRow("movie", apps = listOf("a0", "a1")),
            LayoutRow("music", apps = listOf("b0")),
            LayoutRow("tv", apps = listOf("c0")),
        )
        val (r, p) = moveInLayout(rows, MovePos(0, 0), MoveDir.DOWN)
        assertSame(rows[2], r[2])         // 没碰到的行原样是同一个引用
        assertEquals("movie", r[0].icon)  // 被移空一张的源行只改 apps,icon 原样留着
        assertEquals(MovePos(1, 0), p)
    }

    @Test fun editOutOfRangePosIsANoOp() {
        val (r1, p1) = moveInLayout(layout, MovePos(9, 0), MoveDir.DOWN)
        assertSame(layout, r1); assertEquals(MovePos(9, 0), p1)
        val (r2, p2) = moveInLayout(layout, MovePos(0, 9), MoveDir.RIGHT)
        assertSame(layout, r2); assertEquals(MovePos(0, 9), p2)
    }
}
