package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验收标准 v2 D-07「不误接管」——基准部分(02 Delta 编写,2026-11-06;由 01 原样提交,不得改断言)。
 *
 * 只用 main(7645957f)上已有的公开 API:`stockHomes`、`onWindowChanged`、`debouncedOnly`(HomeKeyPolicy.kt)。
 * 这个文件在 main 上就能编译、预期全部通过;它把「普通原厂桌面 + UnitedU」的现行结果钉成字面量,
 * 修 A/B 时不论怎么改实现,这里都必须保持绿(C 组回归基准),UnitedU 自己也不得进原厂桌面集合(D 组)。
 *
 * 夹具输入的形状与 HomeKeyService.refresh 喂给 stockHomes 的完全一样:
 * `queryIntentActivities(MAIN + HOME)` 每条 → (包名, ai.name),有 activity-alias 时再加 (包名, ai.targetActivity)。
 */
class HomeKeyD07BaselineTest {
    private val self = "com.uniteduone.launcher"
    private val selfMain = self to "com.uniteduone.launcher.MainActivity"

    // 与 HomeKeyPolicyTest 同一组 Google 桌面组件
    private val launcherx = "com.google.android.apps.tv.launcherx" to "com.google.android.apps.tv.launcherx.home.HomeActivity"
    private val tvlauncher = "com.google.android.tvlauncher" to "com.google.android.tvlauncher.MainActivity"

    // 用 activity-alias 声明 HOME 的普通原厂桌面(夹具):queryIntentActivities 报别名,窗口事件带真实类名
    private val aliasPkg = "fixture.stock.aliashome"
    private val aliasName = aliasPkg to "fixture.stock.aliashome.HomeAlias"
    private val aliasTarget = aliasPkg to "fixture.stock.aliashome.RealHomeActivity"

    /** C-1:普通原厂桌面 + UnitedU —— 原厂桌面集合就是去掉自己之后的全部条目(含别名两个名字),逐项钉死。 */
    @Test fun c_stockHomesBaseline_googleLaunchersPlusSelf() {
        val all = listOf(launcherx, selfMain, tvlauncher, aliasName, aliasTarget)
        assertEquals(setOf(launcherx, tvlauncher, aliasName, aliasTarget), stockHomes(all, self))
    }

    /** C-2:原厂桌面 HOME Activity 到前台 → 拉回;同包别的窗口、去抖内 → 不拉(去抖内交给补查)。 */
    @Test fun c_windowDecisionsBaseline() {
        val stock = stockHomes(listOf(launcherx, selfMain, tvlauncher, aliasName, aliasTarget), self)
        assertTrue(onWindowChanged(true, launcherx.first, launcherx.second, stock, lastLaunchAt = 0, now = 10_000))
        assertTrue(onWindowChanged(true, tvlauncher.first, tvlauncher.second, stock, lastLaunchAt = 0, now = 10_000))
        assertTrue("别名的真实类名也认", onWindowChanged(true, aliasTarget.first, aliasTarget.second, stock, lastLaunchAt = 0, now = 10_000))
        assertFalse(onWindowChanged(true, launcherx.first, "android.widget.FrameLayout", stock, lastLaunchAt = 0, now = 10_000))
        assertFalse("不生效时不拉", onWindowChanged(false, launcherx.first, launcherx.second, stock, lastLaunchAt = 0, now = 10_000))
        assertFalse(onWindowChanged(true, launcherx.first, launcherx.second, stock, lastLaunchAt = 9_500, now = 10_000))
        assertTrue(debouncedOnly(true, launcherx.first, launcherx.second, stock, lastLaunchAt = 9_500, now = 10_000))
    }

    /** D-1:UnitedU 自己的任何 HOME 条目(主 Activity、别名、别名的真实类名)都不进原厂桌面集合。 */
    @Test fun d_selfNeverInStockHomes() {
        val selfAlias = self to "com.uniteduone.launcher.HomeAlias"
        val all = listOf(selfMain, selfAlias, launcherx)
        val stock = stockHomes(all, self)
        assertEquals(setOf(launcherx), stock)
        assertTrue(stock.none { it.first == self })
        assertEquals("只有 UnitedU 时集合为空", emptySet<Pair<String, String>>(), stockHomes(listOf(selfMain, selfAlias), self))
    }

    /** D-2:UnitedU 自己的窗口到前台,永远不触发拉起,也不触发去抖补查。 */
    @Test fun d_selfWindowNeverTriggersTakeover() {
        val stock = stockHomes(listOf(launcherx, selfMain, tvlauncher), self)
        assertFalse(onWindowChanged(true, selfMain.first, selfMain.second, stock, lastLaunchAt = 0, now = 10_000))
        assertFalse(debouncedOnly(true, selfMain.first, selfMain.second, stock, lastLaunchAt = 9_500, now = 10_000))
    }
}
