package com.uniteduone.launcher

import kotlin.math.abs

/*
 * **编辑桌面「货架」(R165)的纯逻辑**:货架模型、上下键的固定焦点顺序、层内胶囊 ↔ 卡片的最近映射、目标格夹取、
 * 动作后的落点、纵向位移、拿起时的方向箭头、架子的明暗。不依赖 Android,单测 EditShelvesTest / EditShelvesLandingTest。
 *
 * **扩展点(计划 2:频道行)**——加频道时只动这几处,`EditScreen` 的账本与按键截获不用改:
 * - `Shelf` 加 `data class ChannelShelf(val row: Int, …) : Shelf`(行号仍 = layout.json 行号);
 * - `shelfLanes` 给它只出一条 `CHIPS`(海报预览不可聚焦,spec §2.1);
 * - `shelfChips` 给它 `UP` / `DOWN` / `DELETE`(删除不受「应用行多于 1 行」限制),需要时加 `REAUTHORIZE`;
 * - `NewRowChoice` 加 `CHANNEL`,`choiceFull` 按频道行数判;
 * - `clampSpot` 里 `when (shelves[shelf])` 是穷举的——新加子类型时编译器会逐处点名要处理的地方。
 */

/** 编辑页的一层。[AppShelf.row] = layout.json 的行号(= 本页 `rows` 的下标);[NewRowShelf] 永远是最后一层。 */
internal sealed interface Shelf {
    data class AppShelf(val row: Int, val icon: String, val apps: List<String>) : Shelf
    data object NewRowShelf : Shelf
}

/** 应用架子顶部的操作胶囊,从左到右就是这个顺序(spec §2.1)。 */
internal enum class ShelfChip { ADD_APP, ICON, UP, DOWN, DELETE }

/** 「新的一行」里的选择卡(计划 2 加 CHANNEL)。 */
internal enum class NewRowChoice { APP_ROW }

/** 一层里的哪一条:顶部胶囊、卡片(空架子 = 「添加应用」方块)、新的一行的选择卡。 */
internal enum class ShelfZone { CHIPS, CARDS, NEW }

/** 焦点账本里的一格(spec §2.2):第几层、哪一条、第几个。 */
internal data class ShelfSpot(val shelf: Int, val zone: ShelfZone, val index: Int)

/** 上下键逐格走的一条(一层的胶囊、一层的卡片、新的一行)。 */
internal data class ShelfLane(val shelf: Int, val zone: ShelfZone)

/** 顶部概况「N 行 · N 个应用」的两个数(计划 2 加频道数)。 */
internal data class EditCounts(val rows: Int, val apps: Int)

/** 看得见的那份行(R67,`visibleRows`)→ 货架:每行一层应用架子,最后一层「新的一行」。 */
internal fun shelvesOf(view: List<LayoutRow>): List<Shelf> =
    view.mapIndexed { i, r -> Shelf.AppShelf(i, r.icon, r.apps) } + Shelf.NewRowShelf

internal fun appShelfCount(shelves: List<Shelf>): Int = shelves.count { it is Shelf.AppShelf }

/** 最后一个内容层(「新的一行」之前那层)的下标;没有内容层 → -1。 */
private fun lastContentShelf(shelves: List<Shelf>): Int = shelves.indexOfLast { it !is Shelf.NewRowShelf }

/**
 * 第 [shelf] 层顶部画哪几颗胶囊(spec §2.1):添加应用、换图标恒有;上移只在不是第一层时;下移只在不是最后一个内容层时;
 * 删除只在应用行多于 [MIN_ROWS] 行时。「新的一行」与越界 → 空。
 */
internal fun shelfChips(shelves: List<Shelf>, shelf: Int): List<ShelfChip> = when (shelves.getOrNull(shelf)) {
    is Shelf.AppShelf -> buildList {
        add(ShelfChip.ADD_APP)
        add(ShelfChip.ICON)
        if (shelf > 0) add(ShelfChip.UP)
        if (shelf < lastContentShelf(shelves)) add(ShelfChip.DOWN)
        if (appShelfCount(shelves) > MIN_ROWS) add(ShelfChip.DELETE)
    }
    else -> emptyList()
}

