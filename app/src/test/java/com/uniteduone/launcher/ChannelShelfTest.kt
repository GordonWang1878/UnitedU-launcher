package com.uniteduone.launcher

import com.uniteduone.launcher.ShelfZone.CARDS
import com.uniteduone.launcher.ShelfZone.CHIPS
import com.uniteduone.launcher.ShelfZone.NEW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R165 §2.1–§2.2 / R164:编辑页的频道架子——状态、胶囊、焦点 lane、夹取与落点、页头计数、海报预览尺寸。 */
class ChannelShelfTest {
    private val ref = ChannelRef("com.cibn.tv", "k", "酷喵推荐")
    private val view = listOf(
        LayoutRow("movie", listOf("a")),
        LayoutRow(CHANNEL_ROW_ICON, channel = ref),
        LayoutRow("music", emptyList()),
    )
    private fun shelves(c: ChannelContent? = null) =
        shelvesOf(view, mapOf("com.cibn.tv" to "CIBN酷喵"), c?.let { mapOf(ref to it) } ?: emptyMap())

    @Test fun shelvesMapChannelRowsToChannelShelves() {
        val s = shelves(ChannelContent.NeedsPermission)
        assertEquals(4, s.size)
        assertEquals(Shelf.ChannelShelf(1, ref, "CIBN酷喵", ChannelShelfState.NeedsPermission), s[1])
        assertEquals(Shelf.NewRowShelf, s.last())
        assertEquals("没有应用名时用包名", "com.cibn.tv", (shelvesOf(view)[1] as Shelf.ChannelShelf).appLabel)
    }

    @Test fun shelfStateFollowsContent() {
        val p = Program(1, "t", null, null, null, 0, null, PosterAspect.R16_9, null, 0)
        assertEquals(ChannelShelfState.Posters(listOf(p)), channelShelfState(ChannelContent.Ready(listOf(p))))
        assertEquals(ChannelShelfState.Empty, channelShelfState(ChannelContent.Missing))
        assertEquals("还没读到当暂无内容", ChannelShelfState.Empty, channelShelfState(null))
        assertEquals(ChannelShelfState.NeedsPermission, channelShelfState(ChannelContent.NeedsPermission))
    }

    @Test fun channelChips() {
        assertEquals(listOf(ShelfChip.UP, ShelfChip.DOWN, ShelfChip.DELETE), shelfChips(shelves(), 1))
        assertEquals("第一层没有上移", listOf(ShelfChip.DOWN, ShelfChip.DELETE), shelfChips(shelvesOf(listOf(view[1], view[0])), 0))
        assertEquals("最后一个内容层没有下移", listOf(ShelfChip.UP, ShelfChip.DELETE), shelfChips(shelvesOf(listOf(view[0], view[1])), 1))
        assertEquals(
            "没授权多一颗「重新授权」,排最后",
            listOf(ShelfChip.UP, ShelfChip.DOWN, ShelfChip.DELETE, ShelfChip.REAUTHORIZE),
            shelfChips(shelves(ChannelContent.NeedsPermission), 1),
        )
    }

    /** controller 裁定(Task 13 复审):「重新授权」出现 / 消失时,别的胶囊下标不变——焦点下面那颗不会换成另一颗。 */
    @Test fun reauthorizeNeverShiftsTheOtherChips() {
        val without = shelfChips(shelves(), 1)
        val with = shelfChips(shelves(ChannelContent.NeedsPermission), 1)
        without.forEachIndexed { i, chip -> assertEquals("第 $i 颗", chip, with[i]) }
        assertEquals(without.size + 1, with.size)
        // 授权回来:焦点在「上移」(0)上的目标仍是「上移」
        assertEquals(ShelfSpot(1, CHIPS, 0), clampSpot(shelves(), ShelfSpot(1, CHIPS, 0)))
    }

