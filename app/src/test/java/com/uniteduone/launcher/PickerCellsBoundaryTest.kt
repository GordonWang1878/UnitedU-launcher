package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片网格格子换算的边界(PickerCellsTest 之外):builtinCount = 0、「我的」为空、首格 / 末格、越界格子、
 * 夹紧的极值,以及每一格恰好属于「内置 / ＋ / 我的」三者之一。
 */
class PickerCellsBoundaryTest {

    /** 每一格按换算函数归类,必须与 [pickerCells] 摆出来的那一格一致:内置 → "B<i>",＋ → "+",我的 → "M<i>"。 */
    @Test fun everyCellBelongsToExactlyOneBlockAndMatchesTheGrid() {
        for (b in 0..5) {
            for (n in 0..5) {
                val grid = pickerCells((0 until b).map { "B$it" }, "+", (0 until n).map { "M$it" })
                assertEquals(b + 1 + n, grid.size)
                for (cell in grid.indices) {
                    val builtin = builtinOfCell(cell, b)
                    val image = imageOfCell(cell, b)
                    val isAdd = cell == addCell(b)
                    val kinds = listOf(builtin != null, image != null, isAdd).count { it }
                    assertEquals("b=$b n=$n cell=$cell", 1, kinds)
                    val label = when {
                        builtin != null -> "B$builtin"
                        image != null -> "M$image"
                        else -> "+"
                    }
                    assertEquals("b=$b n=$n cell=$cell", grid[cell], label)
                }
            }
        }
    }

    @Test fun imageRoundTripIsExactInBothDirections() {
        for (b in 0..6) {
            for (i in 0..30) assertEquals(i, imageOfCell(cellOfImage(i, b), b))
            for (cell in (b + 1)..(b + 31)) assertEquals(cell, cellOfImage(imageOfCell(cell, b)!!, b))
        }
    }

    @Test fun noBuiltinsMeansPlusIsCellZeroAndNothingIsBuiltin() {
        assertEquals(0, addCell(0))
        assertEquals(1, cellOfImage(0, 0))
        assertNull(imageOfCell(0, 0))
        assertEquals(0, imageOfCell(1, 0))
        for (cell in -1..3) assertNull(builtinOfCell(cell, 0))
    }

    @Test fun negativeCellsAreNeitherImagesNorBuiltins() {
        for (b in 0..3) {
            for (cell in listOf(-1, -2, -b - 1, -100)) {
                assertNull("b=$b cell=$cell", imageOfCell(cell, b))
                assertNull("b=$b cell=$cell", builtinOfCell(cell, b))
            }
        }
    }

    @Test fun aNegativeBuiltinCountHasNoBuiltinCells() {
        for (cell in -2..2) assertNull(builtinOfCell(cell, -1))
    }

    @Test fun cellsPastTheEndStillMapToImageIndicesTheCallerMustClamp() {
        // imageOfCell 不知道「我的」有几张:越过末格的格子照样算出下标,调用方先 clampCell 再换算
        assertEquals(5, imageOfCell(9, 3))
        assertEquals(clampCell(9, cellCount = 3 + 1 + 2), cellOfImage(1, 3))
    }

    // ---- 夹紧 ----

    @Test fun clampHandlesExtremesAndEmptyGrids() {
        assertEquals(0, clampCell(-1, 5))
        assertEquals(0, clampCell(Int.MIN_VALUE, 5))
        assertEquals(4, clampCell(Int.MAX_VALUE, 5))
        assertEquals(4, clampCell(4, 5))
        assertEquals(0, clampCell(0, 1))
        assertEquals(0, clampCell(3, 0))
        assertEquals(0, clampCell(3, -7))
        assertEquals(0, clampCell(-3, 0))
    }

    @Test fun clampedCellIsAlwaysInsideTheGrid() {
        for (count in 1..6) {
            for (cell in -3..9) assertTrue("cell=$cell count=$count", clampCell(cell, count) in 0 until count)
        }
    }

