package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** R164 spec §5:搬运(首页移动态 / 编辑页拿起)上下跳过频道行;前后都没有应用行就不动。 */
class MoveChannelTest {
    private val ref = ChannelRef("com.cibn.tv", "k", "酷喵推荐")
    private fun app(p: String) = AppEntry(packageName = p, label = p, card = null, isWide = false)
    private fun appRow(layoutRow: Int, vararg pkgs: String) = Row(apps = pkgs.map { app(it) }, layoutRow = layoutRow)
    private fun chRow(layoutRow: Int) = Row(
        apps = emptyList(), icon = CHANNEL_ROW_ICON, layoutRow = layoutRow, channel = ref,
        programs = listOf(Program(1, "t", null, null, null, 0, null, PosterAspect.R16_9, null, 0)),
    )
    private fun names(rows: List<Row>) = rows.map { r -> if (r.isChannel) listOf("#channel") else r.apps.map { it.packageName } }

    @Test fun cellCountIsProgramsForChannelRowsAndAppsOtherwise() {
        assertEquals(1, chRow(1).cellCount)
        assertEquals(2, appRow(0, "a", "b").cellCount)
    }

    @Test fun downSkipsAChannelRow() {
        val home = listOf(appRow(0, "a", "b"), chRow(1), appRow(2, "c"))
        val (r, p) = moveCard(home, MovePos(0, 1), MoveDir.DOWN)
        assertEquals(listOf(listOf("a"), listOf("#channel"), listOf("c", "b")), names(r))
        assertEquals(MovePos(2, 1), p)
    }

    @Test fun upSkipsAChannelRow() {
        val home = listOf(appRow(0, "a"), chRow(1), appRow(2, "c", "d"))
        val (r, p) = moveCard(home, MovePos(2, 0), MoveDir.UP)
        assertEquals(listOf(listOf("c", "a"), listOf("#channel"), listOf("d")), names(r))
        assertEquals(MovePos(0, 0), p)
    }

    @Test fun noAppRowInThatDirectionStaysPut() {
        val home = listOf(appRow(0, "a"), chRow(1))
        val (r, p) = moveCard(home, MovePos(0, 0), MoveDir.DOWN)
        assertSame(home, r)
        assertEquals(MovePos(0, 0), p)
    }

    @Test fun emptiedSourceRowIsRemovedAndTheIndexFollowsPastTheChannelRow() {
        val home = listOf(appRow(0, "a"), chRow(1), appRow(2, "c"))
        val (r, p) = moveCard(home, MovePos(0, 0), MoveDir.DOWN)
        assertEquals(listOf(listOf("#channel"), listOf("a", "c")), names(r))
        assertEquals(MovePos(1, 0), p)
    }

    @Test fun moveInLayoutSkipsChannelRowsToo() {
        val rows = listOf(LayoutRow("movie", listOf("a", "b")), LayoutRow(CHANNEL_ROW_ICON, channel = ref), LayoutRow("music", listOf("c")))
        val (r, p) = moveInLayout(rows, MovePos(0, 0), MoveDir.DOWN)
        assertEquals(listOf(listOf("b"), emptyList(), listOf("a", "c")), r.map { it.apps })
        assertEquals(ref, r[1].channel)
        assertEquals(MovePos(2, 0), p)
        val lone = listOf(LayoutRow("movie", listOf("a")), LayoutRow(CHANNEL_ROW_ICON, channel = ref))
        assertSame(lone, moveInLayout(lone, MovePos(0, 0), MoveDir.DOWN).first)
    }

    @Test fun mergeMoveLeavesChannelRowsByteForByte() {
        val disk = listOf(LayoutRow("movie", listOf("a", "b")), LayoutRow(CHANNEL_ROW_ICON, channel = ref), LayoutRow("music", listOf("c")))
        val original = listOf(appRow(0, "a", "b"), chRow(1), appRow(2, "c"))
        val (working, _) = moveCard(original, MovePos(0, 1), MoveDir.DOWN)
        val merged = mergeMove(disk, original, working)
        assertSame(disk[1], merged[1])
        assertEquals(listOf("a"), merged[0].apps)
        assertEquals(listOf("c", "b"), merged[2].apps)
    }
}