    /** Task 13 复审:频道被删(Missing)、没授权时快照里没有应用名,行头仍写 PackageManager 查到的名字;都查不到(已卸载)才写包名。 */
    @Test fun channelShelfLabelFallsBackToThePackageManagerLabel() {
        val pm = mapOf("com.cibn.tv" to "CIBN酷喵")
        val missing = shelvesOf(view, mergeChannelLabels(emptyMap(), pm), mapOf(ref to ChannelContent.Missing))
        assertEquals(Shelf.ChannelShelf(1, ref, "CIBN酷喵", ChannelShelfState.Empty), missing[1])
        assertEquals("快照有名字时用快照", "酷喵", mergeChannelLabels(mapOf("com.cibn.tv" to "酷喵"), pm)["com.cibn.tv"])
        assertEquals("快照名字是包名 / 空:用 PackageManager", "CIBN酷喵", mergeChannelLabels(mapOf("com.cibn.tv" to " "), pm)["com.cibn.tv"])
        val gone = mergeChannelLabels(emptyMap(), mapOf("com.cibn.tv" to "com.cibn.tv"))
        assertEquals("已卸载(labelOf 回落包名):最后才写包名", "com.cibn.tv", (shelvesOf(view, gone)[1] as Shelf.ChannelShelf).appLabel)
    }

    @Test fun appShelfDeleteCountsAppShelvesOnly() {
        val oneApp = shelvesOf(listOf(LayoutRow("movie", listOf("a")), LayoutRow(CHANNEL_ROW_ICON, channel = ref)))
        assertFalse("只剩一个应用行,频道行不算", ShelfChip.DELETE in shelfChips(oneApp, 0))
        assertTrue(ShelfChip.DELETE in shelfChips(shelves(), 0))
        assertTrue("频道架子永远能删", ShelfChip.DELETE in shelfChips(oneApp, 1))
    }

    /** §2.2:上下键固定顺序——第 1 层胶囊 → 第 1 层卡片 → 第 2 层胶囊 → …;频道架子只有胶囊那一条。 */
    @Test fun channelShelvesOnlyHaveTheChipLane() {
        assertEquals(
            listOf(ShelfLane(0, CHIPS), ShelfLane(0, CARDS), ShelfLane(1, CHIPS), ShelfLane(2, CHIPS), ShelfLane(2, CARDS), ShelfLane(3, NEW)),
            shelfLanes(shelves()),
        )
        assertEquals(0, laneSize(shelves(), 1, CARDS))
    }

    @Test fun clampKeepsSpotsOnAChannelShelfOnItsChips() {
        // clampSpot 换条时格号不清零、照夹(edit-shelves 的写法,应用架子 NEW → CARDS 同样如此):3 颗胶囊 → 夹到 2
        assertEquals("频道架子没有卡片条:改落胶囊条", ShelfSpot(1, CHIPS, 2), clampSpot(shelves(), ShelfSpot(1, CARDS, 3)))
        assertEquals("授权回来「重新授权」没了:同一位置夹取", ShelfSpot(1, CHIPS, 2), clampSpot(shelves(), ShelfSpot(1, CHIPS, 3)))
    }

    @Test fun swapDeleteAndAppendLandings() {
        // 频道架子上移到第一层:没有「上移」了 → 落「下移」(edit-shelves 的 landingAfterSwap 原样适用)
        assertEquals(ShelfSpot(0, CHIPS, 0), landingAfterSwap(shelvesOf(listOf(view[1], view[0], view[2])), 0, ShelfChip.UP))
        assertEquals("删掉频道架子:落上一层第一颗胶囊", ShelfSpot(0, CHIPS, 0), landingAfterDelete(shelvesOf(listOf(view[0], view[2])), 1))
        val added = shelvesOf(view + LayoutRow(CHANNEL_ROW_ICON, channel = ChannelRef("p", "", "n")))
        assertEquals("加频道:新频道架子的第一颗胶囊", ShelfSpot(3, CHIPS, 0), landingAfterAppendChannel(added))
    }

    @Test fun headerCountsChannels() {
        assertEquals(EditCounts(rows = 3, apps = 1, channels = 1), editCounts(view))
    }

    @Test fun posterPreviewIsScaledToTheShelfCardHeight() {
        assertEquals(68.625f, SHELF_POSTER_HEIGHT, 0f)
        assertEquals(196f * 68.625f / 110f, shelfPosterWidthDp(PosterAspect.R16_9), 0.001f)
        assertEquals(68.625f, shelfPosterWidthDp(PosterAspect.R1_1), 0.001f)
    }
}
