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

/**
 * 还原效果**落定之后**要不要改写目标(Task 12 修订一):返回 (layout 行号, 列号) = 新目标,null = 不改。
 * 目标行不在了、焦点落到补位行([homeTargetRow]),或这一行变短、列被夹到末张([homeFocusCol])时,把目标改成实际落点:
 * 否则那一行过一会儿又有了内容 / 又变长,还原效果会把焦点从用户眼前这一格拽走(owner 裁定「整行出现 / 消失,焦点不动」);
 * 列号跟着夹过的那一格,上下键同列落点从这里算。
 * 只在 [landed](目标自报落地,铁律 2)、[resumed](前台——ON_PAUSE 起目标冻结,铁律 5:用户从频道行打开节目后发布方在后台
 * 清空重发,补位落点不能把「回来要回到的那张卡」改掉)、非 [moving](目标归 moving.pos)时改。
 */
internal fun homeLandingTarget(rows: List<Row>, want: Pair<Int, Int>, landed: Boolean, resumed: Boolean, moving: Boolean): Pair<Int, Int>? {
    if (!landed || !resumed || moving) return null
    val row = rows.getOrNull(want.first) ?: return null
    return row.layoutRow to want.second
}
