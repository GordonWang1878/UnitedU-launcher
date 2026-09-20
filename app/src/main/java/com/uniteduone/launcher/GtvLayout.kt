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
    /** Fix round 1(R15,2026-09-20):**15 dp 是 Google 用 Latin 文本(`Top picks for you`)量出来的
     *  值,对中文不成立,不要改回去。** 原始推导是 a11y (116,600)-(302,630) = 30 px = 15 dp——但那是
     *  英文单行的紧凑行高;CJK 字形在同样 16sp 字号下需要明显更高的行盒。装机实测(`onTextLayout` 探针,
     *  `lineHeight` 设为 `Unspecified` 让 Compose 按实际渲染字体——中文回落到系统 CJK 字体——算自然
     *  行高):「视频」「直播」「音乐与播客」「更多应用」四个标题全部量出 `naturalHeightPx = 46`
     *  (= 23 dp,density 2.0),零方差。现改为 23 dp,并且 `CategoryRow` 的标题 `TextStyle` 也显式把
     *  `lineHeight` 设成这个值(不再继承 `titleMedium` 的 Material3 默认 24sp——那个默认值本身够用,
     *  裁切是容器被压到 15dp 造成的,见 `HomeScreen.kt` 里 `CategoryRow` 的注释)。 */
    const val ROW_TITLE_LINE = 23f
    const val ROW_TITLE_TO_CARD = 12.5f
    /** Fix round 1(R15,2026-09-20):**125.5 dp 同样是 Google 用 Latin 量出来的行距,对中文标题不
     *  成立,不要试图凑回这个数。** 沿用它会把 `ROW_GAP` 推到约 −10dp(23+12.5+14+86.06−125.5≈−10),
     *  而 `rowVerticalPad`(上下各 7dp)是货真价实要留给外扩焦点描边的空间——那么负的 `ROW_GAP` 会让
     *  行 N 的焦点描边直接压在行 N+1 的标题字形上。**改为放弃凑 125.5,`ROW_GAP` 改取一个有真实、非负
     *  余量的值。** 这里的「余量」定义是:`ROW_GAP` 的值本身,就是「行 N 的卡片行(含它自己下方预留
     *  给描边的 7dp)结束」到「行 N+1 标题行盒开始」之间的物理间距——因为 `CategoryRow` 的标题行盒
     *  紧贴在 `Column` 顶部、前面不再有别的 padding。取 **8 dp**:比行内自己的 `ROW_TITLE_TO_CARD`
     *  (12.5dp)略窄,让一行标题在视觉上更贴近它自己那一行的卡片、不与上一行混淆,同时明显大于
     *  `FOCUS_STROKE`(2dp),保证聚焦描边与下一行标题之间总有可见的黑色间隙,不会贴到一起。
     *  装机验证见 `task-9b-report.md` Fix round 1 一节。 */
    const val ROW_GAP = 8f
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

    /** Task 9b:补上焦点描边留白项(`CategoryRow` 的卡片行上下各留 `FOCUS_OUTSET + FOCUS_STROKE`,
     *  见 `Theme.gtvCardMetrics.rowVerticalPad`),此前公式没有这一项,是每行 26.5dp 纵向漂移的
     *  四个来源之一。
     *
     *  **Fix round 1(R15):中档、不显示标题时的返回值不再是 125.5——那是 Google 用 Latin 标题量出来的
     *  行距,`ROW_TITLE_LINE`/`ROW_GAP` 已经为了不裁切中文字形改成 CJK 实测值,现在是 143.5625。
     *  这不是需要修的偏差,是同一个公式在换了正确输入之后的正确结果;不要为了凑回 125.5 而改动
     *  `ROW_TITLE_LINE`/`ROW_GAP`,见两个常量各自的 KDoc。** */
    fun rowPitch(size: GtvCardSize, showTitles: Boolean): Float =
        ROW_TITLE_LINE + ROW_TITLE_TO_CARD + 2f * (FOCUS_OUTSET + FOCUS_STROKE) +
            cardHeight(size) + titleHeight(showTitles) + ROW_GAP

    fun rowShiftY(activeRow: Int, size: GtvCardSize, showTitles: Boolean): Float =
        -activeRow.coerceAtLeast(0) * rowPitch(size, showTitles)
}
