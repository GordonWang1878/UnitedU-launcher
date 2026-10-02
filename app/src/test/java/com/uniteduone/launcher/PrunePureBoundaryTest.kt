package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 未安装清理的边界(PrunePureTest 之外):比例上限的每一条临界线、只有一两个包、自检先于一切、
 * 查询出错压过更新窗口、每个包只查一次;更新窗口的时钟倒退、零宽窗口、极大时间跳变。
 */
class PrunePureBoundaryTest {
    private val I = PkgPresence.INSTALLED
    private val M = PkgPresence.MISSING
    private val U = PkgPresence.UNKNOWN

    // ---- 比例上限 ----

    @Test fun ratioGuardAtEveryThreshold() {
        assertFalse(pruneRatioTooHigh(missing = 1, total = 2))   // 只有一个:永远放行
        assertFalse(pruneRatioTooHigh(missing = 1, total = 0))
        assertTrue(pruneRatioTooHigh(missing = 2, total = 3))
        assertFalse(pruneRatioTooHigh(missing = 2, total = 4))   // 恰好一半不算异常
        assertTrue(pruneRatioTooHigh(missing = 3, total = 5))
        assertFalse(pruneRatioTooHigh(missing = 0, total = 5))
    }

    @Test fun aSingleMissingPackageIsRemovedEvenWhenItIsTheOnlyOne() {
        assertEquals(PrunePlan.Remove(setOf("a")), planPrune(listOf("a"), { M }, { false }, I))
    }

    @Test fun twoPackagesBothMissingIsSkippedButOneOfTwoIsRemoved() {
        assertTrue(planPrune(listOf("a", "b"), { M }, { false }, I) is PrunePlan.Skip)
        assertEquals(PrunePlan.Remove(setOf("b")), planPrune(listOf("a", "b"), { if (it == "b") M else I }, { false }, I))
    }

    @Test fun duplicatesDoNotDiluteTheRatio() {
        // a 在三行里各出现一次:按去重后的 2 个包算,2 个都没装 → 异常
        assertTrue(planPrune(listOf("a", "a", "a", "b"), { M }, { false }, I) is PrunePlan.Skip)
    }

    // ---- 先后顺序 ----

    @Test fun theSelfCheckComesBeforeAnyQuery() {
        var asked = 0
        assertTrue(planPrune(emptyList(), { asked++; I }, { false }, U) is PrunePlan.Skip)
        assertTrue(planPrune(listOf("a"), { asked++; I }, { false }, M) is PrunePlan.Skip)
        assertEquals(0, asked)
    }

    @Test fun aQueryErrorWinsOverTheReplacingWindow() {
        // b 在更新窗口里,但查询出错:照样整次跳过(出错说明 PackageManager 此刻不可信)
        assertTrue(planPrune(listOf("a", "b"), { if (it == "b") U else I }, { it == "b" }, I) is PrunePlan.Skip)
    }

    @Test fun eachDistinctPackageIsQueriedExactlyOnce() {
        val asked = mutableListOf<String>()
        planPrune(listOf("a", "b", "a", "a", "b"), { asked += it; I }, { false }, I)
        assertEquals(listOf("a", "b"), asked.sorted())
    }

    @Test fun everyPackageMissingButReplacingRemovesNothing() {
        assertEquals(PrunePlan.Remove(emptySet()), planPrune(listOf("a", "b", "c"), { M }, { true }, I))
    }

    // ---- 从行里去掉 ----

    @Test fun withoutPackagesEdges() {
        val none = emptyList<LayoutRow>()
        assertSame(none, withoutPackages(none, setOf("a")))
        val rows = listOf(LayoutRow("movie", apps = listOf("a")), LayoutRow("tv", apps = listOf("b")))
        assertEquals(listOf(LayoutRow("movie"), LayoutRow("tv")), withoutPackages(rows, setOf("a", "b", "zzz")))
        assertSame(rows[1], withoutPackages(rows, setOf("a"))[1])
        // 同一行里重复出现的包整个去掉
        assertEquals(listOf("b"), withoutPackages(listOf(LayoutRow("movie", apps = listOf("x", "b", "x"))), setOf("x"))[0].apps)
    }

    // ---- 「正在更新」窗口 ----

    @Test fun aClockThatWentBackwardsStillCountsAsRecent() {
        // KDoc:时钟倒退(不该发生)时也算「最近」,宁可少清一次
        val w = ReplacingWindow(windowMs = 100)
        w.note("a", now = 10_000)
        assertTrue(w.isRecent("a", now = 9_999))
        assertTrue(w.isRecent("a", now = 0))
    }

    @Test fun notingWithABackwardsClockDoesNotEvictEntriesFromTheFuture() {
        val w = ReplacingWindow(windowMs = 100)
        w.note("a", now = 10_000)
        w.note("b", now = 5_000)
        assertTrue(w.isRecent("a", now = 5_000))
        assertTrue(w.isRecent("b", now = 5_000))
    }

    @Test fun aZeroWidthWindowCoversOnlyTheSameMillisecond() {
        val w = ReplacingWindow(windowMs = 0)
        w.note("a", now = 7)
        assertTrue(w.isRecent("a", now = 7))
        assertFalse(w.isRecent("a", now = 8))
    }

    @Test fun aHugeTimeJumpExpiresTheEntry() {
        val w = ReplacingWindow()
        w.note("a", now = 0)
        assertFalse(w.isRecent("a", now = Long.MAX_VALUE))
    }

    @Test fun expiredEntriesAreDroppedOnTheNextNote() {
        // 过期的条目在下一次 note 时被清掉:之后即便时钟倒退回窗口之内,它也不再算「最近」
        val w = ReplacingWindow(windowMs = 100)
        w.note("a", now = 0)
        w.note("b", now = 100)                 // a 恰好在窗口边上(100 − 0 = 100,不 > 100):留着
        assertTrue(w.isRecent("a", now = 50))
        w.note("c", now = 101)                 // 101 − 0 > 100:a 被清掉
        assertFalse(w.isRecent("a", now = 50))
        assertTrue(w.isRecent("b", now = 101))
    }
}
