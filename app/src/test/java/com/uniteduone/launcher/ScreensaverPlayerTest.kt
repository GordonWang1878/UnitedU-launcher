package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** 引用计数抽成 RefCounter 在 JVM 上测;协程与主线程部分放模拟器。 */
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

    @Test fun firstAcquireStartsAndNestedAcquiresDoNot() {
        val r = RefCounter()
        assertTrue(r.acquire())      // 桌面屏保先到:启动计时
        assertFalse(r.acquire())     // 系统屏保叠上来接班:不起第二个计时(否则换图速度翻倍)
        assertEquals(2, r.count)
    }

    @Test fun onlyTheLastReleaseStops() {
        val r = RefCounter()
        r.acquire(); r.acquire()
        assertFalse(r.release())     // 桌面先走、系统屏保还在:计时继续
        assertTrue(r.release())      // 最后一个走:停计时
        assertEquals(0, r.count)
    }

    @Test fun extraReleaseIsANoOp() {
        val r = RefCounter()
        assertFalse(r.release())     // 多 detach 一次:不会变负数
        assertEquals(0, r.count)
        assertTrue(r.acquire())      // 也不会让下一次 attach 起不了计时
    }

    @Test fun reacquireAfterFullReleaseStartsAgain() {
        val r = RefCounter()
        r.acquire(); r.release()
        assertTrue(r.acquire())
    }
}
