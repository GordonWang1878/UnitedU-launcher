package com.uniteduone.launcher

import android.content.Context

/**
 * R167(报告 perf-2026-10-10 #2):首页读好的卡片数据(含已缩到卡片尺寸的位图)在进程里留一份,编辑页进页第一帧直接用,
 * 不再进页才 `Apps.load` 一遍、先画文字占位再整排换图。
 * **失效 = MainActivity 的 revision 变了**:装 / 卸 / 更新应用、换卡片图、恢复原图都会 revision++,首页与编辑页本来就按这个量重读,
 * 所以失效时机与首页逐一致。[checked] = 这份是给哪些包查的:查过但没找到 = 被停用(R67,编辑页不画),没查过 = 不命中。
 * 只存同一批 AppEntry 的引用,不多占位图内存。整份不可变快照 + @Volatile:IO 线程写、任意线程读。
 */
internal class AppEntrySnapshot(val revision: Int, val checked: Set<String>, val entries: Map<String, AppEntry>)

internal object AppEntryCache {
    @Volatile private var snap: AppEntrySnapshot? = null
    /** 第几个 MainActivity 实例的缓存([clear] +1)。读取开始时记下,发布时对不上就丢(上一个实例在 IO 线程上没读完的那一趟)。 */
    @Volatile var epoch = 0
        private set
    @Synchronized fun publish(s: AppEntrySnapshot, epoch: Int) { if (epoch == this.epoch) snap = s }
    fun lookup(revision: Int, needed: Set<String>): Map<String, AppEntry>? = appEntryHit(snap, revision, needed)
    /** 同一 revision 的那一份(不管覆不覆盖):[loadAppEntries] 只补查缺的包。 */
    fun sameRevision(revision: Int): AppEntrySnapshot? = snap?.takeIf { it.revision == revision }
    /**
     * MainActivity.onCreate 调。revision 是 Activity 的字段,每个新实例从 0 数起;上一个实例留下的快照号可能与新实例的号相同,
     * 而两个实例之间(Activity 被销毁、进程还活着)装 / 卸的应用没人通知——不清就会按同号命中一份过期数据(僵尸卡 / 缺图)。
     */
    @Synchronized fun clear() { snap = null; epoch++ }
}

/** 命中 = 同一个 revision,且这份查过的包覆盖了 [needed]。 */
internal fun appEntryHit(s: AppEntrySnapshot?, revision: Int, needed: Set<String>): Map<String, AppEntry>? =
    s?.takeIf { it.revision == revision && it.checked.containsAll(needed) }?.entries

/**
 * 同一 revision 只补查了 [missing]:[fresh] 是 [Apps.load] 的结果——**全部**桌面应用,只有 [missing] 带位图 / 名字,
 * 所以底子用 fresh(桌面应用全集,R67 的「查过没找到」靠它),再把 [base] 查过的那几个(已解码)盖回去。
 */
internal fun mergeAppEntries(base: AppEntrySnapshot, fresh: Map<String, AppEntry>, missing: Set<String>): AppEntrySnapshot =
    AppEntrySnapshot(base.revision, base.checked + missing, fresh + base.entries.filterKeys { it in base.checked })

private val loadLock = Any()

/**
 * 先查缓存,没命中再 [Apps.load] 并发布。**IO 线程调用。** 整段串行(R167 复审 2):编辑页开着时首页也在底下(Task 3),
 * 装 / 卸 / 换卡片图让 revision++ 时两边同时没命中——不串行就各解码一遍全部横幅;串行后后到的那个直接命中。
 * 同一 revision 已有一份(编辑页加了应用、首页随写盘重读)只补查缺的包,不重解码已有的位图。
 */
internal fun loadAppEntries(ctx: Context, revision: Int, needed: Set<String>): Map<String, AppEntry> = synchronized(loadLock) {
    AppEntryCache.lookup(revision, needed)?.let { return@synchronized it }
    val epoch = AppEntryCache.epoch
    val base = AppEntryCache.sameRevision(revision)
    val missing = if (base == null) needed else needed - base.checked
    val fresh = Apps.load(ctx, missing, withBitmaps = missing, withLabels = missing)
    val snap = if (base == null) AppEntrySnapshot(revision, needed, fresh) else mergeAppEntries(base, fresh, missing)
    AppEntryCache.publish(snap, epoch)
    snap.entries
}
