package com.uniteduone.launcher

import com.uniteduone.launcher.ShelfChip.ADD_APP
import com.uniteduone.launcher.ShelfChip.DELETE
import com.uniteduone.launcher.ShelfChip.DOWN
import com.uniteduone.launcher.ShelfChip.ICON
import com.uniteduone.launcher.ShelfChip.UP
import com.uniteduone.launcher.ShelfZone.CARDS
import com.uniteduone.launcher.ShelfZone.CHIPS
import com.uniteduone.launcher.ShelfZone.NEW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R165 编辑桌面「货架」:货架模型、上下键的固定焦点顺序(spec §2.2)、层内胶囊 ↔ 卡片的最近映射、目标格的夹取。
 * 纯 JVM。
 */
class EditShelvesTest {
    private fun row(icon: String, vararg apps: String) = LayoutRow(icon, apps = apps.toList())

    /** 0:4 张  1:2 张  2:空行  3:新的一行 */
    private val view3 = listOf(row("movie", "a", "b", "c", "d"), row("tv", "e", "f"), row("music"))
    private val three = shelvesOf(view3)
    private val none: (ShelfSpot) -> Float? = { null }

    @Test fun shelvesKeepLayoutRowIndicesAndEndWithTheNewRow() {
        assertEquals(4, three.size)
        assertEquals(Shelf.AppShelf(1, "tv", listOf("e", "f")), three[1])
        assertEquals(Shelf.NewRowShelf, three.last())
        assertEquals(3, appShelfCount(three))
        assertEquals(listOf<Shelf>(Shelf.NewRowShelf), shelvesOf(emptyList()))
    }

    @Test fun chipsDependOnPositionAndHowManyAppRowsThereAre() {
        assertEquals(listOf(ADD_APP, ICON, DOWN, DELETE), shelfChips(three, 0))        // 第一层:没有上移
        assertEquals(listOf(ADD_APP, ICON, UP, DOWN, DELETE), shelfChips(three, 1))
        assertEquals(listOf(ADD_APP, ICON, UP, DELETE), shelfChips(three, 2))          // 最后一个内容层:没有下移
        assertEquals(emptyList<ShelfChip>(), shelfChips(three, 3))                     // 新的一行没有胶囊
        assertEquals(emptyList<ShelfChip>(), shelfChips(three, 9))
        // 只剩一个应用行:不能上下移,也不能删
        assertEquals(listOf(ADD_APP, ICON), shelfChips(shelvesOf(listOf(row("movie", "a"))), 0))
    }

    @Test fun lanesAreChipsThenCardsPerShelfAndTheNewRowLast() {
        assertEquals(
            listOf(
                ShelfLane(0, CHIPS), ShelfLane(0, CARDS), ShelfLane(1, CHIPS), ShelfLane(1, CARDS),
                ShelfLane(2, CHIPS), ShelfLane(2, CARDS), ShelfLane(3, NEW),
            ),
            shelfLanes(three),
        )
        assertEquals(4, laneSize(three, 0, CARDS))
        assertEquals("空架子只有一格:「添加应用」方块", 1, laneSize(three, 2, CARDS))
        assertEquals(NewRowChoice.entries.size, laneSize(three, 3, NEW))
        assertEquals(0, laneSize(three, 3, CARDS))
        assertEquals(0, laneSize(three, 0, NEW))
        assertEquals(0, laneSize(three, 7, CHIPS))
    }

    @Test fun downWalksTheFixedOrderAndStopsAtTheNewRow() {
        val seen = mutableListOf(ShelfSpot(0, CHIPS, 0))
        while (true) seen += verticalStep(three, seen.last(), down = true, centerOf = none) ?: break
        assertEquals(shelfLanes(three).map { ShelfSpot(it.shelf, it.zone, 0) }, seen)
        assertNull("新的一行往下:到头", verticalStep(three, ShelfSpot(3, NEW, 0), down = true, centerOf = none))
    }

    @Test fun upWalksBackAndStopsAtTheFirstChips() {
        val seen = mutableListOf(ShelfSpot(3, NEW, 0))
        while (true) seen += verticalStep(three, seen.last(), down = false, centerOf = none) ?: break
        assertEquals(shelfLanes(three).reversed().map { ShelfSpot(it.shelf, it.zone, 0) }, seen)
        assertNull("第一层胶囊往上:到头", verticalStep(three, ShelfSpot(0, CHIPS, 3), down = false, centerOf = none))
        assertNull("不在任何 lane 上(越界)", verticalStep(three, ShelfSpot(8, CARDS, 0), down = true, centerOf = none))
    }

