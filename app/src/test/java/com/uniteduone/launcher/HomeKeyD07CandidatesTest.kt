package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验收标准 v2 D-07「不误接管」——候选桌面识别四组固定样例 A/B/C/D(02 Delta 编写,2026-11-06;由 01 原样提交,不得改断言)。
 *
 * **依赖 01 新增的可测接口**(main 7645957f 上还没有,见 README「需要 01 配合」):
 *
 *     data class HomeCandidate(val pkg: String, val cls: String, val priority: Int, val targetActivity: String? = null)
 *     fun stockHomesFrom(candidates: List<HomeCandidate>, self: String): Set<Pair<String, String>>
 *
 * 一条 HomeCandidate = `queryIntentActivities(MAIN + HOME)` 的一条 ResolveInfo:包名、`activityInfo.name`、
 * `ResolveInfo.priority`、`activityInfo.targetActivity`(不是别名时为 null)。返回值与现行 `stockHomes` 同形,
 * 直接喂给 `onWindowChanged` / `debouncedOnly`。
 *
 * 判据怎么实现(按组件名认 FallbackHome、按优先级阈值、或「负优先级默认排除 + 白名单」)由 01 定;
 * 本文件只钉四组样例的结果,任何一种实现只要四组都对就算过。
 */
class HomeKeyD07CandidatesTest {
    private val self = "com.uniteduone.launcher"
    private val selfMain = HomeCandidate(pkg = self, cls = "com.uniteduone.launcher.MainActivity", priority = 0)

    // AOSP TV 的 FallbackHome:仓库 docs/research/2026-10-28-aerial-tv-unitedu-pi-review.md §6.2 在 AOSP TV 14 AVD 上实测,priority=-1000
    private val tvFallbackHome = HomeCandidate(
        pkg = "com.android.tv.settings",
        cls = "com.android.tv.settings.system.FallbackHome",
        priority = -1000,
    )

    // AOSP 手机 / 通用 Settings 的 FallbackHome(AOSP packages/apps/Settings 清单,priority=-1000)
    private val aospFallbackHome = HomeCandidate(
        pkg = "com.android.settings",
        cls = "com.android.settings.FallbackHome",
        priority = -1000,
    )

    // 负优先级的真实桌面(夹具,优先级取自验收标准 v2 D-07-B:VIDAA,-150)
    private val vidaaHome = HomeCandidate(
        pkg = "fixture.vidaa.launcher",
        cls = "fixture.vidaa.launcher.HomeActivity",
        priority = -150,
    )

    // 普通原厂桌面:Google TV / Android TV(组件同 HomeKeyPolicyTest;tvlauncher priority=2 见上述 §6.2,launcherx 取 0)
    private val launcherx = HomeCandidate(
        pkg = "com.google.android.apps.tv.launcherx",
        cls = "com.google.android.apps.tv.launcherx.home.HomeActivity",
        priority = 0,
    )
    private val tvlauncher = HomeCandidate(
        pkg = "com.google.android.tvlauncher",
        cls = "com.google.android.tvlauncher.MainActivity",
        priority = 2,
    )

    // 用 activity-alias 声明 HOME 的普通原厂桌面(夹具)
    private val aliasHome = HomeCandidate(
        pkg = "fixture.stock.aliashome",
        cls = "fixture.stock.aliashome.HomeAlias",
        priority = 0,
        targetActivity = "fixture.stock.aliashome.RealHomeActivity",
    )

    private fun HomeCandidate.pair() = pkg to cls

    /** main 上 HomeKeyService.refresh 的展开方式(每条收 name,有别名再收 targetActivity),用来算「与 main 完全一致」的期望值。 */
    private fun mainFlatten(cs: List<HomeCandidate>): List<Pair<String, String>> =
        cs.flatMap { c -> listOfNotNull(c.pkg to c.cls, c.targetActivity?.let { c.pkg to it }) }

    // ---------- A:只有 AOSP FallbackHome 和 UnitedU ----------

    /** A-1:只有 TV 版 FallbackHome + UnitedU → FallbackHome 不被认作原厂桌面,集合为空。 */
    @Test fun a_tvFallbackHomeIsNotStockHome() {
        val stock = stockHomesFrom(listOf(tvFallbackHome, selfMain), self)
        assertFalse(tvFallbackHome.pair() in stock)
        assertEquals(emptySet<Pair<String, String>>(), stock)
    }

    /** A-2:同上,AOSP Settings 版 FallbackHome。 */
    @Test fun a_aospFallbackHomeIsNotStockHome() {
        val stock = stockHomesFrom(listOf(aospFallbackHome, selfMain), self)
        assertFalse(aospFallbackHome.pair() in stock)
        assertEquals(emptySet<Pair<String, String>>(), stock)
    }

    /** A-3:FallbackHome 在前台(接管生效中)→ 不拉起 UnitedU,也不排去抖补查。 */
    @Test fun a_fallbackHomeInForegroundDoesNotTriggerTakeover() {
        for (fb in listOf(tvFallbackHome, aospFallbackHome)) {
            val stock = stockHomesFrom(listOf(fb, selfMain), self)
            assertFalse(onWindowChanged(true, fb.pkg, fb.cls, stock, lastLaunchAt = 0, now = 10_000))
            assertFalse(debouncedOnly(true, fb.pkg, fb.cls, stock, lastLaunchAt = 9_500, now = 10_000))
        }
    }

