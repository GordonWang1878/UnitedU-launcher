package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** R165 §2.4:「新的一行」两张选择卡;某类满 5 行那张变暗(仍可聚焦,确定不响应),两类分开数。 */
class NewRowChoiceTest {
    private fun app() = LayoutRow("movie", listOf("a"))
    private fun ch(n: Int) = LayoutRow(CHANNEL_ROW_ICON, channel = ChannelRef("p$n", "k", "c"))

    @Test fun channelIsTheSecondCard() {
        assertEquals(listOf(NewRowChoice.APP_ROW, NewRowChoice.CHANNEL), NewRowChoice.entries.toList())
        assertEquals("「新的一行」那一条有两格", 2, laneSize(shelvesOf(listOf(app())), 1, ShelfZone.NEW))
    }

    @Test fun bothOpenByDefault() {
        val s = shelvesOf(listOf(app()))
        assertFalse(choiceFull(s, NewRowChoice.APP_ROW))
        assertFalse(choiceFull(s, NewRowChoice.CHANNEL))
    }

    @Test fun eachKindFillsSeparately() {
        val fullChannels = shelvesOf(listOf(app()) + List(MAX_CHANNEL_ROWS) { ch(it) })
        assertEquals(listOf(false, true), NewRowChoice.entries.map { choiceFull(fullChannels, it) })
        val fullApps = shelvesOf(List(MAX_ROWS) { app() } + ch(1))
        assertEquals(listOf(true, false), NewRowChoice.entries.map { choiceFull(fullApps, it) })
        assertEquals(listOf(MAX_ROWS, MAX_CHANNEL_ROWS), NewRowChoice.entries.map { choiceMax(it) })
    }
}
