package com.uniteduone.launcher

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChannelCacheTest {
    private val ref = ChannelRef("p", "k", "c")
    private val chan = TvChannel(1, "p", CHANNEL_TYPE_PREVIEW, "c", "k")
    private fun prog(id: Long) = Program(id, "t$id", null, null, null, 0, null, PosterAspect.R16_9, null, 0)
    private fun snap(vararg ids: Long) = ChannelSnapshot(true, listOf(chan), mapOf(1L to ids.map { prog(it) }), mapOf("p" to "P"))

    @Test fun firstRefreshPublishes() {
        runBlocking {
            val s = ChannelStore()
            assertNull("还没读过", s.data.value)
            s.refresh { snap(1, 2) }
            assertEquals(snap(1, 2), s.data.value)
        }
    }

    /** 内容相同的刷新(onResume、TvProvider 无关的变化)不换引用 = 不通知:首页不重组、不冻结焦点。 */
    @Test fun equalContentKeepsTheSameInstance() {
        runBlocking {
            val s = ChannelStore()
            val first = snap(1, 2)
            s.refresh { first }
            s.refresh { snap(1, 2) }
            assertSame(first, s.data.value)
        }
    }

    @Test fun changedContentReplaces() {
        runBlocking {
            val s = ChannelStore()
            val first = snap(1, 2)
            s.refresh { first }
            s.refresh { snap(1) }
            assertNotSame(first, s.data.value)
            assertEquals(snap(1), s.data.value)
        }
    }

    @Test fun failedLoadKeepsThePreviousSnapshot() {
        runBlocking {
            val s = ChannelStore()
            s.refresh { snap(1) }
            s.refresh { null }
            assertEquals(snap(1), s.data.value)
        }
    }

    @Test fun contentsAreDerivedFromTheSharedSnapshot() {
        assertEquals("还没读过:谁都不画", emptyMap<ChannelRef, ChannelContent>(), channelContentsFrom(null, listOf(ref)))
        assertEquals(mapOf(ref to ChannelContent.NeedsPermission), channelContentsFrom(ChannelSnapshot(permitted = false), listOf(ref)))
        assertEquals(mapOf(ref to ChannelContent.Ready(listOf(prog(1)))), channelContentsFrom(snap(1), listOf(ref)))
        assertEquals(mapOf(ref to ChannelContent.Missing), channelContentsFrom(snap(), listOf(ref)))
        assertEquals(emptyMap<ChannelRef, ChannelContent>(), channelContentsFrom(snap(1), emptyList()))
    }
}
