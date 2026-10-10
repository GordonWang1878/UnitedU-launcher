package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R167 进程级卡片缓存:命中条件 = 同一个 revision 且查过的包覆盖所需。纯 JVM。 */
class AppEntryCacheTest {
    private fun e(p: String) = AppEntry(p, p.uppercase(), null, isWide = false)
    private val entries = mapOf("a" to e("a"), "b" to e("b"))
    private val snap = AppEntrySnapshot(revision = 3, checked = setOf("a", "b", "gone"), entries = entries)

    @Test fun hitWhenSameRevisionAndCovered() = assertEquals(entries, appEntryHit(snap, 3, setOf("a", "b")))
    @Test fun subsetIsAHit() = assertEquals(entries, appEntryHit(snap, 3, setOf("a")))
    /** 查过却没找到的包(被停用)也算覆盖:编辑页按 R67 不画它,与首页一致 */
    @Test fun checkedButNotFoundStillHits() = assertEquals(entries, appEntryHit(snap, 3, setOf("a", "gone")))
    /** 装 / 卸 / 换卡片图都会 revision++:旧快照一律作废(Review Focus 1) */
    @Test fun otherRevisionMisses() = assertNull(appEntryHit(snap, 4, setOf("a")))
    /** 编辑页刚加了一个新应用:没查过 → 不命中,照旧去读 */
    @Test fun uncheckedPackageMisses() = assertNull(appEntryHit(snap, 3, setOf("a", "new")))
    @Test fun emptyCacheMisses() = assertNull(appEntryHit(null, 0, emptySet()))
    /**
     * 新的 MainActivity 实例 revision 从 0 重新数:上一个实例留下的快照号可能恰好相同(都是 0),而两个实例之间装 / 卸的应用
     * 没人通知(接收器跟着 Activity 走)。onCreate 清掉,同号也不命中。
     */
    @Test fun clearedCacheMissesEvenWithSameRevision() {
        AppEntryCache.publish(AppEntrySnapshot(revision = 0, checked = setOf("a"), entries = entries), AppEntryCache.epoch)
        assertEquals(entries, AppEntryCache.lookup(0, setOf("a")))
        AppEntryCache.clear()
        assertNull(AppEntryCache.lookup(0, setOf("a")))
    }
    /** 上一个实例开始的读取在 clear() 之后才回来:按旧 epoch 发布,丢掉(否则新实例的 revision 0 会命中它) */
    @Test fun publishFromPreviousInstanceIsDropped() {
        AppEntryCache.clear()
        val old = AppEntryCache.epoch
        AppEntryCache.clear()
        AppEntryCache.publish(AppEntrySnapshot(revision = 0, checked = setOf("a"), entries = entries), old)
        assertNull(AppEntryCache.lookup(0, setOf("a")))
    }
    /**
     * 编辑页加了一个应用(同一 revision、needed 多了 "n"):只补查 "n",已有的位图原样沿用。
     * [Apps.load] 的结果含**全部**桌面应用(只有要的那几个带位图)——不能拿它整份盖掉已有条目,否则 "a" 的位图会变成没解码的那一份。
     */
    @Test fun mergeKeepsDecodedEntriesAndAddsMissing() {
        val fresh = mapOf("a" to AppEntry("a", "", null, isWide = false), "n" to e("n"), "other" to AppEntry("other", "", null, isWide = false))
        val m = mergeAppEntries(snap, fresh, missing = setOf("n"))
        assertEquals(3, m.revision)
        assertEquals(setOf("a", "b", "gone", "n"), m.checked)
        assertEquals(e("a"), m.entries["a"])
        assertEquals(e("n"), m.entries["n"])
        assertEquals(entries + ("n" to e("n")), appEntryHit(m, 3, setOf("a", "n")).orEmpty().filterKeys { it != "other" })
    }
    /** 复审 2:revision 5 的读取先发布,revision 4 的那一趟晚回来——不能盖掉新的(否则下一次查 5 没命中、整份重解码) */
    @Test fun stalerRevisionDoesNotReplaceNewer() {
        AppEntryCache.clear()
        val ep = AppEntryCache.epoch
        AppEntryCache.publish(AppEntrySnapshot(revision = 5, checked = setOf("a"), entries = entries), ep)
        AppEntryCache.publish(AppEntrySnapshot(revision = 4, checked = setOf("a"), entries = entries), ep)
        assertEquals(entries, AppEntryCache.lookup(5, setOf("a")))
        assertNull(AppEntryCache.lookup(4, setOf("a")))
    }
    /** 同一 revision 补查后的合并结果照收(checked 变大) */
    @Test fun sameRevisionMergeReplaces() {
        AppEntryCache.clear()
        val ep = AppEntryCache.epoch
        AppEntryCache.publish(AppEntrySnapshot(revision = 2, checked = setOf("a"), entries = entries), ep)
        AppEntryCache.publish(AppEntrySnapshot(revision = 2, checked = setOf("a", "b"), entries = entries), ep)
        assertEquals(entries, AppEntryCache.lookup(2, setOf("a", "b")))
    }
}
