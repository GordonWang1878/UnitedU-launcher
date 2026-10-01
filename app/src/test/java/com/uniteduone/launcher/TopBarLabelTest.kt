package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopBarLabelTest {
    /** 顶栏「哪一颗在焦点」只跟上报走:得到就是它,失去只清掉仍是它的那一颗。 */
    @Test fun gainAndLose() {
        assertEquals(0, nextFocusedPill(null, 0, got = true))
        assertNull(nextFocusedPill(0, 0, got = false))
    }

    /** 颗与颗之间换焦点时,新旧两条上报的先后不定;两种顺序都必须停在新的那一颗。 */
    @Test fun switchingPillsInEitherOrder() {
        // 先失后得
        assertEquals(1, nextFocusedPill(nextFocusedPill(0, 0, got = false), 1, got = true))
        // 先得后失:旧的那颗迟到的「失去」不能把新的清掉
        assertEquals(1, nextFocusedPill(nextFocusedPill(0, 1, got = true), 0, got = false))
    }
}
