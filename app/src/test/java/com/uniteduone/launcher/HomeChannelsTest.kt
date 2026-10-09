package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** R164 首页:哪些频道行画出来、焦点列号按「这一行的格数」夹、海报只加载焦点行 ± 1。 */
class HomeChannelsTest {
    private fun app(p: String) = AppEntry(packageName = p, label = p, card = null, isWide = false)
    private fun prog(id: Long) = Program(id, "t$id", null, null, null, 0, null, PosterAspect.R16_9, null, 0)
    private fun ref(n: Int) = ChannelRef("p$n", "k$n", "c$n")
    private fun chRow(n: Int, layoutRow: Int) = Row(apps = emptyList(), icon = CHANNEL_ROW_ICON, layoutRow = layoutRow, channel = ref(n), channelAppLabel = "App$n")

    /** Review Focus 2:没授权 / 找不到 / 没节目的频道行与空应用行一样不画;layoutRow 在过滤前就定好了。 */
    @Test fun permissionlessAndEmptyChannelRowsAreDropped() {
        val appA = Row(apps = listOf(app("a")), layoutRow = 0)
        val rows = listOf(appA, chRow(1, 1), chRow(2, 2), chRow(3, 3), chRow(4, 4), Row(apps = emptyList(), layoutRow = 5))
        val content = mapOf(
            ref(2) to ChannelContent.NeedsPermission,
            ref(3) to ChannelContent.Missing,
            ref(4) to ChannelContent.Ready(listOf(prog(1), prog(2))),
        )
        val shown = withChannelContent(rows, content)
        assertEquals(listOf(0, 4), shown.map { it.layoutRow })
        assertSame(appA, shown[0])
        assertEquals(listOf(1L, 2L), shown[1].programs.map { it.id })
        assertEquals("App4", shown[1].channelAppLabel)
    }

    @Test fun everythingDeniedLeavesOnlyAppRows() {
        val rows = listOf(Row(apps = listOf(app("a")), layoutRow = 0), chRow(1, 1))
        assertEquals(listOf(0), withChannelContent(rows, mapOf(ref(1) to ChannelContent.NeedsPermission)).map { it.layoutRow })
    }

    /** Review Focus 4:焦点在第 8 张,应用把节目删到 3 张 → 目标列夹到这一行的最后一张(不是行首、不是别的行)。 */
    @Test fun shrinkingRowClampsTheFocusColumnToItsLastCard() {
        val before = listOf(Row(apps = listOf(app("a")), layoutRow = 0), chRow(1, 1).copy(programs = (1L..8L).map { prog(it) }))
        assertEquals(7, homeFocusCol(before, 1, 7))
        val after = listOf(before[0], before[1].copy(programs = (1L..3L).map { prog(it) }))
        assertEquals(2, homeFocusCol(after, 1, 7))
        assertEquals(0, homeFocusCol(after, 0, 7))
        assertEquals("行不在了按 0", 0, homeFocusCol(after, 5, 3))
    }

    @Test fun postersLoadOnlyNearTheFocusRow() {
        assertEquals(listOf(false, true, true, true, false), (0..4).map { loadsPosters(it, 2) })
        assertTrue(loadsPosters(1, 0))
        assertFalse(loadsPosters(2, 0))
    }

    // ---- owner 裁定(2026-10-08):焦点目标按 layoutRow 认行,画出来的行数变了不换行、不清列 ----
    private fun appRow(lr: Int, vararg pkgs: String) = Row(apps = pkgs.map { app(it) }, layoutRow = lr)
    private val ready = chRow(1, 1).copy(programs = (1L..4L).map { prog(it) })

    /** 焦点在频道行下面的应用行,发布方把频道清空 → 频道行不画了,目标仍是同一个 layout 行的同一张卡(画出来的行号 2 → 1)。 */
    @Test fun channelRowAboveDisappearingKeepsTheSameCard() {
        val before = listOf(appRow(0, "a"), ready, appRow(2, "c", "d", "e"))
        assertEquals(2 to 1, homeTargetCell(before, tgtLayoutRow = 2, tgtCol = 1))
        val after = listOf(before[0], before[2])
        assertEquals(1 to 1, homeTargetCell(after, tgtLayoutRow = 2, tgtCol = 1))
    }

    /** 冷启动节目晚到:频道行出现在焦点行上面 → 目标跟着同一个 layout 行往下挪一格,不跳到新冒出来的频道行。 */
    @Test fun channelRowAppearingAboveKeepsTheSameCard() {
        val before = listOf(appRow(0, "a"), appRow(2, "c", "d"))
        assertEquals(1 to 1, homeTargetCell(before, 2, 1))
        val after = listOf(before[0], ready, before[1])
        assertEquals(2 to 1, homeTargetCell(after, 2, 1))
    }

    @Test fun targetRowGoneFallsToTheRowThatTookItsPlace() {
        val rows = listOf(appRow(0, "a"), appRow(3, "c", "d"), appRow(4, "x"))
        assertEquals("layout 行 2 没画 → 补上它位置的行 3", 1, homeTargetRow(rows, 2))
        assertEquals("下面没有了 → 最后一行", 2, homeTargetRow(rows, 9))
        assertEquals("还没定目标(-1)→ 第一行", 0, homeTargetRow(rows, -1))
        assertEquals(0, homeTargetRow(emptyList(), 2))
        assertEquals("列号夹到落点那一行的格数", 2 to 0, homeTargetCell(rows, 4, 5))
    }
}
