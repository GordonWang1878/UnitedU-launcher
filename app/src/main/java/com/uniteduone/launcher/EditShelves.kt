package com.uniteduone.launcher

import kotlin.math.abs

/*
 * **编辑桌面「货架」(R165)的纯逻辑**:货架模型、上下键的固定焦点顺序、层内胶囊 ↔ 卡片的最近映射、目标格夹取、
 * 动作后的落点、纵向位移、拿起时的方向箭头、架子的明暗。不依赖 Android,单测 EditShelvesTest / EditShelvesLandingTest。
 *
 * **扩展点(计划 2:频道行)**——加频道时只动这几处,`EditScreen` 的账本与按键截获不用改:
 * - (R164 已落地)`Shelf.ChannelShelf(row, ref, appLabel, state)`(行号仍 = layout.json 行号);
 * - `shelfLanes` 给它只出一条 `CHIPS`(海报预览不可聚焦,spec §2.1);
 * - `shelfChips` 给它 `UP` / `DOWN` / `DELETE`(删除不受「应用行多于 1 行」限制),需要时加 `REAUTHORIZE`;
 * - `NewRowChoice` 加 `CHANNEL`,`choiceFull` 按频道行数判;
 * - `clampSpot` 里 `when (shelves[shelf])` 是穷举的——新加子类型时编译器会逐处点名要处理的地方。
 */

/** 编辑页的一层。[AppShelf.row] = layout.json 的行号(= 本页 `rows` 的下标);[NewRowShelf] 永远是最后一层。 */
internal sealed interface Shelf {
    data class AppShelf(val row: Int, val icon: String, val apps: List<String>) : Shelf
    /** R164 / R165 §2.1:频道架子——顶部频道图标 +「应用名 · 频道名」+ 小标签「频道」+ 胶囊;下面不可聚焦的海报预览或状态文字。[row] = layout.json 行号。 */
    data class ChannelShelf(val row: Int, val ref: ChannelRef, val appLabel: String, val state: ChannelShelfState) : Shelf
    data object NewRowShelf : Shelf
}

/** 架子顶部的操作胶囊。左右顺序由 [shelfChips] 的 `buildList` 决定,不看枚举顺序([REAUTHORIZE] 只在频道架子上,R164)。 */
internal enum class ShelfChip { ADD_APP, ICON, UP, DOWN, DELETE, REAUTHORIZE }

/** 「新的一行」里的选择卡(计划 2 加 CHANNEL)。 */
internal enum class NewRowChoice { APP_ROW }

/** 一层里的哪一条:顶部胶囊、卡片(空架子 = 「添加应用」方块)、新的一行的选择卡。 */
internal enum class ShelfZone { CHIPS, CARDS, NEW }

/** 焦点账本里的一格(spec §2.2):第几层、哪一条、第几个。 */
internal data class ShelfSpot(val shelf: Int, val zone: ShelfZone, val index: Int)

/** 上下键逐格走的一条(一层的胶囊、一层的卡片、新的一行)。 */
internal data class ShelfLane(val shelf: Int, val zone: ShelfZone)

/** 顶部概况「N 行 · N 个应用 · N 个频道」的三个数(spec §2.1)。 */
internal data class EditCounts(val rows: Int, val apps: Int, val channels: Int = 0)

/** R164:频道架子此刻画什么。 */
internal sealed interface ChannelShelfState {
    data class Posters(val programs: List<Program>) : ChannelShelfState
    data object Empty : ChannelShelfState
    data object NeedsPermission : ChannelShelfState
}

/** 内容 → 架子状态;还没读到(null)按「暂无内容」画。 */
internal fun channelShelfState(c: ChannelContent?): ChannelShelfState = when (c) {
    is ChannelContent.Ready -> ChannelShelfState.Posters(c.programs)
    ChannelContent.NeedsPermission -> ChannelShelfState.NeedsPermission
    ChannelContent.Missing, null -> ChannelShelfState.Empty
}

/**
 * 看得见的那份行(R67,`visibleRows`)→ 货架:应用行一层应用架子,频道行一层频道架子(R164),最后一层「新的一行」。
 * 层号 = layout.json 行号(`visibleRows` 与 `rows` 逐行一一对应),所以 `swapRows` / `deleteRow` 直接拿层号当行号。
 * [channelLabels] / [channelContent] 来自进程级 `ChannelCache`(EditScreen 的 `shelvesFor` 统一传);缺省空表 = 用包名、按「暂无内容」。
 */
