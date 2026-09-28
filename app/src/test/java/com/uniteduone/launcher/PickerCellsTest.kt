package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 图片网格的格子换算(Ruling R63;R115 起前面多了「内置」块):格子 = [内置 B 张] + [「＋」] + [我的图片]。
 * 焦点记忆(focusedIdx)、删图后的夹紧、扫码页回来的落点都按格子下标算,错一位就会落到隔壁那张图上。
 * B = 0(内置清单为空)时与 R63 逐格相同。
 */
class PickerCellsTest {
    @Test fun plusIsCellZeroAndImagesFollowWhenThereAreNoBuiltins() {
        assertEquals(listOf("+", "a", "b"), pickerCells(emptyList(), "+", listOf("a", "b")))
        assertEquals(listOf("+"), pickerCells(emptyList(), "+", emptyList()))
        assertEquals(0, addCell())
    }

    @Test fun builtinsComeFirstThenPlusThenMine() {
        assertEquals(listOf("B1", "B2", "B3", "+", "a"), pickerCells(listOf("B1", "B2", "B3"), "+", listOf("a")))
        assertEquals(3, addCell(builtinCount = 3))
        assertEquals(4, cellOfImage(0, builtinCount = 3))
        assertEquals(0, builtinOfCell(0, builtinCount = 3))
        assertEquals(2, builtinOfCell(2, builtinCount = 3))
        assertNull(builtinOfCell(3, builtinCount = 3))   // 「＋」不是内置图
        assertNull(builtinOfCell(4, builtinCount = 3))
    }

    @Test fun cellAndImageIndexRoundTrip() {
        for (b in listOf(0, 1, 4)) {
            for (i in 0..20) assertEquals(i, imageOfCell(cellOfImage(i, b), b))
            assertNull(imageOfCell(addCell(b), b))   // 「＋」不是图片:不预览、不删
            for (c in 0 until b) assertNull(imageOfCell(c, b))   // 内置格不是「我的」图片:删不掉
        }
        assertEquals(1, cellOfImage(0))
    }

    @Test fun deletingTheLastImageClampsToThePreviousOne() {
        // 3 张图 = 4 格,焦点在末张(格 3);删掉它剩 3 格 → 夹到格 2 = 新的末张
        assertEquals(2, clampCell(3, cellCount = 3))
        // 有 2 张内置图:内置 2 + 「＋」+ 3 张 = 6 格,焦点在末张(格 5),删掉剩 5 格 → 格 4
        assertEquals(4, clampCell(5, cellCount = 5))
    }

    @Test fun deletingAMiddleImageKeepsTheSameCell() {
        // 焦点在格 2(第 2 张图),删掉它后同一格由下一张补上,格子下标不变
        assertEquals(2, clampCell(2, cellCount = 3))
    }

    @Test fun deletingEverythingLandsOnPlus() {
        assertEquals(addCell(), clampCell(1, cellCount = 1))
        assertEquals(addCell(), clampCell(5, cellCount = 0))   // 防御:还没有格子时也不越界
        // 有内置图时删空「我的」:剩内置 2 + 「＋」= 3 格,夹到「＋」(格 2),不会跳进内置块
        assertEquals(addCell(2), clampCell(3, cellCount = 3))
    }

    @Test fun landingIsTheFirstUploadStillInTheGrid() {
        val names = listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg")
        // 按上传先后取第一张:传了 c 再传 a,落 c(图片 2 → 格 3),不是网格里排在前面的 a
        assertEquals(3, landingCell(names, listOf("c.jpg", "a.jpg")))
        // 第一张在手机上又删了 → 顺延到下一张还在的
        assertEquals(1, landingCell(names, listOf("gone.jpg", "a.jpg")))
        // 前面有 4 张内置图:同一张 c 落在格 4 + 1 + 2 = 7
        assertEquals(7, landingCell(names, listOf("c.jpg"), builtinCount = 4))
    }

    @Test fun noUploadLandsOnPlus() {
        assertEquals(addCell(), landingCell(listOf("a.jpg"), emptyList()))
        assertEquals(addCell(), landingCell(emptyList(), listOf("a.jpg")))
        // 有内置图时「＋」在内置之后
        assertEquals(addCell(4), landingCell(listOf("a.jpg"), emptyList(), builtinCount = 4))
    }

    @Test fun restoreOriginalEntryShiftsCardImages() {
        // 换卡片图:图片 0 是「恢复原图」(没有文件名),文件从图片 1 开始 → 第一个文件在格 2
        assertEquals(2, landingCell(listOf(null, "x.png", "y.png"), listOf("x.png")))
        assertEquals(3, landingCell(listOf(null, "x.png", "y.png"), listOf("y.png")))
        assertEquals(5, landingCell(listOf(null, "x.png", "y.png"), listOf("x.png"), builtinCount = 3))
    }

    // ---- 版式(R115)----

    @Test fun noBuiltinsMeansNoTitlesAndTheR63Rows() {
        val lines = pickerLines(builtinCount = 0, mineCells = 5, columns = 3)
        assertEquals(
            listOf(
                PickerLine.Cells(PickerSection.MINE, 0, 3),
                PickerLine.Cells(PickerSection.MINE, 3, 2),
            ),
            lines,
        )
    }

    @Test fun twoBlocksEachWithATitleAndRowsStartingFresh() {
        // 内置 4 张(3 列:3 + 1),我的 = 「＋」+ 2 张
        val lines = pickerLines(builtinCount = 4, mineCells = 3, columns = 3)
        assertEquals(
            listOf(
                PickerLine.Title(PickerSection.BUILTIN),
                PickerLine.Cells(PickerSection.BUILTIN, 0, 3),
                PickerLine.Cells(PickerSection.BUILTIN, 3, 1),
                PickerLine.Title(PickerSection.MINE),
                PickerLine.Cells(PickerSection.MINE, 4, 3),
            ),
            lines,
        )
        assertEquals(1, lineOfCell(lines, 0))
        assertEquals(2, lineOfCell(lines, 3))
        assertEquals(4, lineOfCell(lines, addCell(4)))
        assertEquals(4, lineOfCell(lines, 6))
        assertEquals(-1, lineOfCell(lines, 7))
    }

    @Test fun onlyThePlusCellStillHasARow() {
        assertEquals(listOf(PickerLine.Cells(PickerSection.MINE, 0, 1)), pickerLines(0, 1, 4))
    }
}
