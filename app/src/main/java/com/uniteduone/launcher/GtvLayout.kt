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
    /** **内容卡**(content card,16:9 无边界推荐流那种)专用的焦点描边几何:画在布局框**外**
     *  FOCUS_OUTSET 处,粗 FOCUS_STROKE,不缩放。owner 反馈 Round 4(2026-09-21)裁定「我们的
     *  应用行是 app,不是 content——Google 对 app tile 的处理是缩放,不是这种静态外扩描边」之后,
     *  gtv 线首页已经没有任何卡片走这套画法(`AppCard`/`AddCard`/`MissingCard` 全部改用
     *  [APP_FOCUS_SCALE] 一族的 app 处理,见 `GtvFocusStroke.gtvAppFocusFrame`)——但这两个常量
     *  **没有变成死代码**,仍在两处活着:①`RowIconPicker` 的行图标格子(小网格图标,不是
     *  app,继续用这套画法,理由见该文件);②`gtvAppFocusFrame` 里 `moving`(首页原地移动态)
     *  分支——被搬的那张卡的高亮描边是 UnitedU 自己的交互反馈,Google 没有对应物,不跟着
     *  app 聚焦一起缩放,沿用这套固定外扩几何。`rowVerticalPad`
     *  (`Theme.gtvCardMetrics`)的留白量也仍然读这两个常量,`rowPitch` 因此不受本轮影响
     *  (owner 反馈 Round 4 明确要求不改 rowPitch)。 */
    const val FOCUS_STROKE = 2f
    const val FOCUS_OUTSET = 5f
    const val CARD_TITLE_GAP = 4f
    /** Fix 1(owner 反馈 R2,2026-09-20,R15 的同一种病第二次发作):**16 dp 是 `Theme.gtvCardMetrics`
     *  把卡片标题字号从 Google 的 Latin 量测抬到 14sp 时沿用的旧容器高,对 CJK 不成立,不要改回去。**
     *  真机(owner 的「云视听极光」「银河奇异果」)上逐行像素扫描:标题墨迹在 y=470→493 之间从
     *  47→46→**0**、没有渐变收尾——硬裁,不是渐变到底。
     *
     *  装机实测(同 R15 的 `onTextLayout` 探针,`lineHeight` 留 `Unspecified` 让 Compose 按实际渲染
     *  字体——中文回落系统 CJK 字体——算自然行高;探针必须放在**没有** `.height()` 约束的节点上,
     *  放在 `AppCard` 原来那个已经带 `.height(metrics.titleLine)` 的 `BasicText` 上量到的只是约束值
     *  本身,不是自然行高——这是第一次量到 32px≈16dp「零裁切」假象的原因,松开约束后才量到真值):
     *  `unitedu-gtv` AVD(1920×1080/320dpi,density 2.0)上「云视听极光」「银河奇异果」两个标题在
     *  14sp 下全部量出 `naturalHeightPx = 40`(= 20 dp,零方差,连量两轮一致);对照组「Play Store」
     *  (Latin)同字号量出 35px——与 R15 同一个结论:CJK 在同样字号下需要比 Latin 更高的行盒。
     *  现改为 20 dp,`AppCard` 的卡片标题 `TextStyle` 也显式把 `lineHeight` 设成
     *  `metrics.titleLine`(不是这个常量本身——`AppCard.kt` 对 main 线 / gtv 线都通用,只读
     *  `CardMetrics`,不直接读 `GtvLayout`,详见该文件),消除「容器高度」与「文字行高」分别改动
     *  导致再次漂移的可能。`titleHeight(true)` 与依赖它的 `rowPitch(size, true)` 会跟着变
     *  4dp——这是显示标题时行间距该有的样子,不是需要另外吸收的偏差(`GtvLayoutTest` 新增的
     *  `showTitles = true` 断言直接编码这条不变量)。装机复核见
     *  `.superpowers/sdd/2026-09-20-gtv-line/owner-feedback-fix-report.md`「Round 2 · Fix 1」与
     *  `docs/screenshots/gtv-owner-fix1-card-title-{clipped,fixed}-*.png` 的裁切前后对照。 */
    const val CARD_TITLE_LINE = 20f
    /** 行标题图标与文字之间的间距(CategoryRow)。Fix 4(终审 2026-09-20)从字面量搬进来,数值不变。 */
    const val ROW_TITLE_ICON_GAP = 8f

    /** 长按 / 齿轮菜单(GearMenu,Task 8):药丸尺寸,实测报告 §7,268×55 dp,全圆角(h/2)。 */
    const val MENU_ITEM_WIDTH = 268f
    const val MENU_ITEM_HEIGHT = 55f
    /** 药丸之间的纵向间距。报告没给这一项,按参考图 docs/screenshots/gtv/16-app-longpress-menu.png
     *  像素量测(两药丸间隙 y 355→384 px,该图 1:1 对应 320dpi 实机,/2 得 dp)≈ 14.5 dp,取整 16。 */
    const val MENU_ITEM_GAP = 16f
    /** 左侧 banner 与应用名之间的间距。同一张参考图量测(banner 底 y 469 → 名字顶 ≈508 px)≈ 19.5 dp,取整 20。 */
    const val MENU_BANNER_NAME_GAP = 20f
    /** 药丸左右内边距(spec §2.3「菜单项 16sp」附近)。Fix 4 从字面量搬进来,数值不变。 */
    const val MENU_ITEM_PADDING_H = 24f
    /** 菜单项文字字号(spec §2.3)。 */
    const val MENU_ITEM_TEXT = 16f
    /** Ruling R17(终审 2026-09-20):齿轮菜单(不含长按卡片菜单)恢复第二行说明文字,字号比标题小一档、
     *  颜色更淡(见 GearMenu.MenuPill 的 showHint 分支),视觉上明确从属于标题。 */
    const val MENU_ITEM_HINT_TEXT = 12f
    /** 标题行与说明行之间的间距。 */
    const val MENU_ITEM_HINT_GAP = 2f
    /** 两行文字时药丸的上下内边距(单行时数学上不改变居中位置,见 GearMenu.MenuPill 的推导注释)。 */
    const val MENU_ITEM_PADDING_V = 10f
    /** 齿轮菜单左半 banner 应用名的字号 + 字距(MenuBanner)。 */
    const val MENU_BANNER_NAME_TEXT = 16f
    const val MENU_BANNER_NAME_LETTER_SPACING = 1f
    /** 顶栏时钟 + 字标的字号(spec §2.3)。 */
    const val TOP_BAR_CLOCK_TEXT = 20f

    /**
     * Fix 3(owner 反馈 R2,2026-09-20):「目前 UI 交互没有任何动画……焦点一下子跳到这、一下子跳到
     * 那」。根因是 decision B1 去掉聚焦缩放之后,`gtvFocusStroke` 的描边与 `GearMenu`/`GtvTopBar`
     * 的填色焦点都是瞬间切换(布尔值直接门控 `drawBehind`/`background`,零动画),丢了缩放曾经
     * 提供的唯一连续性提示。
     *
     * **owner 反馈 Round 4(2026-09-21)替换了这两个数字的来源**:Round 2 写的 150/120ms 是
     * "未测量占位值"(见本文件历史版本/report),真值已从旧版 launcherx APK
     * (1.0.595789376,资源名未混淆)读出——`animator/card_focus`、`animator/card_unfocus`
     * 两个 `ObjectAnimator` 的 `duration` 都引用 `@integer/default_focused_animation_duration_ms
     * = 150`,进焦出焦**对称同为 150ms**,不是 150/120 的非对称值。**这不再是占位值,不要再加
     * "未测量"字样**——真的又量到更精确的数字才改,不要凭直觉往回调。
     *
     * 用在:app 卡片的缩放 + 描边(`GtvFocusStroke.gtvAppFocusFrame`,见 [APP_FOCUS_SCALE] 一族);
     * `gtvFocusStroke` 覆盖的另外两处内容卡式描边(`RowIconPicker`、`gtvAppFocusFrame` 的
     * `moving` 分支)延续复用同一对时长——`card_focus`/`_unfocus` 是 Google 对「app 卡片」的量测,
     * 这两处不是严格意义上的 app 卡片聚焦,只是为了整条线的焦点淡入淡出手感统一而借用同一个数字,
     * 不是又找到了各自的独立测量,如实记录不夸大。`GearMenu.MenuPill` 同理复用(见该文件调用点
     * 的注释)。曲线用 `Theme.AppFocusEasing`(同一份 APK 引用的 `AccelerateDecelerateInterpolator`,
     * 不是 `Theme.MotionEasing` 那条给「位置动画」用的减速曲线)。
     */
    const val FOCUS_FADE_IN_MS = 150
    const val FOCUS_FADE_OUT_MS = 150

    /**
     * owner 反馈 Round 4:Google 对 **app tile**(不是 content card)的聚焦处理——放大,不是外扩
     * 静态描边。旧版本(1.0.595789376)`fraction/app_card_focused_scale = 1.14`;但 controller
     * 在**本项目实际对照的目标版本**(1.0.976298245)上装机像素量测聚焦态应用图块
     * 152 → 168 px = **1.105×**——两代版本数值不同,以目标版本的实测为准,1.14 只作为旧版记录,
     * 不要把它当成现在该用的数字。 */
    const val APP_FOCUS_SCALE = 1.105f
    /** app tile 聚焦描边与**缩放后**边缘之间的间隙(dp)。controller 在目标版本(1.0.976298245)
     *  上装机像素量测得出,不是命名资源(Google 没有给这段间隙单独取名字)。 */
    const val APP_FOCUS_GAP = 2f
    /** app tile 聚焦描边本身的宽度(dp)。`dimen/card_focused_frame_outer_stroke_width = 2dp`——
     *  与内容卡的 [FOCUS_STROKE] 数值恰好相同,但这是两个分别命名的 Google 资源(content card
     *  与 app tile 各自的边框宽度只是刚好都是 2dp),不合并成一个常量,避免以后其中一个改了
     *  而误伤另一个。 */
    const val APP_FOCUS_STROKE = 2f

    /** app tile 聚焦时的视觉溢出量(缩放增量的一半 + 描边间隙 + 描边本身),给定卡片某一边的
     *  未缩放长度。纯几何,不含 Compose 类型,方便单测验证「聚焦时会不会碰到下一行标题」
     *  「行尾右缘会不会被屏幕边缘裁描边」这类不变量(owner 反馈 Round 4 §5)。 */
    fun appFocusOverflow(dimension: Float): Float =
        dimension * (APP_FOCUS_SCALE - 1f) / 2f + APP_FOCUS_GAP + APP_FOCUS_STROKE

    /** 顶栏图标按钮的填色淡入 / 淡出时长(owner 反馈 Round 4):`integer
     *  /top_nav_animation_duration_focus = 100`、`_unfocus = 200`,与 app 卡片的
     *  [FOCUS_FADE_IN_MS]/[FOCUS_FADE_OUT_MS] 是两组不同的 Google 资源,进出也不对称
     *  (先快进、后慢出),不要合并成一组常量。 */
    const val TOP_NAV_FADE_IN_MS = 100
    const val TOP_NAV_FADE_OUT_MS = 200

    fun cardWidth(size: GtvCardSize): Float = when (size) {
        GtvCardSize.SMALL -> 122f
        GtvCardSize.MEDIUM -> 153f
        GtvCardSize.LARGE -> 192f
    }

    fun cardHeight(size: GtvCardSize): Float = cardWidth(size) * 9f / 16f

    fun cardPitch(size: GtvCardSize): Float = cardWidth(size) + CARD_GAP

    /**
     * Ruling R20(终审 2026-09-20,owner 真机走查后推翻):**"焦点卡永远钉在左基准线,整行按
     * 索引 × pitch 平移"这条规则本身的测量没有错(实测 launcherx:沿 `Top picks for you` 行
     * 按右键 7 次,焦点卡左缘恒为 x=116px=58dp,见
     * `docs/research/2026-09-20-google-tv-launcherx-measurements.md` §8),错的是照搬它的前提——
     * **不要因为这个函数曾经就是这么写的,就把公式改回去。**
     *
     * Google 的内容行是无边界的推荐流(`Top picks for you`),行天然比屏幕宽,"焦点卡永远最左、
     * 右边永远还有更多"这个假设对它成立。我们的行是有限的应用列表,常见 5 张卡:MEDIUM 档
     * 5 张卡只占 845dp(5×153 + 4×20),这台机型屏宽 960dp,连左右两条 58dp 基准线一起量都
     * 刚好放得下——一整行本来就不需要移动。按 Google 规则从第一次按右键起就整行左移一个
     * pitch(173dp),会把第 1 张卡推出屏幕左侧,右边空出约 230dp 的死白,真机走查看到的就是
     * 这个样子。
     *
     * 现在的规则改回 pre-Task-7(commit 7abf015 之前,`git show 7abf015` 可见原型)的做法:
     * **焦点卡完全可见时行不动;只在焦点卡的右缘会超出屏幕右侧可视区域时,才左移刚好这么多、
     * 一点不多。** 右侧可视区域同样以 [CONTENT_KEYLINE] 为界(与左基准线对称)。超出屏幕右缘的
     * 卡仍然不砍宽度——见 `HomeScreen.kt` 里 `CategoryRow` 的 `Row` 上那条
     * `wrapContentWidth(unbounded)` 的注释,量出 0 宽的卡永远聚焦不到——继续靠它 + 屏幕本身的
     * 绘制裁切自然露出一截,行尾 peeking 效果不受影响。
     *
     * [screenWidthDp] 由调用方传入(`CategoryRow` 读 `LocalConfiguration.current.screenWidthDp`)——
     * 这个函数本身依然不含任何 Compose 类型,继续可以纯 JVM 单测(见 `GtvLayoutTest`)。
     *
     * **owner 反馈 Round 4(2026-09-21)补丁,§5**:`focusRight` 现在加了一份 [appFocusOverflow]——
     * app 卡片聚焦时会缩放 [APP_FOCUS_SCALE] 倍并外扩描边(`gtvAppFocusFrame`),视觉右缘比
     * 布局右缘更靠右;这个判断原本只看布局右缘,会在「布局右缘刚好没超、但缩放 + 描边之后的
     * 视觉右缘已经超出屏幕」时误判成不需要挪行,结果最右那张完全可见的卡的描边被屏幕边缘裁掉。
     * 不改 `rowPitch`——溢出预算的验证见 `GtvLayoutTest`「app 卡片聚焦溢出」一节与
     * owner-feedback-fix-report.md「Round 4 §5」。
     */
    fun rowShiftX(focusedIndex: Int, size: GtvCardSize, screenWidthDp: Float): Float {
        val focused = focusedIndex.coerceAtLeast(0)
        // 焦点卡右缘的位置,按行尚未平移时的自然布局算(与 pre-Task-7 的 focusRight 同一推导,
        // 只是把 Theme.SidePadding / metrics.cardWidth / metrics.cardSpacing 换成这里的
        // CONTENT_KEYLINE / cardWidth(size) / CARD_GAP),再加上 Round 4 的缩放 + 描边视觉溢出。
        val focusRight = CONTENT_KEYLINE + cardWidth(size) * (focused + 1) + CARD_GAP * focused +
            appFocusOverflow(cardWidth(size))
        // 期望的右侧留白与左基准线对称,同样取 CONTENT_KEYLINE;超出这条线才移动。
        val overRight = focusRight + CONTENT_KEYLINE - screenWidthDp
        return if (overRight > 0f) -overRight else 0f
    }

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