/** 一条里有几格。卡片:应用数,空架子 1(「添加应用」方块);不存在的组合 0。 */
internal fun laneSize(shelves: List<Shelf>, shelf: Int, zone: ShelfZone): Int {
    val s = shelves.getOrNull(shelf) ?: return 0
    return when (zone) {
        ShelfZone.CHIPS -> shelfChips(shelves, shelf).size
        ShelfZone.CARDS -> if (s is Shelf.AppShelf) s.apps.size.coerceAtLeast(1) else 0
        ShelfZone.NEW -> if (s is Shelf.NewRowShelf) NewRowChoice.entries.size else 0
    }
}

/** 上下键的固定顺序(spec §2.2):第 1 层胶囊 → 第 1 层卡片 → 第 2 层胶囊 → … → 新的一行。 */
internal fun shelfLanes(shelves: List<Shelf>): List<ShelfLane> = shelves.flatMapIndexed { i, s ->
    when (s) {
        is Shelf.AppShelf -> listOf(ShelfLane(i, ShelfZone.CHIPS), ShelfLane(i, ShelfZone.CARDS))
        Shelf.NewRowShelf -> listOf(ShelfLane(i, ShelfZone.NEW))
    }
}.filter { laneSize(shelves, it.shelf, it.zone) > 0 }

/** [centers] 里水平中心离 [x] 最近的下标;一样近取靠左的;[x] 没量到或一个都没量到 → 0。 */
internal fun nearestByCenter(x: Float?, centers: List<Float?>): Int {
    if (x == null) return 0
    var best = 0
    var bestD = Float.MAX_VALUE
    centers.forEachIndexed { i, c ->
        if (c != null) {
            val d = abs(c - x)
            if (d < bestD) { bestD = d; best = i }
        }
    }
    return best
}

/**
 * 从 [from] 按上 / 下([down])落到哪一格:沿 [shelfLanes] 走一条,落点取那条里水平中心离 [from] 最近的一格
 * ([centerOf] 给每一格此刻的水平中心,没量到给 null)。到头 / [from] 不在任何一条上 → null(调用方吞掉这一下 = `Cancel`)。
 * spec 只写了「层内卡片 ↔ 胶囊按最近」;跨层(卡片 → 下一层胶囊、胶囊 → 上一层卡片、进出新的一行)同样按最近。
 */
internal fun verticalStep(
    shelves: List<Shelf>,
    from: ShelfSpot,
    down: Boolean,
    centerOf: (ShelfSpot) -> Float?,
): ShelfSpot? {
    val lanes = shelfLanes(shelves)
    val i = lanes.indexOf(ShelfLane(from.shelf, from.zone))
    if (i < 0) return null
    val to = lanes.getOrNull(if (down) i + 1 else i - 1) ?: return null
    val n = laneSize(shelves, to.shelf, to.zone)
    val centers = List(n) { centerOf(ShelfSpot(to.shelf, to.zone, it)) }
    return ShelfSpot(to.shelf, to.zone, nearestByCenter(centerOf(from), centers))
}

/**
 * 把一个可能已经过期的目标夹回此刻的货架里:层号、条、格号依次夹。**`NEW` 永远指向「新的一行」**(行数变了它跟着挪,
 * Review Focus 3);应用架子上不会有 `NEW`(→ `CARDS`);落到「新的一行」上的别的条 → `NEW`。
 */
internal fun clampSpot(shelves: List<Shelf>, spot: ShelfSpot): ShelfSpot {
    if (shelves.isEmpty()) return ShelfSpot(0, spot.zone, 0)
    val newRow = shelves.indexOfLast { it is Shelf.NewRowShelf }
    val shelf = if (spot.zone == ShelfZone.NEW && newRow >= 0) newRow else spot.shelf.coerceIn(0, shelves.lastIndex)
    val zone = when (shelves[shelf]) {
        is Shelf.AppShelf -> if (spot.zone == ShelfZone.NEW) ShelfZone.CARDS else spot.zone
        Shelf.NewRowShelf -> ShelfZone.NEW
    }
    val n = laneSize(shelves, shelf, zone)
    return ShelfSpot(shelf, zone, spot.index.coerceIn(0, (n - 1).coerceAtLeast(0)))
}

/** 这张选择卡是不是已满(spec §2.4:变暗、写「已满 5 行」、确定不响应,仍可聚焦)。 */
internal fun choiceFull(shelves: List<Shelf>, choice: NewRowChoice): Boolean = when (choice) {
    NewRowChoice.APP_ROW -> appShelfCount(shelves) >= MAX_ROWS
}

/** 顶部概况:行数 + 看得见的应用数。 */
internal fun editCounts(view: List<LayoutRow>): EditCounts = EditCounts(view.size, view.sumOf { it.apps.size })
