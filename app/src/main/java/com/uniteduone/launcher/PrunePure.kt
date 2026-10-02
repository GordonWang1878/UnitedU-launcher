package com.uniteduone.launcher

import java.util.concurrent.ConcurrentHashMap

/**
 * 启动 / 回到前台时的「未安装清理」的纯判据(Ruling R68,2026-09-23)。与 Android 无关的部分单独放在这里,
 * JVM 单测直接测;接 PackageManager 的那一半见 PackagePruning.kt 的 `pruneMissingPackages`。
 */

/** 一个包此刻在不在。[UNKNOWN] = 查询本身出了错(不是 NameNotFound),这一次什么都不能判。 */
internal enum class PkgPresence { INSTALLED, MISSING, UNKNOWN }

/** 一次清理的结论:要去掉哪些包,或者整次跳过(只 Log、不写盘)。 */
internal sealed interface PrunePlan {
    data class Remove(val pkgs: Set<String>) : PrunePlan
    data class Skip(val reason: String) : PrunePlan
}

/**
 * 「未安装」比例上限:没装的包**超过一半**(且不止一个)就当 PackageManager 在闹脾气,整次跳过。
 * 只剩一个包、正好没装的情形放行——否则一行只放了一个应用的人,那张僵尸卡永远清不掉。
 */
internal fun pruneRatioTooHigh(missing: Int, total: Int): Boolean = missing > 1 && missing * 2 > total

/**
 * @param pkgs layout.json 里的全部包(去重;layout.json 只有应用行,输入源行不在里面,天然不受影响)。
 * @param presence 逐包查询。
 * @param recentlyReplaced 最近 [REPLACING_WINDOW_MS] 内收到过「正在更新」(`EXTRA_REPLACING`)的包:
 *   更新过程中包会短暂查不到,这种一律不算没装。
 * @param canary 自己这个包查出来的结果:连自己都查不到(或查询出错),说明 PackageManager 这会儿不可信。
 *
 * 规则(任一条命中就整次跳过):自检不是 INSTALLED;任何一个包查询出错;没装的比例 [pruneRatioTooHigh]。
 * 否则去掉「没装、且不在更新窗口里」的包(可能是空集 = 什么都不用做)。
 */
internal fun planPrune(
    pkgs: Collection<String>,
    presence: (String) -> PkgPresence,
    recentlyReplaced: (String) -> Boolean,
    canary: PkgPresence,
): PrunePlan {
    if (canary != PkgPresence.INSTALLED) return PrunePlan.Skip("自检失败:查自己的包得到 $canary")
    val distinct = pkgs.toSet()
    if (distinct.isEmpty()) return PrunePlan.Remove(emptySet())
    val status = distinct.associateWith(presence)
    status.entries.firstOrNull { it.value == PkgPresence.UNKNOWN }?.let {
        return PrunePlan.Skip("查询出错:${it.key}")
    }
    val missing = status.filterValues { it == PkgPresence.MISSING }.keys.filterNotTo(HashSet(), recentlyReplaced)
    if (pruneRatioTooHigh(missing.size, distinct.size)) {
        return PrunePlan.Skip("没装的比例异常:${missing.size}/${distinct.size}(${missing.sorted().joinToString()})")
    }
    return PrunePlan.Remove(missing)
}

/** 纯函数:把 [gone] 从每一行去掉;一个都没命中时返回**同一个** list。行图标、行序、空行都保留。 */
internal fun withoutPackages(rows: List<LayoutRow>, gone: Set<String>): List<LayoutRow> {
    if (gone.isEmpty() || rows.none { r -> r.apps.any { it in gone } }) return rows
    return rows.map { r -> if (r.apps.any { it in gone }) r.copy(apps = r.apps.filter { it !in gone }) else r }
}

/** 「正在更新」窗口:收到 `EXTRA_REPLACING` 的包在这么久之内不清。 */
internal const val REPLACING_WINDOW_MS = 60_000L

/**
 * 记最近的「正在更新」事件(`PACKAGE_REMOVED` / `PACKAGE_ADDED` 带 `EXTRA_REPLACING`)。时间由调用方传
 * (`SystemClock.elapsedRealtime()`,单测传假时钟)。进程内一份([recentReplacements]),线程安全:
 * 广播在主线程记、清理在 IO 线程查。进程死了就没了——那时更新早已结束,查询结果本来就可信。
 */
internal class ReplacingWindow(private val windowMs: Long = REPLACING_WINDOW_MS) {
    private val seen = ConcurrentHashMap<String, Long>()

    fun note(pkg: String, now: Long) {
        seen[pkg] = now
        seen.entries.removeIf { now - it.value > windowMs }   // 顺手清掉过期的,表不会越长越大
    }

    /** 时钟倒退(不该发生:elapsedRealtime 单调)时也算「最近」,宁可少清一次。 */
    fun isRecent(pkg: String, now: Long): Boolean = seen[pkg]?.let { now - it <= windowMs } == true
}

internal val recentReplacements = ReplacingWindow()
