package com.uniteduone.launcher

/** 卡片大小三档(B5-a:三个固定尺寸,不再是「每行几张」)。 */
enum class GtvCardSize { SMALL, MEDIUM, LARGE }

/**
 * gtv 线的全部几何,单位一律 dp(Float),**不含任何 Compose 类型**,便于 JVM 单测。
 * 数值出处:docs/research/2026-09-20-google-tv-launcherx-measurements.md(实测 launcherx 1.0.976298245)。
 * 与 [HomeLayout] 同形并存:main 线仍读 HomeLayout,本线只读这里。
 */
object GtvLayout {
    const val CONTENT_KEYLINE = 58f
    const val CARD_GAP = 20f
    const val HERO_HEIGHT = 192f
    const val TOP_BAR_TOP = 34f
    const val TOP_BAR_HEIGHT = 36f
    const val TOP_BAR_ICON = 32f
    const val TOP_BAR_ICON_GAP = 8f
    /** 行标题行盒高:实测 a11y (116,600)-(302,630) = 30 px = 15 dp。 */
    const val ROW_TITLE_LINE = 15f
    const val ROW_TITLE_TO_CARD = 12.5f
    /** 反推:125.5(实测行距)− 15 − 12.5 − 86.06(中档卡高)= 11.94,取整 12。 */
    const val ROW_GAP = 12f
    const val CARD_CORNER = 8f
    /** 焦点描边:画在布局框**外** FOCUS_OUTSET 处,粗 FOCUS_STROKE。不缩放。 */
    const val FOCUS_STROKE = 2f
    const val FOCUS_OUTSET = 5f
    const val CARD_TITLE_GAP = 4f
    const val CARD_TITLE_LINE = 16f

    /** 长按 / 齿轮菜单(GearMenu,Task 8):药丸尺寸,实测报告 §7,268×55 dp,全圆角(h/2)。 */
    const val MENU_ITEM_WIDTH = 268f
    const val MENU_ITEM_HEIGHT = 55f
    /** 药丸之间的纵向间距。报告没给这一项,按参考图 docs/screenshots/gtv/16-app-longpress-menu.png
     *  像素量测(两药丸间隙 y 355→384 px,该图 1:1 对应 320dpi 实机,/2 得 dp)≈ 14.5 dp,取整 16。 */
    const val MENU_ITEM_GAP = 16f
    /** 左侧 banner 与应用名之间的间距。同一张参考图量测(banner 底 y 469 → 名字顶 ≈508 px)≈ 19.5 dp,取整 20。 */
    const val MENU_BANNER_NAME_GAP = 20f

    fun cardWidth(size: GtvCardSize): Float = when (size) {
        GtvCardSize.SMALL -> 122f
        GtvCardSize.MEDIUM -> 153f
        GtvCardSize.LARGE -> 192f
    }

    fun cardHeight(size: GtvCardSize): Float = cardWidth(size) * 9f / 16f

    fun cardPitch(size: GtvCardSize): Float = cardWidth(size) + CARD_GAP

    /** 焦点卡钉在左基准线:行整体左移「索引 × pitch」。 */
    fun rowShiftX(focusedIndex: Int, size: GtvCardSize): Float =
        -focusedIndex.coerceAtLeast(0) * cardPitch(size)

    fun titleHeight(showTitles: Boolean): Float =
        if (showTitles) CARD_TITLE_GAP + CARD_TITLE_LINE else 0f

    fun rowPitch(size: GtvCardSize, showTitles: Boolean): Float =
        ROW_TITLE_LINE + ROW_TITLE_TO_CARD + cardHeight(size) + titleHeight(showTitles) + ROW_GAP

    fun rowShiftY(activeRow: Int, size: GtvCardSize, showTitles: Boolean): Float =
        -activeRow.coerceAtLeast(0) * rowPitch(size, showTitles)
}
