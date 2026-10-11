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

    /** R169:没有 Google Play 服务时「新的一行」只有应用行一格;已有的频道架子照常在,目标夹回唯一那一格。 */
    @Test fun noChannelCardWithoutPlayServices() {
        assertEquals(listOf(NewRowChoice.APP_ROW), newRowChoices(channelsOffered = false))
        assertEquals(NewRowChoice.entries, newRowChoices(channelsOffered = true))
        val s = shelvesOf(listOf(app(), ch(1)), channelsOffered = false)
        assertEquals(Shelf.NewRowShelf(listOf(NewRowChoice.APP_ROW)), s.last())
        assertEquals("已加的频道行照常显示", true, s[1] is Shelf.ChannelShelf)
        assertEquals(1, laneSize(s, 2, ShelfZone.NEW))
        assertEquals(ShelfSpot(2, ShelfZone.NEW, 0), clampSpot(s, ShelfSpot(2, ShelfZone.NEW, 1)))
    }
}
