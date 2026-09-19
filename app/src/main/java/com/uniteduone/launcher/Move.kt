package com.uniteduone.launcher

/** 首页移动态里被搬的那张卡的位置(首页渲染行的下标,含置顶的输入源行)。按位置追踪,不按包名找(同一个包可以在两行里)。 */
data class MovePos(val row: Int, val col: Int)

enum class MoveDir { LEFT, RIGHT, UP, DOWN }

/**
 * 搬一步(M4b spec §0-9)。左右:与同行邻卡换位,到头不动。上下:落到那个方向最近的**应用行**
 * (跳过输入源行)的同一列,越过行尾就放行尾;那个方向没有应用行 → 不动。源行被移空 → 从结果里去掉
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
 * 新顺序 = 工作副本里 layoutRow == L 那一行的包(没有 = 被移空)+ 该行里首页原本就没显示的包(未安装的,保持相对顺序)。
 * 名字、图标、行序全部照磁盘;首页不显示的行(空行、全是未安装)原样保留。
 */
internal fun mergeMove(disk: List<LayoutRow>, original: List<Row>, working: List<Row>): List<LayoutRow> =
    disk.mapIndexed { l, row ->
        val before = original.firstOrNull { it.kind == RowKind.APPS && it.layoutRow == l } ?: return@mapIndexed row
        val shown = before.apps.map { it.packageName }.toSet()
        val now = working.firstOrNull { it.kind == RowKind.APPS && it.layoutRow == l }?.apps?.map { it.packageName } ?: emptyList()
        row.copy(apps = (now + row.apps.filter { it !in shown }).distinct())
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
