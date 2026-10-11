package com.uniteduone.launcher

/**
 * R164:首页频道行的纵向几何(dp)。数值出处 spec `2026-10-08-channel-rows-design.md` §4;字号走 `Type`
 * (行头 `Type.section` 17 sp / 行高 22,卡下标题 `Type.body` 14 sp / 21,元数据 `Type.caption` 12 sp / 16),
 * 行盒高就是那三个 lineHeight——改 Type 的行高要连这里一起改。HomeChannelRow.kt 照这些数画,单测 HomeVerticalTest 钉住。
 *
 * 一行的布局块(从上到下):上留白 [GtvLayout.ROW_CARD_TOP] → 行头 [HEADER_LINE] → [headerGap](= 聚焦溢出,放大 + 描边
 * 不压行头)→ 卡 [CARD_HEIGHT] → 卡下 [infoHeight](= 聚焦溢出 + 标题行 + 元数据行;非焦点行同样占位)→ 下留白 ROW_CARD_TOP;
 * 行距 [GtvLayout.ROW_GAP] 由外层 Column 的 spacedBy 给。
 */
internal object ChannelRowLayout {
    const val CARD_HEIGHT = 110f
    const val HEADER_LINE = 22f
    /** 非焦点行的行头不透明度(spec §4:白 60%;焦点行 100%)。 */
    const val HEADER_IDLE_ALPHA = 0.6f
    const val INFO_TITLE_LINE = 21f
    const val INFO_META_LINE = 16f
    fun headerGap(): Float = GtvLayout.appFocusOverflow(CARD_HEIGHT)
    fun infoGap(): Float = GtvLayout.appFocusOverflow(CARD_HEIGHT)
    fun infoHeight(): Float = infoGap() + INFO_TITLE_LINE + INFO_META_LINE
    /** 行布局块顶 → 卡顶。 */
    fun cardTop(): Float = GtvLayout.ROW_CARD_TOP + HEADER_LINE + headerGap()
}

/**
 * 一行的纵向几何:[pitch] = 布局块高 + 行距;[cardTop] = 块顶 → 卡顶;[visibleBelow] = 卡顶 → 「需要可见」的下沿
 * (应用行:卡 + 聚焦溢出 + 卡片标题;频道行:卡 + 卡下两行)。
 */
internal data class RowGeom(val pitch: Float, val cardTop: Float, val visibleBelow: Float)

internal fun appRowGeom(size: GtvCardSize, showTitles: Boolean): RowGeom = RowGeom(
    pitch = GtvLayout.rowPitch(size, showTitles),
    cardTop = GtvLayout.ROW_CARD_TOP,
    visibleBelow = GtvLayout.cardHeight(size) + GtvLayout.appFocusOverflow(GtvLayout.cardHeight(size)) +
        GtvLayout.titleHeight(size, showTitles),
)

internal fun channelRowGeom(): RowGeom = RowGeom(
    pitch = 2f * GtvLayout.ROW_CARD_TOP + ChannelRowLayout.HEADER_LINE + ChannelRowLayout.headerGap() +
        ChannelRowLayout.CARD_HEIGHT + ChannelRowLayout.infoHeight() + GtvLayout.ROW_GAP,
    cardTop = ChannelRowLayout.cardTop(),
    visibleBelow = ChannelRowLayout.CARD_HEIGHT + ChannelRowLayout.infoHeight(),
)

/**
 * R52 焦点线的逐行版本(R164)。每行有自己的焦点线([focusLine]:让这一行的「需要可见」下沿离屏底 [GtvLayout.HOME_BOTTOM_MARGIN]),
 * 行块按各自 pitch 累计排开;静止(焦点在顶栏或行 0)时行 0 卡顶 = 行 0 的焦点线;焦点在行 n 时整页位移 [shiftY](n),
 * 让行 n 的卡顶落在**行 n 自己的**焦点线上。全是应用行时 = `GtvLayout.rowsTop / restCardTop / rowShiftY`,逐像素相同
 * (HomeVerticalTest 钉住)。[fallback] 给「一行都没有」时用(空桌面,与改前一样按应用行算)。
 */
internal class HomeVertical(geoms: List<RowGeom>, private val screenHeightDp: Float, fallback: RowGeom) {
    private val g: List<RowGeom> = geoms.ifEmpty { listOf(fallback) }
    private val blockTops = FloatArray(g.size).also { tops ->
        var acc = 0f
        for (i in g.indices) { tops[i] = acc; acc += g[i].pitch }
    }
    private fun at(row: Int) = row.coerceIn(0, g.lastIndex)

    fun focusLine(row: Int): Float = screenHeightDp - GtvLayout.HOME_BOTTOM_MARGIN - g[at(row)].visibleBelow

    /** 装着全部行的 Column 的 `padding(top)`(静止态行 0 布局块顶)。 */
    val rowsTop: Float = focusLine(0) - g[0].cardTop

    fun restBlockTop(row: Int): Float = rowsTop + blockTops[at(row)]

    fun restCardTop(row: Int): Float = restBlockTop(row) + g[at(row)].cardTop

    /** 焦点在 [activeRow] 时的整页位移(dp,≤ 0);负值(顶栏)= 0。 */
    fun shiftY(activeRow: Int): Float {
        if (activeRow <= 0) return focusLine(0) - restCardTop(0)
        val r = at(activeRow)
        return focusLine(r) - restCardTop(r)
    }

    /**
     * 壁纸逐行压暗([GtvLayout.WALLPAPER_DIM_PER_ROW])的逐行累计版本,MainActivity 的壁纸层用它替掉 `homeWallpaperAlpha(shift, rowPitch)`:
     * |[shiftDp]| 落在哪两行的静止位移(-[shiftY])之间,就在这两行的静止 alpha(都取 [GtvLayout.wallpaperAlpha])之间线性插值;
     * 越过最后一行(弹簧过冲)按最后一行的 pitch 外推。全是应用行时 -shiftY(n) = n × rowPitch,与改前逐值相同(HomeVerticalTest)。
     */
    fun wallpaperAlpha(shiftDp: Float, perRow: Boolean = GtvLayout.WALLPAPER_DIM_PER_ROW): Float {
        if (!perRow) return GtvLayout.wallpaperAlpha(shiftDp)
        val d = kotlin.math.abs(shiftDp)
        fun lerp(s0: Float, s1: Float): Float {
            val a0 = GtvLayout.wallpaperAlpha(s0)
            val a1 = GtvLayout.wallpaperAlpha(s1)
            return if (s1 <= s0) a0 else a0 + (a1 - a0) * ((d - s0) / (s1 - s0))
        }
        for (i in 0 until g.lastIndex) {
            val s1 = -shiftY(i + 1)
            if (d < s1) return lerp(-shiftY(i), s1)
        }
        val sLast = -shiftY(g.lastIndex)
        val p = g.last().pitch
        if (p <= 0f) return GtvLayout.wallpaperAlpha(d)
        val n = kotlin.math.floor(((d - sLast) / p).coerceAtLeast(0f))
        return lerp(sLast + n * p, sLast + (n + 1f) * p)
    }
}
