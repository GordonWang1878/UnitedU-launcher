package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** R164:每个频道行此刻的内容(spec §3.2):找频道 → 取节目 → 排序截断;没权限 / 找不到 / 空。 */
class ChannelContentsTest {
    private val ref = ChannelRef("com.cibn.tv", "k1", "酷喵推荐")
    private val other = ChannelRef("com.other", "", "热门")
    private val chan = TvChannel(10, "com.cibn.tv", CHANNEL_TYPE_PREVIEW, "酷喵推荐", "k1")
    private fun prog(id: Long, w: Int = 0) = Program(id, "t$id", null, null, null, 0, null, PosterAspect.R16_9, null, w)

    /** Review Focus 2:授权被收回(冷启动后 / 自动收回)→ 每个频道行都是「需要重新授权」,不当成「暂无内容」。 */
    @Test fun deniedMeansEveryRowNeedsPermission() {
        val got = channelContents(listOf(ref, other), { null }, { error("没权限时不该查节目") })
        assertEquals(mapOf(ref to ChannelContent.NeedsPermission, other to ChannelContent.NeedsPermission), got)
    }

    @Test fun programsDeniedMidwayAlsoNeedsPermission() {
        assertEquals(ChannelContent.NeedsPermission, channelContents(listOf(ref), { listOf(chan) }, { null })[ref])
    }

    @Test fun missingChannelOrNoProgramsIsMissing() {
        assertEquals(ChannelContent.Missing, channelContents(listOf(other), { emptyList() }, { emptyList() })[other])
        assertEquals(ChannelContent.Missing, channelContents(listOf(ref), { listOf(chan) }, { emptyList() })[ref])
    }

    @Test fun readyIsSortedAndCapped() {
        val got = channelContents(listOf(ref), { listOf(chan) }, { id -> assertEquals(10L, id); (1L..15L).map { prog(it, w = it.toInt()) } })[ref]
        assertEquals((15L downTo 4L).toList(), (got as ChannelContent.Ready).programs.map { it.id })
    }

    @Test fun eachPackageIsQueriedOnce() {
        var calls = 0
        val twoOfSamePkg = listOf(ref, ChannelRef("com.cibn.tv", "k2", "第二个"))
        channelContents(twoOfSamePkg, { calls++; listOf(chan) }, { emptyList() })
        assertEquals(1, calls)
    }
}
