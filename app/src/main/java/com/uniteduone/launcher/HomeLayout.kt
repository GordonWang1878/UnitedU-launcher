package com.uniteduone.launcher

/**
 * M8 首页几何,单位一律 dp(Float),**不含任何 Compose 类型**,便于 JVM 单测。
 * 数值出处:spec §1.1(Google 网格:边距 58、间距 20、圆角 8、1.1 倍、3dp 描边)与 §2.1(锚点 = 屏高 × 2/3)。
 * `Theme.cardMetrics` 只是这里的 Dp 包装;HomeScreen 的纵向位移直接读这里。
 */
object HomeLayout {
    const val SIDE_PADDING = 58f
    const val CARD_SPACING = 20f
    const val CARD_CORNER = 8f
    const val FOCUS_SCALE = 1.1f
    const val FOCUS_BORDER = 3f
    const val ROW_GAP = 20f
    /** 行标题行高 = titleMedium 16sp 的行高 24。 */
    const val ROW_TITLE_LINE = 24f
    const val ROW_TITLE_GAP = 8f
    /** 卡片标题(开关开时):卡底到文字 4、行高 = bodySmall 12sp 的行高 16。 */
    const val CARD_TITLE_GAP = 4f
    const val CARD_TITLE_LINE = 16f
    /** scrim 顶边在锚点上方多少。 */
    const val SCRIM_LEAD = 60f
    /** hero 大字时钟块顶。 */
    const val HERO_TOP = 150f
    const val PILL_TOP = 28f

    fun span(screenW: Float = 960f): Float = screenW - 2f * SIDE_PADDING

    fun cardWidth(cardsPerRow: Int, screenW: Float = 960f): Float =
        (span(screenW) - CARD_SPACING * (cardsPerRow - 1)) / cardsPerRow

    fun cardHeight(cardsPerRow: Int, screenW: Float = 960f): Float = cardWidth(cardsPerRow, screenW) * 9f / 16f

    /** 行内上下留白:放大 10% 的溢出一半 + 描边,刚好放得下聚焦卡。 */
    fun rowVerticalPad(cardsPerRow: Int, screenW: Float = 960f): Float =
        cardHeight(cardsPerRow, screenW) * (FOCUS_SCALE - 1f) / 2f + FOCUS_BORDER

    fun titleHeight(showTitles: Boolean): Float = if (showTitles) CARD_TITLE_GAP + CARD_TITLE_LINE else 0f

    /** 行标题顶到下一行行标题顶。 */
    fun rowPitch(cardsPerRow: Int, showTitles: Boolean, screenW: Float = 960f): Float =
        ROW_TITLE_LINE + ROW_TITLE_GAP + 2f * rowVerticalPad(cardsPerRow, screenW) +
            cardHeight(cardsPerRow, screenW) + titleHeight(showTitles) + ROW_GAP

    /** 焦点行锚定:内容整块上移 activeRow 个行距(spec §2.2)。 */
    fun shift(activeRow: Int, cardsPerRow: Int, showTitles: Boolean, screenW: Float = 960f): Float =
        -activeRow.coerceAtLeast(0) * rowPitch(cardsPerRow, showTitles, screenW)

    /** hero 主体第 1 行起隐藏(spec §2.3)。 */
    fun heroAlpha(activeRow: Int): Float = if (activeRow <= 0) 1f else 0f

    fun anchorTop(screenH: Float): Float = screenH * 2f / 3f

    fun scrimHeight(screenH: Float): Float = screenH - anchorTop(screenH) + SCRIM_LEAD
}