internal fun shelvesOf(
    view: List<LayoutRow>,
    channelLabels: Map<String, String> = emptyMap(),
    channelContent: Map<ChannelRef, ChannelContent> = emptyMap(),
): List<Shelf> = view.mapIndexed { i, r ->
    val ref = r.channel
    if (ref != null) Shelf.ChannelShelf(i, ref, channelLabels[ref.pkg] ?: ref.pkg, channelShelfState(channelContent[ref]))
    else Shelf.AppShelf(i, r.icon, r.apps)
} + Shelf.NewRowShelf

internal fun appShelfCount(shelves: List<Shelf>): Int = shelves.count { it is Shelf.AppShelf }

/** 最后一个内容层(「新的一行」之前那层)的下标;没有内容层 → -1。 */
private fun lastContentShelf(shelves: List<Shelf>): Int = shelves.indexOfLast { it !is Shelf.NewRowShelf }

/**
 * 第 [shelf] 层顶部画哪几颗胶囊(spec §2.1):添加应用、换图标恒有;上移只在不是第一层时;下移只在不是最后一个内容层时;
 * 删除只在应用行多于 [MIN_ROWS] 行时(只数应用架子,[appShelfCount])。
 * 频道架子:上移 / 下移同应用架子;删除永远有、不弹确认;没授权时最前面多一颗「重新授权」——那是这一层此刻最该按的一颗。
 * 「新的一行」与越界 → 空。
 */
internal fun shelfChips(shelves: List<Shelf>, shelf: Int): List<ShelfChip> = when (val s = shelves.getOrNull(shelf)) {
    is Shelf.AppShelf -> buildList {
        add(ShelfChip.ADD_APP)
        add(ShelfChip.ICON)
        if (shelf > 0) add(ShelfChip.UP)
        if (shelf < lastContentShelf(shelves)) add(ShelfChip.DOWN)
        if (appShelfCount(shelves) > MIN_ROWS) add(ShelfChip.DELETE)
    }
    is Shelf.ChannelShelf -> buildList {
        if (s.state == ChannelShelfState.NeedsPermission) add(ShelfChip.REAUTHORIZE)
        if (shelf > 0) add(ShelfChip.UP)
        if (shelf < lastContentShelf(shelves)) add(ShelfChip.DOWN)
        add(ShelfChip.DELETE)
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
        // 海报预览不可聚焦(spec §2.1):只有胶囊那一条
        is Shelf.ChannelShelf -> listOf(ShelfLane(i, ShelfZone.CHIPS))
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
        // 频道架子只有胶囊:授权回来「重新授权」那颗没了,目标 (层, CHIPS, i) 在下面按新胶囊表夹到同一位置
        is Shelf.ChannelShelf -> ShelfZone.CHIPS
        Shelf.NewRowShelf -> ShelfZone.NEW
    }
    val n = laneSize(shelves, shelf, zone)
    return ShelfSpot(shelf, zone, spot.index.coerceIn(0, (n - 1).coerceAtLeast(0)))
}

/** 这张选择卡是不是已满(spec §2.4:变暗、写「已满 5 行」、确定不响应,仍可聚焦)。 */
internal fun choiceFull(shelves: List<Shelf>, choice: NewRowChoice): Boolean = when (choice) {
    NewRowChoice.APP_ROW -> appShelfCount(shelves) >= MAX_ROWS
}

/** 顶部概况「N 行 · N 个应用 · N 个频道」(spec §2.1):行数(含频道行)+ 看得见的应用数 + 频道行数。 */
internal fun editCounts(view: List<LayoutRow>): EditCounts =
    EditCounts(view.size, view.sumOf { it.apps.size }, view.count { it.isChannel })

/**
 * 货架的几何、配色、动效常量(R165 §2.1 / §2.5)。数值逐项来自效果图 `docs/design/edit-redesign/shelf.css`、`c1.html`、`c2.html`
 * (画布 960 × 540 = dp)。字号不在这里——一律 `Type`(12 sp = `Type.CAPTION`,13 px 的行头取 `Type.BODY` 14,17 = `SECTION`,11 = `MICRO`)。
 */
