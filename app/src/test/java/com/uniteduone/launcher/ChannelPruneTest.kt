package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** R164 spec §3.3:发布频道的应用被真正卸载(FULLY_REMOVED / 启动缺包清理)→ 它的频道行从 layout.json 删掉。 */
class ChannelPruneTest {
    private val kumiao = ChannelRef("com.cibn.tv", "k", "酷喵推荐")
    private val bili = ChannelRef("com.xiaodianshi.tv.yst", "", "热门")
    private val rows = listOf(
        LayoutRow("movie", listOf("com.cibn.tv", "com.a")),
        LayoutRow(CHANNEL_ROW_ICON, channel = kumiao),
        LayoutRow(CHANNEL_ROW_ICON, channel = bili),
    )

    @Test fun uninstallRemovesTheAppCardAndItsChannelRow() {
        val next = Layout.withoutPackage(rows, "com.cibn.tv")
        assertEquals(listOf(LayoutRow("movie", listOf("com.a")), LayoutRow(CHANNEL_ROW_ICON, channel = bili)), next)
    }

    @Test fun channelOnlyPackageIsRemovedToo() {
        val next = Layout.withoutPackage(rows, "com.xiaodianshi.tv.yst")
        assertEquals(2, next.size)
        assertEquals(kumiao, next[1].channel)
    }

    @Test fun untouchedWhenNothingMatches() {
        assertSame(rows, Layout.withoutPackage(rows, "com.zzz"))
        assertSame(rows, withoutPackages(rows, setOf("com.zzz")))
    }

    @Test fun startupPruneSeesChannelPackages() {
        assertEquals(listOf("com.cibn.tv", "com.a", "com.cibn.tv", "com.xiaodianshi.tv.yst"), layoutPackages(rows))
        val next = withoutPackages(rows, setOf("com.xiaodianshi.tv.yst"))
        assertEquals(listOf(null, kumiao), next.map { it.channel })
    }

    /** 编辑页整份写回前的合并(落盘排查 2026-09-23 同一个洞):别人刚清掉的频道行不能被编辑页的旧快照写回去。 */
    @Test fun editSnapshotDropsChannelRowsRemovedElsewhere() {
        val disk = listOf(LayoutRow("movie", listOf("com.a")), LayoutRow(CHANNEL_ROW_ICON, channel = kumiao))
        val known = knownAfterWrite(emptySet(), rows)
        val merged = dropRemovedElsewhere(rows, disk, known) { it != "com.xiaodianshi.tv.yst" }
        assertEquals(listOf(null, kumiao), merged.map { it.channel })
        assertEquals(listOf("com.cibn.tv", "com.a"), merged[0].apps)
    }

    @Test fun channelAddedInThisEditSessionIsKept() {
        val disk = listOf(LayoutRow("movie", listOf("com.a")))
        val snapshot = disk + LayoutRow(CHANNEL_ROW_ICON, channel = bili)
        assertSame(snapshot, dropRemovedElsewhere(snapshot, disk, knownAfterWrite(emptySet(), disk)) { false })
    }
}
