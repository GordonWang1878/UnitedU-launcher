package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** 播放器的两个下标纯函数(spec §2)。播放器本体(协程 + 主线程)不在 JVM 上测,放模拟器。 */
class ScreensaverPlayerTest {
    @Test fun nextIndexWrapsAround() {
        assertEquals(1, nextIndex(0, 3))
        assertEquals(2, nextIndex(1, 3))
        assertEquals(0, nextIndex(2, 3))
    }

    @Test fun nextIndexOfSingleImageStaysPut() {
        assertEquals(0, nextIndex(0, 1))
    }

    @Test fun nextIndexOfEmptyGalleryIsZero() {
        assertEquals(0, nextIndex(0, 0))
        assertEquals(0, nextIndex(5, 0))
    }

    @Test fun nextIndexClampsAStaleIndexFirst() {
        // 删图后还没来得及夹回的下标:先夹到末张,再往后一张 = 回到第 0 张;负数先夹到 0
        assertEquals(0, nextIndex(7, 3))
        assertEquals(1, nextIndex(-4, 3))
    }

    @Test fun clampIndexPullsBackIntoRange() {
        assertEquals(2, clampIndex(5, 3))   // 删掉了末尾几张:落在新的末张
        assertEquals(1, clampIndex(1, 3))   // 范围内不动
        assertEquals(0, clampIndex(-1, 3))
        assertEquals(0, clampIndex(3, 0))   // 删空
    }
}