internal object ShelfLayout {
    /** 架子左右留白、圆角、层间距。 */
    const val SIDE = 40f
    const val CORNER = 20f
    const val GAP = 14f
    /** 第一层架子不位移时的顶边(c1)。 */
    const val TOP = 84f
    /** 焦点线:焦点层的顶边对齐屏幕上方 1/3(540 / 3)。 */
    const val FOCUS_LINE = 180f
    /** 内容末尾(「新的一行」下面那行说明之后)的留白。 */
    const val BOTTOM_PAD = 24f
    /** 页头:左右边距、文字行的顶、渐隐遮罩高(c2:96 px,55% 处起变透明)。 */
    const val HEADER_LEFT = 58f
    const val HEADER_Y = 34f
    const val SCRIM_HEIGHT = 96f
    const val HINT_GAP = 12f
    /** 架子里的顶行:离顶 12、高 28、左 18 / 右 16;行图标 18、图标与字间距 8。 */
    const val HEADER_TOP = 12f
    const val HEADER_HEIGHT = 28f
    const val PAD_START = 18f
    const val PAD_END = 16f
    const val ICON = 18f
    const val ICON_GAP = 8f
    /** 卡片行:离架子顶 56(= 12 + 28 + 16),卡下留 15.375,应用架子总高 140。 */
    const val CARDS_TOP = 56f
    const val CARDS_BOTTOM = 15.375f
    const val APP_SHELF_HEIGHT = 140f
    const val CARD_GAP = 20f
    /** 操作胶囊(spec §2.5):28 高、14 圆角、左右内边距 12、间距 8、图标 15;聚焦放大 1.08;平时白 8% 底。 */
    const val CHIP_HEIGHT = 28f
    const val CHIP_CORNER = 14f
    const val CHIP_PAD_H = 12f
    const val CHIP_GAP = 8f
    const val CHIP_ICON = 15f
    const val CHIP_ICON_GAP = 5f
    const val CHIP_FOCUS_SCALE = 1.08f
    const val CHIP_IDLE_ALPHA = 0.08f
    const val CHIP_SHADOW = 6f
    /** 「新的一行」的选择卡(c2):离架子顶 52、300 × 110、圆角 16、内边距 20 / 16、间距 20;聚焦放大 1.05;平时白 7% 底;已满整卡 45%。 */
    const val CHOICE_TOP = 52f
    const val CHOICE_WIDTH = 300f
    const val CHOICE_HEIGHT = 110f
    const val CHOICE_CORNER = 16f
    const val CHOICE_PAD_H = 20f
    const val CHOICE_PAD_V = 16f
    const val CHOICE_GAP = 20f
    const val CHOICE_ICON = 28f
    const val CHOICE_IDLE_ALPHA = 0.07f
    const val CHOICE_FOCUS_SCALE = 1.05f
    const val CHOICE_SHADOW = 14f
    const val CHOICE_FULL_ALPHA = 0.45f
    /** 「新的一行」架子高 190(= 52 + 110 + 28),下面一行说明离架子 16。 */
    const val NEW_ROW_HEIGHT = 190f
    const val NEW_ROW_CAPTION_GAP = 16f
    /** 架子玻璃(spec §2.5):平时白 5.5% 底 + 白 7% 边;焦点层白 10% + 白 14% + 阴影;新的一行平时白 3%。 */
    const val BG_ALPHA = 0.055f
    const val BORDER_ALPHA = 0.07f
    const val BG_ALPHA_FOCUSED = 0.10f
    const val BORDER_ALPHA_FOCUSED = 0.14f
    const val NEW_ROW_BG_ALPHA = 0.03f
    const val BORDER = 1f
    /** 焦点层阴影(效果图 `0 20px 60px rgba(0,0,0,.45)`):黑 45%、向下 20、铺开 40。 */
    const val SHADOW_ALPHA = 0.45f
    const val SHADOW_SPREAD = 40f
    const val SHADOW_DY = 20f
    /** 非焦点层整层不透明度(效果图 .62)。 */
    const val IDLE_SHELF_ALPHA = 0.62f
    /** 整层淡化用的离屏层四边各撑大这么多(R129f:焦点卡的 60 dp 柔光画在架子外)。 */
    const val LAYER_PAD = GtvLayout.APP_FOCUS_GLOW_DP
    /** 拿起时的方向箭头:离卡边 12(在放大溢出之外)、三角半宽 5;抬起的阴影 12。 */
    const val ARROW_GAP = 12f
    const val ARROW_SIZE = 5f
    const val CARRY_SHADOW = 12f
    /** 换层(架子亮度 + 整页位移)200 ms、拿起 / 放下 150 ms,曲线 FastOutSlowIn(spec §2.5)。 */
    const val SHIFT_MS = 200
    const val LIFT_MS = 150
}

