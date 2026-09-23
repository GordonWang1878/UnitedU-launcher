package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 图片网格首格「＋ 从手机添加」(Ruling R63)的下标换算:格子下标 = 图片下标 + 1。
 * 焦点记忆(focusedIdx)、删图后的夹紧、扫码页回来的落点都按格子下标算,错一位就会落到隔壁那张图上。
 */
class PickerCellsTest {
    @Test fun plusIsCellZeroAndImagesFollow() {
        assertEquals(listOf("+", "a", "b"), pickerCells(listOf("a", "b"), "+"))
        assertEquals(listOf("+"), pickerCells(emptyList(), "+"))
        assertEquals(0, ADD_CELL)
    }

    @Test fun cellAndImageIndexRoundTrip() {
        for (i in 0..20) assertEquals(i, imageOfCell(cellOfImage(i)))
        assertNull(imageOfCell(ADD_CELL))   // 「＋」不是图片:不预览、不删
        assertEquals(1, cellOfImage(0))
    }

    @Test fun deletingTheLastImageClampsToThePreviousOne() {
        // 3 张图 = 4 格,焦点在末张(格 3);删掉它剩 3 格 → 夹到格 2 = 新的末张
        assertEquals(2, clampCell(3, cellCount = 3))
    }

    @Test fun deletingAMiddleImageKeepsTheSameCell() {
        // 焦点在格 2(第 2 张图),删掉它后同一格由下一张补上,格子下标不变
        assertEquals(2, clampCell(2, cellCount = 3))
    }

    @Test fun deletingEverythingLandsOnPlus() {
        assertEquals(ADD_CELL, clampCell(1, cellCount = 1))
        assertEquals(ADD_CELL, clampCell(5, cellCount = 0))   // 防御:还没有格子时也不越界
    }

    @Test fun landingIsTheFirstUploadStillInTheGrid() {
        val names = listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg")
        // 按上传先后取第一张:传了 c 再传 a,落 c(图片 2 → 格 3),不是网格里排在前面的 a
        assertEquals(3, landingCell(names, listOf("c.jpg", "a.jpg")))
        // 第一张在手机上又删了 → 顺延到下一张还在的
        assertEquals(1, landingCell(names, listOf("gone.jpg", "a.jpg")))
    }

    @Test fun noUploadLandsOnPlus() {
        assertEquals(ADD_CELL, landingCell(listOf("a.jpg"), emptyList()))
        assertEquals(ADD_CELL, landingCell(emptyList(), listOf("a.jpg")))
    }

    @Test fun restoreOriginalEntryShiftsCardImages() {
        // 换卡片图:图片 0 是「恢复原图」(没有文件名),文件从图片 1 开始 → 第一个文件在格 2
        assertEquals(2, landingCell(listOf(null, "x.png", "y.png"), listOf("x.png")))
        assertEquals(3, landingCell(listOf(null, "x.png", "y.png"), listOf("y.png")))
    }
}
