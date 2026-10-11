package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** R164 spec §5:所有应用页「加到桌面… → 选一行」只列应用行,第 k 颗药丸映射回 layout 下标。 */
class AppsPageChannelTest {
    private val ref = ChannelRef("com.cibn.tv", "k", "酷喵推荐")
    private val rows = listOf(
        LayoutRow("movie", listOf("a")),
        LayoutRow(CHANNEL_ROW_ICON, channel = ref),
        LayoutRow("music", listOf("b")),
    )

    @Test fun secondLayerListsAppRowsAndMapsBackToLayoutIndices() {
        val choices = appRowIndices(rows)
        assertEquals(listOf(0, 2), choices)
        val next = addToRow(rows, choices[1], "x")   // 第 2 颗药丸 = layout 第 2 行
        assertEquals(listOf("b", "x"), next[2].apps)
        assertSame(rows[1], next[1])
    }

    @Test fun aStaleIndexPointingAtAChannelRowWritesNothing() {
        assertSame(rows, addToRow(rows, 1, "x"))
    }
}