/** 落在第 [shelf] 层的 [chip] 那颗;那颗此刻不在(比如删除已不允许)→ 第一颗。 */
internal fun landingOnChip(shelves: List<Shelf>, shelf: Int, chip: ShelfChip): ShelfSpot =
    clampSpot(shelves, ShelfSpot(shelf, ShelfZone.CHIPS, shelfChips(shelves, shelf).indexOf(chip).coerceAtLeast(0)))

/** 落在第 [shelf] 层第 [col] 张卡(夹到这一层的卡片数;空架子 = 「添加应用」方块)。 */
internal fun landingOnCard(shelves: List<Shelf>, shelf: Int, col: Int): ShelfSpot =
    clampSpot(shelves, ShelfSpot(shelf, ShelfZone.CARDS, col))

/**
 * 上移 / 下移之后([shelves] 是换完的,[newShelf] 是这一层的新下标):跟着这一层走,落同一颗胶囊;那颗在新位置上没了
 * (移到第一层没有「上移」、移到最后一个内容层没有「下移」)→ 反方向那颗;再没有 →「换图标」(Review Focus 2)。
 */
internal fun landingAfterSwap(shelves: List<Shelf>, newShelf: Int, pressed: ShelfChip): ShelfSpot {
    val chips = shelfChips(shelves, newShelf)
    val opposite = when (pressed) {
        ShelfChip.UP -> ShelfChip.DOWN
        ShelfChip.DOWN -> ShelfChip.UP
        else -> pressed
    }
    val chip = listOf(pressed, opposite, ShelfChip.ICON).firstOrNull { it in chips } ?: ShelfChip.ADD_APP
    return landingOnChip(shelves, newShelf, chip)
}

/**
 * 删掉第 [deleted] 层之后([shelves] 是删完的):落上一层的**第一颗**胶囊(「添加应用」);删的是第一层落新的第一层。
 * spec 只写「落上一层的胶囊」——取第一颗而不是同位置的「删除」,免得连按两下确定连删两行(空行删除不弹确认)。
 */
internal fun landingAfterDelete(shelves: List<Shelf>, deleted: Int): ShelfSpot =
    clampSpot(shelves, ShelfSpot((deleted - 1).coerceAtLeast(0), ShelfZone.CHIPS, 0))

/**
 * 进页时的初始目标(铁律 5 的目标,数据到之前就要有):没有种子 → 第 1 层第 1 张;带着「换卡片图」回来的
 * (layout 行号, 包名)→ 那一层、按 layout 下标估的那一列(看得见的列号要等数据,由种子效果再精确定位一次)。
 * 层号第一帧就对,整页不会先画在第 1 层再滑到种子那层(Task 10 模拟器实测)。
 */
internal fun initialEditSpot(rows: List<LayoutRow>, seed: Pair<Int, String>?): ShelfSpot {
    if (seed == null || rows.isEmpty()) return ShelfSpot(0, ShelfZone.CARDS, 0)
    val r = seed.first.coerceIn(0, rows.lastIndex)
    return ShelfSpot(r, ShelfZone.CARDS, rows[r].apps.indexOf(seed.second).coerceAtLeast(0))
}

/**
 * 卡片条横向裁切的右边界(dp,相对这张卡的左缘;null = 不裁)。[distToShelfEnd] = 这张卡左缘到架子右边缘的距离。
 * 非焦点卡一律裁在架子右端;焦点卡([current])整张落进架子之后不裁(owner 裁定:柔光可以画出架子),
 * **还在滑入时照样裁**——否则横向换焦点的那 ~170 ms 里新焦点卡会整张画进架子外的 40 dp 留白、只被屏幕边缘截(Task 10 1× 录像)。
 */
internal fun shelfCardClipRight(distToShelfEnd: Float, cardW: Float, current: Boolean): Float? =
    if (current && distToShelfEnd >= cardW) null else distToShelfEnd

/** 加频道之后:落最后一个频道架子(就是新的那层)的第一颗胶囊(spec §2.2)。 */
internal fun landingAfterAppendChannel(shelves: List<Shelf>): ShelfSpot =
    clampSpot(shelves, ShelfSpot(shelves.indexOfLast { it is Shelf.ChannelShelf }.coerceAtLeast(0), ShelfZone.CHIPS, 0))

