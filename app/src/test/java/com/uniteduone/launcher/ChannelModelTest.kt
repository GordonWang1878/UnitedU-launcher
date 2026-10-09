package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R164:TvProvider 行 → 模型、频道匹配(spec §3.3 标识)、节目排序截断、元数据文案。全部纯 JVM,行用 Map 模拟游标。 */
class ChannelModelTest {
    private fun chRow(id: Long, pkg: String = "com.cibn.tv", type: String = CHANNEL_TYPE_PREVIEW, name: String = "酷喵推荐", internal: String? = "k1") =
        mapOf<String, Any?>(TvCols.ID to id, TvCols.PACKAGE to pkg, TvCols.TYPE to type, TvCols.DISPLAY_NAME to name, TvCols.INTERNAL_ID to internal)

    private fun prog(id: Long, weight: Int = 0, title: String = "t$id") =
        Program(id, title, null, null, null, 0L, null, PosterAspect.R16_9, null, weight)

    @Test fun channelRowsParse() {
        assertEquals(TvChannel(7, "com.cibn.tv", CHANNEL_TYPE_PREVIEW, "酷喵推荐", "k1"), channelFromRow(chRow(7)))
        assertNull("没有 _id 的行丢掉", channelFromRow(chRow(7) - TvCols.ID))
        assertNull("没有包名的行丢掉", channelFromRow(chRow(7, pkg = " ")))
        assertNull("空的 internal_provider_id 当没写", channelFromRow(chRow(7, internal = "  "))!!.internalId)
    }

    @Test fun programRowsParseWithThumbnailFallback() {
        val p = programFromRow(
            mapOf(
                TvCols.ID to 3L, TvCols.TITLE to " 海底 ", TvCols.SEASON to "2", TvCols.EPISODE to 5L,
                TvCols.DURATION to 6_300_000L, TvCols.THUMB to "content://x/1", TvCols.THUMB_ASPECT to 4L,
                TvCols.INTENT to "intent:#Intent;end", TvCols.WEIGHT to 9L,
            ),
        )!!
        assertEquals("海底", p.title)
        assertEquals("2", p.season)
        assertEquals("5", p.episode)
        assertEquals("content://x/1", p.posterUri)
        assertEquals(PosterAspect.R2_3, p.aspect)
        assertEquals(9, p.weight)
        assertNull(programFromRow(mapOf(TvCols.TITLE to "no id")))
    }

    @Test fun posterArtWinsOverThumbnailTogetherWithItsOwnAspect() {
        val p = programFromRow(
            mapOf(TvCols.ID to 1L, TvCols.POSTER to "https://a/p.jpg", TvCols.POSTER_ASPECT to 3L, TvCols.THUMB to "content://t", TvCols.THUMB_ASPECT to 0L),
        )!!
        assertEquals("https://a/p.jpg", p.posterUri)
        assertEquals(PosterAspect.R1_1, p.aspect)
    }

    @Test fun aspectCodesAndWidths() {
        assertEquals(
            listOf(PosterAspect.R16_9, PosterAspect.R3_2, PosterAspect.R4_3, PosterAspect.R1_1, PosterAspect.R2_3, PosterAspect.R2_3, PosterAspect.R3_4, PosterAspect.R16_9, PosterAspect.R16_9),
            listOf(0L, 1L, 2L, 3L, 4L, 5L, 6L, 99L, null).map { posterAspectOf(it) },
        )
        // spec §4:110 dp 高时的卡宽
        assertEquals(listOf(196f, 165f, 147f, 110f, 73f, 83f), PosterAspect.values().map { it.widthDp })
    }

    @Test fun matchesByInternalProviderId() {
        val chans = listOf(channelFromRow(chRow(1, internal = "other"))!!, channelFromRow(chRow(2))!!)
        assertEquals(2L, matchChannel(ChannelRef("com.cibn.tv", "k1", "旧名字"), chans)!!.id)
    }

    /** Review Focus 1:重装 / 更新后应用重建了频道,_id 变了,key 不变 → 照样找回。 */
    @Test fun reinstalledChannelWithNewIdStillMatchesByKey() {
        val ref = refFor(channelFromRow(chRow(4))!!)
        val afterReinstall = listOf(channelFromRow(chRow(31))!!)
        assertEquals(31L, matchChannel(ref, afterReinstall)!!.id)
    }

    @Test fun withoutKeyMatchesByNameOnly() {
        val chans = listOf(channelFromRow(chRow(5, internal = null, name = "热播"))!!)
        assertEquals(5L, matchChannel(ChannelRef("com.cibn.tv", "", "热播"), chans)!!.id)
        assertNull(matchChannel(ChannelRef("com.cibn.tv", "", "别的"), chans))
    }

    @Test fun keyMismatchDoesNotFallBackToAnotherChannel() {
        val chans = listOf(channelFromRow(chRow(5, internal = "new-key", name = "酷喵推荐"))!!)
        assertNull("key 对不上就是「暂无内容」,不按名字换成别的频道", matchChannel(ChannelRef("com.cibn.tv", "k1", "酷喵推荐"), chans))
    }

    @Test fun onlyPreviewChannelsOfThatPackageMatch() {
        val chans = listOf(
            channelFromRow(chRow(1, type = "TYPE_OTHER"))!!,
            channelFromRow(chRow(2, pkg = "com.other"))!!,
        )
        assertNull(matchChannel(ChannelRef("com.cibn.tv", "k1", "酷喵推荐"), chans))
    }

    @Test fun refForUsesInternalIdOrEmpty() {
        assertEquals(ChannelRef("com.cibn.tv", "k1", "酷喵推荐"), refFor(channelFromRow(chRow(1))!!))
        assertEquals("", refFor(channelFromRow(chRow(1, internal = null))!!).key)
    }

    @Test fun programsSortByWeightThenInsertionAndCapAtTwelve() {
        val ps = (1L..20L).map { prog(it, weight = if (it % 2 == 0L) 5 else 1) }.shuffled(java.util.Random(7))
        val top = topPrograms(ps)
        assertEquals(MAX_PROGRAMS, top.size)
        assertEquals(listOf(2L, 4L, 6L, 8L, 10L, 12L, 14L, 16L, 18L, 20L, 1L, 3L), top.map { it.id })
    }

    @Test fun subtitles() {
        val f = MetaFormats("第 %1\$s 季 · 第 %2\$s 集", "第 %1\$s 集", "%1\$d 小时 %2\$d 分钟", "%1\$d 分钟")
        assertEquals("第 2 季 · 第 5 集", programSubtitle(prog(1).copy(season = "2", episode = "5"), f))
        assertEquals("第 5 集", programSubtitle(prog(1).copy(episode = "5"), f))
        assertEquals("1 小时 45 分钟", programSubtitle(prog(1).copy(durationMs = 6_300_000), f))
        assertEquals("42 分钟", programSubtitle(prog(1).copy(durationMs = 42 * 60_000L + 59_000), f))
        assertNull("不到一分钟、没有季集 → 第二行留空", programSubtitle(prog(1).copy(durationMs = 30_000), f))
    }
}