    // ---------- B:负优先级的真实桌面 ----------

    /** B-1:优先级 -150 的真实桌面(VIDAA)要被认作原厂桌面。 */
    @Test fun b_negativePriorityRealLauncherIsStockHome() {
        val stock = stockHomesFrom(listOf(vidaaHome, selfMain), self)
        assertEquals(setOf(vidaaHome.pair()), stock)
    }

    /** B-2:它到前台(接管生效中)→ 拉回 UnitedU;去抖内 → 交给补查。 */
    @Test fun b_negativePriorityRealLauncherInForegroundTriggersTakeover() {
        val stock = stockHomesFrom(listOf(vidaaHome, selfMain), self)
        assertTrue(onWindowChanged(true, vidaaHome.pkg, vidaaHome.cls, stock, lastLaunchAt = 0, now = 10_000))
        assertTrue(debouncedOnly(true, vidaaHome.pkg, vidaaHome.cls, stock, lastLaunchAt = 9_500, now = 10_000))
    }

    /** A+B:FallbackHome 与 -150 的真实桌面同时在场 → 只收真实桌面。 */
    @Test fun ab_fallbackHomeExcludedWhileNegativePriorityLauncherKept() {
        val stock = stockHomesFrom(listOf(tvFallbackHome, vidaaHome, selfMain, aospFallbackHome), self)
        assertEquals(setOf(vidaaHome.pair()), stock)
    }

    // ---------- C:普通原厂桌面 + UnitedU,与 main 完全一致 ----------

    /** C-1:结果与 main 的 stockHomes(按 main 的展开方式)逐项相同,并与字面量相同。 */
    @Test fun c_ordinaryStockLaunchersMatchMain() {
        val cs = listOf(launcherx, selfMain, tvlauncher, aliasHome)
        val expectedLiteral = setOf(
            launcherx.pair(),
            tvlauncher.pair(),
            aliasHome.pair(),
            aliasHome.pkg to "fixture.stock.aliashome.RealHomeActivity",
        )
        assertEquals(expectedLiteral, stockHomes(mainFlatten(cs), self))
        assertEquals(stockHomes(mainFlatten(cs), self), stockHomesFrom(cs, self))
    }

    /** C-2:用新集合判窗口,结果与用 main 集合判逐项相同。 */
    @Test fun c_windowDecisionsMatchMain() {
        val cs = listOf(launcherx, selfMain, tvlauncher, aliasHome)
        val oldStock = stockHomes(mainFlatten(cs), self)
        val newStock = stockHomesFrom(cs, self)
        val windows = listOf(
            launcherx.pair(),
            tvlauncher.pair(),
            aliasHome.pair(),
            aliasHome.pkg to "fixture.stock.aliashome.RealHomeActivity",
            launcherx.pkg to "android.widget.FrameLayout",
            launcherx.pkg to "com.google.android.apps.tv.launcherx.settings.SettingsActivity",
            selfMain.pair(),
        )
        for (active in listOf(true, false)) for (w in windows) for (last in listOf(0L, 9_500L)) {
            assertEquals(
                "onWindowChanged active=$active w=$w last=$last",
                onWindowChanged(active, w.first, w.second, oldStock, last, 10_000),
                onWindowChanged(active, w.first, w.second, newStock, last, 10_000),
            )
            assertEquals(
                "debouncedOnly active=$active w=$w last=$last",
                debouncedOnly(active, w.first, w.second, oldStock, last, 10_000),
                debouncedOnly(active, w.first, w.second, newStock, last, 10_000),
            )
        }
    }

    // ---------- D:UnitedU 自己永远不被认作原厂桌面 ----------

    /** D-1:UnitedU 的任何条目(主 Activity、高优先级、别名、负优先级)都不进集合;只有 UnitedU 时集合为空。 */
    @Test fun d_selfNeverStockHome() {
        val selfVariants = listOf(
            selfMain,
            HomeCandidate(pkg = self, cls = "com.uniteduone.launcher.MainActivity", priority = 1000),
            HomeCandidate(
                pkg = self,
                cls = "com.uniteduone.launcher.HomeAlias",
                priority = 0,
                targetActivity = "com.uniteduone.launcher.MainActivity",
            ),
            HomeCandidate(pkg = self, cls = "com.uniteduone.launcher.MainActivity", priority = -150),
        )
        assertEquals(emptySet<Pair<String, String>>(), stockHomesFrom(selfVariants, self))
        val mixed = stockHomesFrom(selfVariants + listOf(launcherx, vidaaHome, tvFallbackHome), self)
        assertTrue(mixed.none { it.first == self })
    }

    /** D-2:UnitedU 自己的窗口到前台,无论集合里有谁,都不拉起、不补查。 */
    @Test fun d_selfWindowNeverTriggersTakeover() {
        val stock = stockHomesFrom(listOf(launcherx, vidaaHome, tvFallbackHome, selfMain), self)
        assertFalse(onWindowChanged(true, selfMain.pkg, selfMain.cls, stock, lastLaunchAt = 0, now = 10_000))
        assertFalse(debouncedOnly(true, selfMain.pkg, selfMain.cls, stock, lastLaunchAt = 9_500, now = 10_000))
    }
}
