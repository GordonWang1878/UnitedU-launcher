package com.uniteduone.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** R164:layout.json 里的频道行(spec §3.3)。用真 org.json(testImplementation),走的就是 Layout.read / write 的 parse / toJson。 */
class ChannelLayoutTest {
    private val kumiao = ChannelRef("com.cibn.tv", "kumiao_android_tv_provider|2019.1125.1657", "酷喵推荐")

    @Test fun channelRowRoundTrips() {
        val rows = listOf(
            LayoutRow("movie", listOf("com.a", "com.b")),
            LayoutRow("tv", channel = kumiao),
            LayoutRow("music", emptyList()),
        )
        assertEquals(rows, Layout.parse(Layout.toJson(rows)))
    }

    @Test fun channelRowIsWrittenExactlyAsSpecified() {
        val json = JSONObject(Layout.toJson(listOf(LayoutRow("movie", listOf("com.a")), LayoutRow("tv", channel = kumiao))))
        val row = json.getJSONArray("rows").getJSONObject(1)
        assertEquals("tv", row.getString("icon"))
        assertEquals(0, row.getJSONArray("apps").length())
        val ch = row.getJSONObject("channel")
        assertEquals("com.cibn.tv", ch.getString("pkg"))
        assertEquals("kumiao_android_tv_provider|2019.1125.1657", ch.getString("key"))
        assertEquals("酷喵推荐", ch.getString("name"))
        assertFalse("应用行不写 channel", json.getJSONArray("rows").getJSONObject(0).has("channel"))
    }

    @Test fun filesWithoutChannelFieldReadAsAppRows() {
        val read = Layout.parse("""{"rows":[{"icon":"movie","apps":["com.a"]},{"icon":"tv","apps":[]}]}""")
        assertTrue(read.none { it.isChannel })
        assertEquals(listOf(listOf("com.a"), emptyList()), read.map { it.apps })
    }

    /** Review Focus 5:旧版本(不认 channel)重写后,频道行成了空应用行;应用行可以超过 5 行,读回不当损坏。 */
    @Test fun oldVersionRewriteBecomesPlainEmptyAppRows() {
        val rewritten = """{"rows":[
            {"icon":"movie","apps":["a"]},{"icon":"tv","apps":["b"]},{"icon":"music","apps":["c"]},
            {"icon":"apps","apps":["d"]},{"icon":"apps","apps":["e"]},{"icon":"tv","apps":[]},{"icon":"tv","apps":[]}
        ]}"""
        val read = Layout.parse(rewritten)
        assertEquals(7, read.size)
        assertTrue(read.none { it.isChannel })
    }

    @Test fun allChannelRowsIsCorrupt() {
        val text = Layout.toJson(listOf(LayoutRow("tv", channel = kumiao)))
        assertThrows(IllegalStateException::class.java) { Layout.parse(text) }
    }

    @Test fun zeroRowsIsStillCorrupt() {
        assertThrows(IllegalStateException::class.java) { Layout.parse("""{"rows":[]}""") }
    }

    @Test fun incompleteChannelFieldFallsBackToAnAppRow() {
        val read = Layout.parse(
            """{"rows":[{"icon":"movie","apps":["a"]},
               {"icon":"tv","apps":[],"channel":{"pkg":"","key":"k","name":"x"}},
               {"icon":"tv","apps":[],"channel":{"pkg":"p"}},
               {"icon":"tv","apps":[],"channel":"oops"}]}""",
        )
        assertEquals(listOf(false, false, false, false), read.map { it.isChannel })
    }

    @Test fun missingKeyDefaultsToEmptyAndAppsOfAChannelRowAreIgnored() {
        val read = Layout.parse(
            """{"rows":[{"icon":"movie","apps":["a"]},{"icon":"tv","apps":["stray"],"channel":{"pkg":"p","name":" 推荐 "}}]}""",
        )
        assertEquals(ChannelRef("p", "", "推荐"), read[1].channel)
        assertEquals(emptyList<String>(), read[1].apps)
    }

    @Test fun channelRefFromDiskTrimsAndRejectsBlanks() {
        assertEquals(ChannelRef("p", "k", "n"), channelRefFromDisk(" p ", " k ", " n "))
        assertNull(channelRefFromDisk("p", "k", "  "))
        assertNull(channelRefFromDisk(null, "k", "n"))
    }
}