    @Test fun cardAndChipMapToTheNearestByHorizontalCentre() {
        val six = shelvesOf(listOf(row("movie", "a", "b", "c", "d", "e", "f"), row("tv", "g")))
        // 第一层胶囊靠右(添加应用 / 换图标 / 下移 / 删除),卡片靠左、最后一张已经滑到右边
        val chipX = listOf(1230f, 1420f, 1585f, 1735f)
        val cardX = listOf(238f, 522f, 806f, 1090f, 1374f, 1700f)
        val centre: (ShelfSpot) -> Float? = { s ->
            when (s.zone) {
                CHIPS -> chipX.getOrNull(s.index)
                CARDS -> if (s.shelf == 0) cardX.getOrNull(s.index) else 300f
                NEW -> null
            }
        }
        assertEquals(ShelfSpot(0, CHIPS, 3), verticalStep(six, ShelfSpot(0, CARDS, 5), down = false, centerOf = centre))
        assertEquals(ShelfSpot(0, CHIPS, 0), verticalStep(six, ShelfSpot(0, CARDS, 0), down = false, centerOf = centre))
        assertEquals(ShelfSpot(0, CARDS, 4), verticalStep(six, ShelfSpot(0, CHIPS, 1), down = true, centerOf = centre))
        // 跨层也按最近:第 1 层最右那张卡往下 → 第 2 层胶囊里最靠右的那颗
        assertEquals(ShelfSpot(1, CHIPS, 3), verticalStep(six, ShelfSpot(0, CARDS, 5), down = true, centerOf = centre))
    }

    @Test fun nearestFallsBackToTheFirstWhenNothingIsMeasured() {
        assertEquals(0, nearestByCenter(null, listOf(1f, 2f)))
        assertEquals(0, nearestByCenter(500f, listOf(null, null)))
        assertEquals(0, nearestByCenter(500f, emptyList()))
        assertEquals("一样近取左边那个", 0, nearestByCenter(10f, listOf(0f, 20f)))
        assertEquals(1, nearestByCenter(500f, listOf(null, 480f, 900f)))
    }

    /** Review Focus 3:焦点停在「新的一行」时行数变了,目标仍在新的一行上。 */
    @Test fun clampKeepsTheNewRowOnTheNewRowWhenRowsChange() {
        val four = shelvesOf(view3 + row("games"))
        assertEquals("多了一行:新的一行往后挪一格", ShelfSpot(4, NEW, 0), clampSpot(four, ShelfSpot(3, NEW, 0)))
        val two = shelvesOf(view3.take(2))
        assertEquals("少了一行:新的一行往前挪一格", ShelfSpot(2, NEW, 0), clampSpot(two, ShelfSpot(3, NEW, 0)))
        assertEquals(ShelfSpot(3, NEW, NewRowChoice.entries.lastIndex), clampSpot(three, ShelfSpot(3, NEW, 5)))
    }

    @Test fun clampPullsStaleSpotsBackInsideTheShelves() {
        assertEquals(ShelfSpot(0, CARDS, 3), clampSpot(three, ShelfSpot(0, CARDS, 9)))
        assertEquals("空架子:只剩方块", ShelfSpot(2, CARDS, 0), clampSpot(three, ShelfSpot(2, CARDS, 2)))
        assertEquals("胶囊少了(删除没了):落最后一颗", ShelfSpot(0, CHIPS, 3), clampSpot(three, ShelfSpot(0, CHIPS, 4)))
        assertEquals(ShelfSpot(0, CARDS, 0), clampSpot(three, ShelfSpot(-1, CARDS, -2)))
        assertEquals("越过最后一层:落到新的一行", ShelfSpot(3, NEW, 0), clampSpot(three, ShelfSpot(5, CARDS, 0)))
        assertEquals("应用架子上不会有 NEW", ShelfSpot(3, NEW, 0), clampSpot(three, ShelfSpot(1, NEW, 0)))
    }

    @Test fun appRowChoiceIsFullAtFiveAppRows() {
        assertFalse(choiceFull(three, NewRowChoice.APP_ROW))
        val five = shelvesOf(view3 + row("games") + row("kids"))
        assertTrue(choiceFull(five, NewRowChoice.APP_ROW))
    }

    @Test fun countsAreRowsAndVisibleApps() {
        assertEquals(EditCounts(rows = 3, apps = 6), editCounts(view3))
        assertEquals(EditCounts(rows = 0, apps = 0), editCounts(emptyList()))
    }
}