/** 编辑页海报预览的高(dp)= 编辑页应用卡高(中档 122 × 9 / 16,§2.1「高 68.6 dp」)。 */
internal const val SHELF_POSTER_HEIGHT = 68.625f

/** 海报预览宽(dp):首页卡宽([PosterAspect.widthDp],110 dp 高下)按 [SHELF_POSTER_HEIGHT] 等比缩。 */
internal fun shelfPosterWidthDp(aspect: PosterAspect): Float = aspect.widthDp * SHELF_POSTER_HEIGHT / ChannelRowLayout.CARD_HEIGHT

/** 新建应用行之后:落最后一个应用架子(就是新的那层)的「添加应用」方块。 */
internal fun landingAfterAppend(shelves: List<Shelf>): ShelfSpot =
    clampSpot(shelves, ShelfSpot(shelves.indexOfLast { it is Shelf.AppShelf }.coerceAtLeast(0), ShelfZone.CARDS, 0))

/**
 * 货架整块往上挪多少 px(≥ 0,spec §2.1):焦点层([focused])的顶边对齐焦点线 [focusLine],再夹到「内容末尾 + [bottomPad]
 * 刚好贴屏幕底边」为止。[heights] = 各层实测高度,[top] = 第一层不位移时的顶边,[gap] = 层间距,[viewport] = 屏高;
 * 视窗没量到(0)或没有层 → 0。
 */
internal fun shelfScroll(heights: List<Int>, gap: Int, focused: Int, top: Int, focusLine: Int, viewport: Int, bottomPad: Int): Int {
    if (heights.isEmpty() || viewport <= 0) return 0
    val f = focused.coerceIn(0, heights.lastIndex)
    val focusedTop = top + (0 until f).sumOf { heights[it] + gap }
    val contentBottom = top + heights.sum() + gap * (heights.size - 1) + bottomPad
    val maxShift = (contentBottom - viewport).coerceAtLeast(0)
    return (focusedTop - focusLine).coerceIn(0, maxShift)
}

/** 拿起时卡片四周画哪几个方向箭头:只画按下去真的会挪的方向(与 [moveInLayout] 同一判据;照 Google TV gtv-04)。 */
internal fun carryArrows(view: List<LayoutRow>, pos: MovePos): Set<MoveDir> =
    MoveDir.entries.filterTo(mutableSetOf()) { moveInLayout(view, pos, it).first !== view }

/** 整层不透明度:[focus] 0 = 非焦点层([ShelfLayout.IDLE_SHELF_ALPHA]),1 = 焦点层。只在绘制阶段读。 */
internal fun shelfAlpha(focus: Float): Float {
    val t = focus.coerceIn(0f, 1f)
    return ShelfLayout.IDLE_SHELF_ALPHA + (1f - ShelfLayout.IDLE_SHELF_ALPHA) * t
}

/** 一层玻璃的底 / 边 / 阴影透明度(白 / 白 / 黑),按焦点系数插值。 */
internal data class ShelfLook(val bg: Float, val border: Float, val shadow: Float)

internal fun shelfLook(focus: Float, dashed: Boolean): ShelfLook {
    val t = focus.coerceIn(0f, 1f)
    val bgIdle = if (dashed) ShelfLayout.NEW_ROW_BG_ALPHA else ShelfLayout.BG_ALPHA
    return ShelfLook(
        bg = bgIdle + (ShelfLayout.BG_ALPHA_FOCUSED - bgIdle) * t,
        border = ShelfLayout.BORDER_ALPHA + (ShelfLayout.BORDER_ALPHA_FOCUSED - ShelfLayout.BORDER_ALPHA) * t,
        shadow = ShelfLayout.SHADOW_ALPHA * t,
    )
}

/** 页头右边的按键提示是哪一组:平时(c1)、焦点在新的一行(c2)、拿起中。 */
internal enum class EditHintSet { BROWSE, NEW_ROW, CARRY }

internal fun editHintSet(zone: ShelfZone?, carrying: Boolean): EditHintSet = when {
    carrying -> EditHintSet.CARRY
    zone == ShelfZone.NEW -> EditHintSet.NEW_ROW
    else -> EditHintSet.BROWSE
}
