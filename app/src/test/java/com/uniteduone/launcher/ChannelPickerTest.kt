package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R164 §3.1 选频道页:列哪些、按 id 重定位、页面阶段;INITIALIZE_PROGRAMS 只发一次(按包名 + versionCode)。 */
class ChannelPickerTest {
    private fun ch(id: Long, pkg: String, name: String, internal: String? = "k$id", type: String = CHANNEL_TYPE_PREVIEW) =
        TvChannel(id, pkg, type, name, internal)

    @Test fun listsPreviewChannelsNotOnTheHomeScreenSortedByAppThenName() {
        val all = listOf(
            ch(1, "com.cibn.tv", "酷喵推荐"),
            ch(2, "com.xiaodianshi.tv.yst", "热门"),
            ch(3, "com.cibn.tv", "动漫"),
            ch(4, "com.cibn.tv", "老频道", type = "TYPE_OTHER"),
            ch(5, "com.uniteduone.launcher", "自己"),
        )
        val labels = mapOf("com.cibn.tv" to "CIBN酷喵", "com.xiaodianshi.tv.yst" to "云视听小电视")
        val onLayout = listOf(ChannelRef("com.cibn.tv", "k1", "酷喵推荐"))
        val got = pickerChannels(all, onLayout, labels, "com.uniteduone.launcher")
        assertEquals(listOf(3L, 2L), got.map { it.channel.id })
        assertEquals("CIBN酷喵", got[0].appLabel)
    }

    @Test fun retargetFollowsTheSameChannelOrTheSlot() {
        assertEquals("同一个频道换了位置", 2, retargetById(listOf(10, 20, 30), listOf(5, 10, 20, 30), 1))
        assertEquals("焦点那个被删 → 补上来的下一项", 1, retargetById(listOf(10, 20, 30), listOf(10, 30), 1))
        assertEquals("删的是最后一项 → 上一项", 1, retargetById(listOf(10, 20, 30), listOf(10, 20), 2))
        assertEquals(0, retargetById(listOf(10), emptyList(), 0))
        assertEquals("第一次有列表", 0, retargetById(emptyList(), listOf(10, 20), 0))
    }

    @Test fun phases() {
        val one = listOf(ChannelCandidate(ch(1, "p", "c"), "P"))
        assertTrue(channelPickerPhase(granted = false, asked = false, candidates = null) is ChannelPickerPhase.Asking)
        assertTrue(channelPickerPhase(false, true, null) is ChannelPickerPhase.Denied)
        assertTrue(channelPickerPhase(true, true, null) is ChannelPickerPhase.Loading)
        assertTrue(channelPickerPhase(true, false, emptyList()) is ChannelPickerPhase.Empty)
        assertEquals(ChannelPickerPhase.Ready(one), channelPickerPhase(true, true, one))
    }

    @Test fun initializeProgramsIsSentOncePerPackageAndVersion() {
        val receivers = mapOf("com.cibn.tv" to 1413L, "com.netflix.ninja" to 7L, "com.uniteduone.launcher" to 9L)
        assertEquals(listOf("com.cibn.tv", "com.netflix.ninja"), pendingInit(receivers, emptyMap(), "com.uniteduone.launcher"))
        assertEquals(listOf("com.netflix.ninja"), pendingInit(receivers, mapOf("com.cibn.tv" to 1413L, "com.netflix.ninja" to 6L), "com.uniteduone.launcher"))
        assertEquals(emptyList<String>(), pendingInit(receivers, mapOf("com.cibn.tv" to 1413L, "com.netflix.ninja" to 7L), "com.uniteduone.launcher"))
    }

    @Test fun channelInitFileRoundTripsAndToleratesGarbage() {
        val m = mapOf("com.cibn.tv" to 1413L, "test.channels" to 1L)
        assertEquals(m, parseChannelInit(channelInitToJson(m)))
        assertEquals(emptyMap<String, Long>(), parseChannelInit(null))
        assertEquals(emptyMap<String, Long>(), parseChannelInit("{not json"))
        assertEquals(mapOf("a" to 2L), parseChannelInit("""{"notified":{"a":2,"b":"x"}}"""))
    }

    @Test fun onlyPackagesActuallySentAreRecorded() {
        val versions = mapOf("com.cibn.tv" to 1413L, "com.netflix.ninja" to 7L)
        assertEquals(
            "发失败的(netflix)不记,下次进页再发",
            mapOf("a" to 1L, "com.cibn.tv" to 1413L),
            recordInit(mapOf("a" to 1L), versions, listOf("com.cibn.tv")),
        )
        assertEquals(mapOf("a" to 1L), recordInit(mapOf("a" to 1L), versions, emptyList()))
    }
}