    @Test fun deletingTheOnlyImageWithBuiltinsLandsOnPlusNotInTheBuiltinBlock() {
        for (b in 1..4) {
            // 内置 b + 「＋」+ 1 张;焦点在那张上(末格),删掉后剩 b + 1 格
            assertEquals(addCell(b), clampCell(cellOfImage(0, b), cellCount = b + 1))
        }
    }

    // ---- 扫码页回来的落点 ----

    @Test fun landingIgnoresUploadsThatMatchNoFileAndNullEntries() {
        // 「恢复原图」那一项是 null,不会被任何上传名匹配上
        assertEquals(addCell(2), landingCell(listOf(null, null), listOf("a.jpg"), builtinCount = 2))
        assertEquals(addCell(0), landingCell(emptyList(), emptyList()))
    }

    @Test fun landingOnTheLastImageAndOnADuplicateName() {
        val names = listOf("a.jpg", "b.jpg", "c.jpg")
        assertEquals(cellOfImage(2, 3), landingCell(names, listOf("c.jpg"), builtinCount = 3))
        // 同名出现两次(不该发生):落第一次出现的那格
        assertEquals(cellOfImage(0, 0), landingCell(listOf("a.jpg", "a.jpg"), listOf("a.jpg")))
        // 同一个名字传了两次:与传一次相同
        assertEquals(cellOfImage(1, 0), landingCell(names, listOf("b.jpg", "b.jpg")))
    }

    @Test fun landingNeverPointsIntoTheBuiltinBlock() {
        for (b in 0..4) {
            for (uploaded in listOf(emptyList<String>(), listOf("x.jpg"), listOf("a.jpg"))) {
                val cell = landingCell(listOf("a.jpg"), uploaded, builtinCount = b)
                assertNull("b=$b uploaded=$uploaded", builtinOfCell(cell, b))
                assertTrue(cell >= addCell(b))
            }
        }
    }

    // ---- 行 ----

    @Test fun nonPositiveColumnsActAsOneColumn() {
        for (cols in listOf(0, -1, Int.MIN_VALUE)) {
            assertEquals(
                listOf(PickerLine.Cells(PickerSection.MINE, 0, 1), PickerLine.Cells(PickerSection.MINE, 1, 1)),
                pickerLines(builtinCount = 0, mineCells = 2, columns = cols),
            )
        }
    }

    @Test fun builtinBlockThatExactlyFillsItsRows() {
        val lines = pickerLines(builtinCount = 6, mineCells = 1, columns = 3)
        assertEquals(
            listOf(
                PickerLine.Title(PickerSection.BUILTIN),
                PickerLine.Cells(PickerSection.BUILTIN, 0, 3),
                PickerLine.Cells(PickerSection.BUILTIN, 3, 3),
                PickerLine.Title(PickerSection.MINE),
                PickerLine.Cells(PickerSection.MINE, 6, 1),
            ),
            lines,
        )
        assertEquals(4, lineOfCell(lines, addCell(6)))
    }

    @Test fun everyCellIsInExactlyOneLineAndTitlesHoldNoCells() {
        for (b in 0..7) {
            for (mine in 1..7) {
                for (cols in 1..4) {
                    val lines = pickerLines(b, mine, cols)
                    val cells = lines.filterIsInstance<PickerLine.Cells>().flatMap { it.first until it.first + it.count }
                    assertEquals("b=$b mine=$mine cols=$cols", (0 until b + mine).toList(), cells)
                    for (cell in 0 until b + mine) {
                        val line = lines[lineOfCell(lines, cell)] as PickerLine.Cells
                        assertEquals(if (cell < b) PickerSection.BUILTIN else PickerSection.MINE, line.section)
                        assertTrue(line.count in 1..cols)
                    }
                }
            }
        }
    }

    @Test fun cellsOutsideTheGridAreInNoLine() {
        val lines = pickerLines(builtinCount = 2, mineCells = 2, columns = 3)
        assertEquals(-1, lineOfCell(lines, -1))
        assertEquals(-1, lineOfCell(lines, 4))
        assertEquals(-1, lineOfCell(emptyList(), 0))
    }
}
