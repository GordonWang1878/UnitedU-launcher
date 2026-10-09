package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 数据格式向下兼容(spec 2026-10-09 §5):从 Beta 切回稳定版时,旧代码要读 Beta 写下的文件。
 * Beta 只许**新增**字段——这里钉住每个读盘函数对未知字段(顶层与嵌套)宽容、已知字段不丢。
 */
class ForwardCompatTest {
    @Test fun settingsIgnoresUnknownKeys() {
        val s = parseSettings("{\"cardsPerRow\":8,\"futureFlag\":true,\"futureObj\":{\"a\":1},\"futureArr\":[1,2]}")
        assertEquals(8, s.cardsPerRow)
    }

    @Test fun layoutIgnoresUnknownKeys() {
        val rows = Layout.parse("{\"rows\":[{\"icon\":\"movie\",\"apps\":[\"com.a\"],\"futureRowKey\":1}],\"futureTop\":\"x\"}")
        assertEquals(listOf("com.a"), rows.single().apps)
    }

    @Test fun titlesKeepKnownEntries() {
        val t = parseTitles("{\"com.a\":\"甲\",\"com.b\":\"乙\"}")
        assertEquals("甲", t["com.a"])
    }

    @Test fun hiddenInputsKeepKnownEntries() {
        assertEquals(setOf("in1"), parseHiddenInputs("{\"in1\":\"x\"}"))
    }
}
