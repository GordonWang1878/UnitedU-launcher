package com.uniteduone.launcher

/** 首页移动态里被搬的那张卡的位置(首页渲染行的下标,含置顶的输入源行)。按位置追踪,不按包名找(同一个包可以在两行里)。 */
data class MovePos(val row: Int, val col: Int)

enum class MoveDir { LEFT, RIGHT, UP, DOWN }

/**
 * 搬一步(M4b spec §0-9)。左右:与同行邻卡换位,到头不动。上下:落到那个方向最近的**应用行**
 * (跳过输入源行)的同一列,越过行尾就放行尾;那个方向没有应用行 → 不动;**那一行里已经有同一个包 → 不动**
 * (也不越过它去找更远的行)——一行里一个包只能有一张(`Layout.read` 做 distinct),搬进去的话放下时
 * 会被合并掉,卡片等于从源行凭空消失(M4b Task 5 跟进裁定)。源行被移空 → 从结果里去掉
 * (首页不显示空行),落点行号随之校正。不动时返回**同一个** list 与原位置。
 */
internal fun moveCard(rows: List<Row>, pos: MovePos, dir: MoveDir): Pair<List<Row>, MovePos> {
    val src = rows.getOrNull(pos.row) ?: return rows to pos
    if (src.kind != RowKind.APPS || pos.col !in src.apps.indices) return rows to pos
    when (dir) {
        MoveDir.LEFT, MoveDir.RIGHT -> {
            val to = if (dir == MoveDir.LEFT) pos.col - 1 else pos.col + 1
            if (to !in src.apps.indices) return rows to pos
            val apps = src.apps.toMutableList().apply { val t = this[pos.col]; this[pos.col] = this[to]; this[to] = t }
            return rows.mapIndexed { i, r -> if (i == pos.row) r.copy(apps = apps) else r } to pos.copy(col = to)
        }
        MoveDir.UP, MoveDir.DOWN -> {
            val step = if (dir == MoveDir.UP) -1 else 1
            var t = pos.row + step
            while (t in rows.indices && rows[t].kind != RowKind.APPS) t += step
            if (t !in rows.indices) return rows to pos
            val card = src.apps[pos.col]
            val target = rows[t]
            if (target.apps.any { it.packageName == card.packageName }) return rows to pos
            val col = pos.col.coerceAtMost(target.apps.size)
            val next = rows.mapIndexed { i, r ->
                when (i) {
                    pos.row -> r.copy(apps = r.apps.filterIndexed { c, _ -> c != pos.col })
                    t -> r.copy(apps = r.apps.toMutableList().apply { add(col, card) })
                    else -> r
                }
            }
            if (next[pos.row].apps.isNotEmpty()) return next to MovePos(t, col)
            val pruned = next.filterIndexed { i, _ -> i != pos.row }
            return pruned to MovePos(if (t > pos.row) t - 1 else t, col)
        }
    }
}

/**
 * 放下时写回 layout.json 的内容(纯函数)。对磁盘上的每一行 L:
 * - **可见顺序没变**(工作副本里 layoutRow == L 那一行的包序与原来相同,含两边都没有这一行)→ 照磁盘**原样**返回。
 *   这次没搬到的行一个字节都不动:首页没显示的包(未安装的)留在它原来的位置,装上之后还出现在原来那一格
 *   (M4b Task 5 跟进裁定;原先每一行都会把它们挪到行尾)。
 * - 可见顺序变了 → 新顺序 = 工作副本里那一行的包(没有 = 被移空)+ 该行里首页原本就没显示的包(保持相对顺序)。
 * 名字、图标、行序全部照磁盘;首页不显示的行(空行、全是未安装)原样保留。
 */
