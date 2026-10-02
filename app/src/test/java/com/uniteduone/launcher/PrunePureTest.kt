package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ruling R68:启动 / 回到前台时清理没装的包——判据与防误删。 */
class PrunePureTest {
    private val I = PkgPresence.INSTALLED
    private val M = PkgPresence.MISSING
    private val U = PkgPresence.UNKNOWN
    private fun presenceOf(m: Map<String, PkgPresence>): (String) -> PkgPresence = { m.getValue(it) }

    @Test fun removesMissingPackages() {
        val plan = planPrune(listOf("a", "b", "c"), presenceOf(mapOf("a" to I, "b" to I, "c" to M)), { false }, I)
        assertEquals(PrunePlan.Remove(setOf("c")), plan)
    }

    @Test fun nothingMissingIsAnEmptyRemove() {
        assertEquals(PrunePlan.Remove(emptySet()), planPrune(listOf("a"), { I }, { false }, I))
        assertEquals(PrunePlan.Remove(emptySet()), planPrune(emptyList(), { M }, { false }, I))
    }

    @Test fun recentlyReplacedPackageIsNotRemoved() {
        val plan = planPrune(listOf("a", "b", "c"), presenceOf(mapOf("a" to I, "b" to I, "c" to M)), { it == "c" }, I)
        assertEquals(PrunePlan.Remove(emptySet()), plan)
    }

    @Test fun anyQueryErrorSkipsTheWholeRun() {
        val plan = planPrune(listOf("a", "b", "c"), presenceOf(mapOf("a" to I, "b" to U, "c" to M)), { false }, I)
        assertTrue(plan is PrunePlan.Skip)
    }

    @Test fun failingSelfCheckSkips() {
        assertTrue(planPrune(listOf("a"), { M }, { false }, M) is PrunePlan.Skip)
        assertTrue(planPrune(listOf("a"), { M }, { false }, U) is PrunePlan.Skip)
    }

    @Test fun moreThanHalfMissingSkips() {
        val m = mapOf("a" to I, "b" to M, "c" to M)
        assertTrue(planPrune(m.keys, presenceOf(m), { false }, I) is PrunePlan.Skip)
        // 恰好一半不算异常
        val half = mapOf("a" to I, "b" to I, "c" to M, "d" to M)
        assertEquals(PrunePlan.Remove(setOf("c", "d")), planPrune(half.keys, presenceOf(half), { false }, I))
    }

    @Test fun ratioGuardLetsASingleStalePackageThrough() {
        assertFalse(pruneRatioTooHigh(missing = 1, total = 1))
        assertTrue(pruneRatioTooHigh(missing = 2, total = 2))
        assertTrue(pruneRatioTooHigh(missing = 11, total = 11))
        assertFalse(pruneRatioTooHigh(missing = 0, total = 0))
    }

    @Test fun ratioCountsAfterTheReplacingWindow() {
        // 更新中的包不算「没装」,也就不推高比例:b 在更新窗口里,只有 c 一个没装 → 放行
        val m = mapOf("a" to I, "b" to M, "c" to M)
        assertEquals(PrunePlan.Remove(setOf("c")), planPrune(m.keys, presenceOf(m), { it == "b" }, I))
    }

    @Test fun duplicatesAcrossRowsCountOnce() {
        val m = mapOf("a" to I, "b" to I, "c" to M)
        assertEquals(PrunePlan.Remove(setOf("c")), planPrune(listOf("a", "c", "b", "c"), presenceOf(m), { false }, I))
    }

    @Test fun withoutPackagesKeepsRowsAndOrder() {
        val rows = listOf(LayoutRow("movie", apps = listOf("a", "x", "b")), LayoutRow("tv", apps = listOf("x")))
        val next = withoutPackages(rows, setOf("x"))
        assertEquals(listOf(listOf("a", "b"), emptyList()), next.map { it.apps })
        assertEquals("tv", next[1].icon)
        assertSame(rows, withoutPackages(rows, setOf("zzz")))
        assertSame(rows, withoutPackages(rows, emptySet()))
    }

    @Test fun replacingWindowExpiresAfterSixtySeconds() {
        val w = ReplacingWindow()
        w.note("a", now = 1_000)
        assertTrue(w.isRecent("a", now = 1_000))
        assertTrue(w.isRecent("a", now = 1_000 + REPLACING_WINDOW_MS))
        assertFalse(w.isRecent("a", now = 1_001 + REPLACING_WINDOW_MS))
        assertFalse(w.isRecent("b", now = 1_000))
    }

    @Test fun replacingWindowRenewsOnAnotherEvent() {
        val w = ReplacingWindow(windowMs = 100)
        w.note("a", now = 0)
        w.note("a", now = 90)   // PACKAGE_ADDED 跟在 PACKAGE_REMOVED 后面
        assertTrue(w.isRecent("a", now = 180))
        w.note("b", now = 500)  // 顺手清掉过期的 a
        assertFalse(w.isRecent("a", now = 500))
    }
}
