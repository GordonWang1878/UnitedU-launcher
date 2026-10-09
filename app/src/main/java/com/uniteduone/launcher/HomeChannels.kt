package com.uniteduone.launcher

import kotlin.math.abs

/**
 * 首页要画的行(R164):应用行要有卡;频道行要有内容([ChannelContent.Ready])——没授权、找不到、没节目的频道行与空应用行
 * 一样整行不画(spec §2.3)。
 *
 * ⚠️ 这个过滤**同时是焦点的不变量**(原来写在 buildRows 末尾,R164 挪到这里):upTarget / downTarget 指向相邻行的 rowFocus,
 * 而 rowFocus 只挂在画出来的卡上;哪天想「空行也显示出来」,那些 requester 就会挂空,focusProperties 给出非 Default 的
 * requester 会短路几何搜索——按上 / 下将完全没反应。
 */
internal fun withChannelContent(rows: List<Row>, content: Map<ChannelRef, ChannelContent>): List<Row> =
    rows.mapNotNull { r ->
        val ref = r.channel ?: return@mapNotNull r.takeIf { it.apps.isNotEmpty() }
        val ready = content[ref] as? ChannelContent.Ready ?: return@mapNotNull null
        if (ready.programs.isEmpty()) null else r.copy(programs = ready.programs)
    }

/** 焦点目标列夹到第 [row] 行的格数以内(应用行按应用数、频道行按节目数;行不在了 → 0)。还原效果的「目标」与 requester 挂点共用它(铁律 2)。 */
internal fun homeFocusCol(rows: List<Row>, row: Int, wanted: Int): Int =
    wanted.coerceIn(0, ((rows.getOrNull(row)?.cellCount ?: 1) - 1).coerceAtLeast(0))

/** 海报只加载焦点行与上下各一行(spec §3.3);其余行先画占位底色,进入范围再加载。 */
internal fun loadsPosters(row: Int, activeRow: Int): Boolean = abs(row - activeRow) <= 1

/**
 * **owner 裁定(2026-10-08)**:首页焦点目标的行按 **layout.json 行号**([Row.layoutRow])认,不按「画出来的第几行」。
 * 频道行会因为没内容 / 没授权 / 节目晚到而整行出现或消失,画出来的行号随之整体挪一格;按画出来的行号记目标的话,
 * 焦点会被还原到别的行上。返回 [tgtLayoutRow] 此刻画在第几行:那一行没画 → 它下面第一行(补上它位置的那一行),
 * 再没有 → 最后一行;还没定目标(< 0)或一行都没有 → 0。
 */
internal fun homeTargetRow(rows: List<Row>, tgtLayoutRow: Int): Int {
    if (rows.isEmpty() || tgtLayoutRow < 0) return 0
    val exact = rows.indexOfFirst { it.layoutRow == tgtLayoutRow }
    if (exact >= 0) return exact
    val below = rows.indexOfFirst { it.layoutRow > tgtLayoutRow }
    return if (below >= 0) below else rows.lastIndex
}

/** 目标格 = ([homeTargetRow], [tgtCol] 夹到那一行的格数)。还原效果的退出判据与 requester 挂点用同一个夹取(铁律 2)。 */
internal fun homeTargetCell(rows: List<Row>, tgtLayoutRow: Int, tgtCol: Int): Pair<Int, Int> {
    val r = homeTargetRow(rows, tgtLayoutRow)
    return r to homeFocusCol(rows, r, tgtCol)
}
