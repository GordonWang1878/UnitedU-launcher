package com.uniteduone.launcher

import com.uniteduone.launcher.ShelfZone.CARDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R165 Task 10 模拟器验收发现的两处:带种子进页的初始目标、焦点卡滑入架子右端时的裁切。纯 JVM。 */
class EditShelvesTask10Test {
    private fun row(icon: String, vararg apps: String) = LayoutRow(icon, apps = apps.toList())
    private val rows = listOf(row("movie", "a", "b"), row("tv", "c"), row("music", "d", "e", "f"))

    /** 换卡片图回来:第一帧起目标就在种子那一层(整页不先画在第 1 层再滑下去)。 */
    @Test fun seededEntryStartsOnTheSeedShelf() {
        assertEquals(ShelfSpot(0, CARDS, 0), initialEditSpot(rows, null))
        assertEquals(ShelfSpot(2, CARDS, 1), initialEditSpot(rows, 2 to "e"))
        assertEquals("包名不在那一行:那一层第 1 张", ShelfSpot(2, CARDS, 0), initialEditSpot(rows, 2 to "zzz"))
        assertEquals("行号越界:夹到最后一行", ShelfSpot(2, CARDS, 0), initialEditSpot(rows, 9 to "a"))
        assertEquals("空布局", ShelfSpot(0, CARDS, 0), initialEditSpot(emptyList(), 3 to "a"))
    }

    /** 非焦点卡一律裁在架子右端;焦点卡只有整张落进架子里才不裁(还在滑入时照样裁,不画到架子外的留白里)。 */
    @Test fun focusedCardIsClippedOnlyWhileStillSlidingIn() {
        assertEquals(50f, shelfCardClipRight(50f, 122f, current = false))
        assertEquals(500f, shelfCardClipRight(500f, 122f, current = false))
        assertEquals("焦点卡还没整张进架子", 80f, shelfCardClipRight(80f, 122f, current = true))
        assertNull("焦点卡整张在架子里:不裁,柔光画出架子", shelfCardClipRight(122f, 122f, current = true))
        assertNull(shelfCardClipRight(400f, 122f, current = true))
    }
}