internal fun mergeMove(disk: List<LayoutRow>, original: List<Row>, working: List<Row>): List<LayoutRow> =
    disk.mapIndexed { l, row ->
        val before = original.firstOrNull { it.kind == RowKind.APPS && it.layoutRow == l }?.apps?.map { it.packageName }
        val now = working.firstOrNull { it.kind == RowKind.APPS && it.layoutRow == l }?.apps?.map { it.packageName }
        if (now == before) return@mapIndexed row
        val shown = before.orEmpty().toSet()
        row.copy(apps = (now.orEmpty() + row.apps.filter { it !in shown }).distinct())
    }

/**
 * 首页原地移动态(M4b spec §3)。**只有一份**,住在 MainActivity:按键(dispatchKeyEvent)改它,HomeScreen 照它画。
 * - [pos]:被搬的卡现在在哪一格,同时就是首页焦点的目标格(HomeScreen 的还原效果以它为 key)。
 * - [rows]:首页已渲染的行的工作副本——不重读磁盘、不重解码图标,每一步只是列表重排。
 * - [original]:进入移动态那一刻的样子;放下时与磁盘合并([mergeMove]),取消时首页照磁盘数据原样画回。
 * - [from]:出发那一格,取消后焦点回这里。
 * - [committing]:已按确定、正在写盘。这段时间按键一律不动、取消入口一律不理,由写盘结果决定去留。
 */
data class MoveState(
    val pos: MovePos,
    val rows: List<Row>,
    val original: List<Row>,
    val from: MovePos,
    val committing: Boolean = false,
)

/**
 * 移动态结束时焦点该落的那一格(放下 = 卡的新位置;取消 = 出发那一格)。HomeScreen 的还原效果把它写进
 * 自己的目标格,**每个落点只写一次**——按对象身份比对,所以故意不是 data class:两次取消回到同一格
 * 也是两个落点。[wrote] = 这次放下真的写了盘:首页重读落地之前,继续画搬好的那一份。
 */
class MoveLanding(val pos: MovePos, val wrote: Boolean)

/**
 * 编辑页搬卡一步(M4b spec §0-18,Gordon 2026-09-20):与首页 [moveCard] 同一套键位,对象是 layout.json 的行——
 * 空行也是合法落点(编辑页显示空行),源行被移空照样保留;编辑页没有输入源行。左右:与同行邻卡换位,到头不动。
 * 上下:落到相邻行的同一列,越过行尾放行尾;相邻行已有同一个包 → 不动(同首页 Ruling M4b-R16);那个方向没有行 → 不动。
 * 不动时返回同一个 list 与原位置。
 */
internal fun moveInLayout(rows: List<LayoutRow>, pos: MovePos, dir: MoveDir): Pair<List<LayoutRow>, MovePos> {
    val src = rows.getOrNull(pos.row) ?: return rows to pos
    if (pos.col !in src.apps.indices) return rows to pos
    return when (dir) {
        MoveDir.LEFT, MoveDir.RIGHT -> {
            val to = if (dir == MoveDir.LEFT) pos.col - 1 else pos.col + 1
            if (to !in src.apps.indices) return rows to pos
            val apps = src.apps.toMutableList().apply { val t = this[pos.col]; this[pos.col] = this[to]; this[to] = t }
            rows.mapIndexed { i, r -> if (i == pos.row) r.copy(apps = apps) else r } to pos.copy(col = to)
        }
        MoveDir.UP, MoveDir.DOWN -> {
            val t = pos.row + if (dir == MoveDir.UP) -1 else 1
            val target = rows.getOrNull(t) ?: return rows to pos
            val pkg = src.apps[pos.col]
            if (pkg in target.apps) return rows to pos
            val col = pos.col.coerceAtMost(target.apps.size)
            rows.mapIndexed { i, r ->
                when (i) {
                    pos.row -> r.copy(apps = r.apps.filterIndexed { c, _ -> c != pos.col })
                    t -> r.copy(apps = r.apps.toMutableList().apply { add(col, pkg) })
                    else -> r
                }
            } to MovePos(t, col)
        }
    }
}
