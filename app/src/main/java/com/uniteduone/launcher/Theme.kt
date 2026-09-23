package com.uniteduone.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 一档卡片布局的尺寸(Dp)。main 线由 [HomeLayout] 推导([cardMetrics]),gtv 线由 [GtvLayout]
 *  推导([gtvCardMetrics]);这里只做两条线共用的单位包装,不要在这里写任何数字。 */
data class CardMetrics(
    val cardWidth: Dp,
    val cardHeight: Dp,
    val cardCorner: Dp,
    val cardSpacing: Dp,
    /** 行内上下留白。main 线含义是「放大 10% 的溢出一半 + 描边」(HomeLayout.rowVerticalPad,
     *  焦点会缩放的旧画法留下的公式);gtv 线不缩放,含义是「焦点描边外扩留白」
     *  (GtvLayout.FOCUS_OUTSET + FOCUS_STROKE)。两条线来源不同,字段共用。 */
    val rowVerticalPad: Dp,
    val titleGap: Dp,
    val titleLine: Dp,
    val titleSize: TextUnit,
)

/** 全部视觉常量集中在这里,对应 docs/DESIGN-custom-launcher.md §4 的规格表。 */
object Theme {
    val Background = Color(0xFF000000)

    /**
     * Google Sans Flex:`google/fonts` 仓库 `ofl/googlesansflex/`,SIL Open Font License 1.1,
     * 版权行无 Reserved Font Name,随软件打包分发合法(2026-09-20 核实)。
     * 变量字体,三个字重全部由 `wght` 轴给出 —— 不注册轴就会静默回落到 400,
     * 与 DM Sans 那次「只注册 Normal/Bold 导致 Medium 回落」是同一个坑。
     * **中文不受影响**:此字体无 CJK 子集,中文照旧回落系统 Noto Sans CJK。
     */
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    val Sans = FontFamily(
        Font(R.font.google_sans_flex, FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.google_sans_flex, FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.google_sans_flex, FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )

    /** 香槟白:Projectivy 里是金格 (1,5) 亮度 +80,取到的近似色。
     *  **= 金预设的 highlight 原值**(ThemePresets.kt)。2026-09-16 起界面代码不再直接引它——
     *  高亮一律读 `LocalThemeColors.current.highlight`(跟着所选预设 / 壁纸主色走);这里只留作标定记录。 */
    val Champagne = Color(0xFFFFF5DC)

    /** 香槟金:复审取参考图齿轮前 1% 像素的中位色 #BEA438、去模糊后约 #C0A73A;
     *  原来的 #D9B970 蓝通道 112,参考只有 56 —— 偏白偏冷了一档。
     *  **= 金预设的 accent 原值**;同上,界面代码读 `LocalThemeColors.current.accent`,这里只留作标定记录。 */
    val ChampagneGold = Color(0xFFC0A73A)

    val RowTitle = Color(0xFFFFFFFF)

    // ---- UI chrome palette (M2 Task A: consolidated, values unchanged) ----
    // 以下常量是从 ImagePicker/EditScreen/HomeSettingsCard/GearMenu/AppCard 里
    // 原样搬来的 Color(0x..) 字面量,按用途命名、纯搬家——没有改过任何一个值。
    // 同一色值在多处作同一种用途时共用一个常量;视觉意图不同的即使撞色也分开命名。

    /** 弹窗/浮层面板背景:图片选择器、编辑页「选应用」弹窗、齿轮菜单、默认桌面卡片共用。 */
    val DialogSurface = Color(0xFF141414)
    /** 编辑页整屏背景。 */
    val EditScreenBackground = Color(0xFF0A0A0A)
    // (SettingsPreviewScrim 已随「壁纸组才半透明」那套分组切换一起删掉:M7 T5 起设置页
    //  恒为左深右浅的水平渐变遮罩、任何分组都一样,底下的首页全程可见,见 SettingsScreen。)
    /** 默认桌面卡片里「当前默认桌面」信息行背景。 */
    val InfoRowBackground = Color(0xFF1E1E1E)
    /** 未聚焦的交互面:图片选择器缩略图卡片、默认桌面卡片主按钮共用。 */
    val UnfocusedSurface = Color(0xFF222222)
    /** 编辑页「未安装」卡片(未聚焦)背景。 */
    val MissingCardBackground = Color(0xFF241414)
    /** 编辑页「加载中」占位卡片(未聚焦)背景。 */
    val PendingCardBackground = Color(0xFF242426)
    /** 默认桌面卡片里应用图标占位框背景。 */
    val IconPlaceholderBackground = Color(0xFF2A2A2A)
    /** 编辑页行尾「＋」加卡片(未聚焦)背景。 */
    val AddCardBackground = Color(0xFF2A2A2C)
    /** 图片选择器缩略图加载中的占位背景。 */
    val ThumbPlaceholderBackground = Color(0xFF333333)
    // MissingCardFocusedBackground(编辑页「未安装」卡片聚焦底色)随 Ruling R18(终审 2026-09-20)删除:
    // MissingCard 聚焦改用 gtvFocusStroke 外扩描边,不再靠换底色表示聚焦(同 AppCard),
    // 零调用点后就地删掉,不留死代码。
    /** 编辑页「加载中」占位卡片(聚焦)背景。 */
    val PendingCardFocusedBackground = Color(0xFF3A3A3C)
    /** 弹窗底部「返回关闭」一类提示文字:关于页、默认桌面卡片共用(gtv 线 Task 8 起齿轮菜单不再用——
     *  换皮后是全屏 banner + 药丸,不留这行提示)。 */
    val FooterHintText = Color(0xFF4A4A4A)
    /** 图片选择器底部「返回关闭/取消」提示文字。 */
    val PickerFooterText = Color(0xFF666666)
    /** 次要说明文字:编辑页应用包名、默认桌面卡片注释行共用。 */
    val FootnoteText = Color(0xFF7A7A7A)
    /** 图片选择器缩略图加载中的「...」占位文字。 */
    val ThumbLoadingText = Color(0xFF888888)
    /** 提示性文字:图片选择器 adb 提示、默认桌面卡片「当前」标签共用。 */
    val HintText = Color(0xFF8A8A8A)
    /** 编辑页次要文字:顶部提示、加载中占位卡片包名、选应用弹窗加载/空态提示共用。 */
    val SecondaryText = Color(0xFF9A9A9A)
    /** 图片选择器缩略图标签(未聚焦)。 */
    val ThumbLabelText = Color(0xFFAAAAAA)
    /** 编辑页「未安装」卡片提示文字。 */
    val MissingCardText = Color(0xFFB08080)
    /** 关于页检查更新的失败提示(网络 / 格式 / 下载 / 校验 / 安装失败)。与上一行撞色,用途不同分开命名。 */
    val StatusErrorText = Color(0xFFB08080)
    /** 齿轮菜单条目标题(未聚焦)。 */
    val MenuItemText = Color(0xFFB0B0B0)
    /** 默认桌面卡片主按钮文字(未聚焦)。 */
    val ButtonText = Color(0xFFCFCFCF)
    /** 弹窗正文文字:图片选择器空态提示、选应用弹窗条目标题(未聚焦)共用。 */
    val DialogBodyText = Color(0xFFE8E8E8)
    /** 强调/高亮文字:确认框、默认桌面卡片当前标签共用(gtv 线 Task 8 起齿轮菜单聚焦项改填主题
     *  accent、文字按亮度取黑/白对比色,不再固定用这个值)。 */
    val EmphasisText = Color(0xFFF5F5F5)

    // 注:曾按 Projectivy 资源表的 default_icon_bg(#333333)与 icons_scale(0.8)给方形图标
    // 加底色并缩放,复审用像素证明参考图里两者都没有生效——资源存在不代表用在这个位置。
    // 现在方形图标不画底、按卡片高铺满。

    /** 每行张数 → 一档尺寸。三档同一公式(spec §1.1),不再有「中档零回归锚点」;非法值按中档 6。 */
    fun cardMetrics(cardsPerRow: Int): CardMetrics {
        val n = if (cardsPerRow in VALID_CARDS_PER_ROW) cardsPerRow else 6
        return CardMetrics(
            cardWidth = HomeLayout.cardWidth(n).dp,
            cardHeight = HomeLayout.cardHeight(n).dp,
            cardCorner = HomeLayout.CARD_CORNER.dp,
            cardSpacing = HomeLayout.CARD_SPACING.dp,
            rowVerticalPad = HomeLayout.rowVerticalPad(n).dp,
            titleGap = HomeLayout.CARD_TITLE_GAP.dp,
            titleLine = HomeLayout.CARD_TITLE_LINE.dp,
            titleSize = 12.sp,   // bodySmall
        )
    }

    /** gtv 线:三档固定尺寸。与 [cardMetrics] 并存,main 线不受影响。 */
    fun gtvCardMetrics(size: GtvCardSize): CardMetrics = CardMetrics(
        cardWidth = GtvLayout.cardWidth(size).dp,
        cardHeight = GtvLayout.cardHeight(size).dp,
        cardCorner = GtvLayout.CARD_CORNER.dp,
        cardSpacing = GtvLayout.CARD_GAP.dp,
        // 行内上下留白 7dp:这是**行距预算**(rowPitch 里给聚焦态留的固定一项),不是溢出容量——
        // app tile 聚焦缩放 + 贴边描边的纵向视觉溢出是 GtvLayout.appFocusOverflow(cardHeight)
        // (LARGE 档 9.4dp),靠 rowVerticalPad + ROW_GAP(R51 起 7 + 40 = 47dp)一起容下(GtvLayoutTest
        // 「不会碰到下一行卡片的描边留白带」断言的就是这条);R28 的柔光更是纯绘制、不在任何预算里。
        // 数值沿用 content card 时代的 FOCUS_OUTSET + FOCUS_STROKE 只是为了不动 rowPitch(Round 4 要求)。
        rowVerticalPad = (GtvLayout.FOCUS_OUTSET + GtvLayout.FOCUS_STROKE).dp,
        // ui-pending #9:标题让到聚焦描边外缘之下(随档位变),见 GtvLayout.cardTitleGap
        titleGap = GtvLayout.cardTitleGap(size).dp,
        titleLine = GtvLayout.CARD_TITLE_LINE.dp,
        titleSize = 14.sp,
    )

    val SidePadding = HomeLayout.SIDE_PADDING.dp
    /** 焦点 / 位移动效:tv-material SurfaceScaleTokens 同一条减速曲线与进焦时长。
     *  **Ruling R27(2026-09-21)之后,gtv 分支上只剩图片选择器(`ImagePicker` 的网格翻页位移)
     *  这一个读者**——首页行位移(row shift x/y)与编辑页纵向位移已改读 [BrowseEasing] +
     *  [GtvLayout.BROWSE_SHIFT_MS],R29(2026-09-22)起再改为 [browseShiftSpec] 的弹簧。这两个常量**保留不删**:`main` 分支的 `HomeScreen`
     *  (`HomeLayout` 那套行位移,三处)仍然逐字读它们,在这里删掉只会在合回去时凭空造冲突,
     *  而 main 线的动效不在本轮验收范围里。
     *
     *  300ms 的出处如实记录:它等同 Material 的 `material_motion_duration_long_1`,是一个通用值,
     *  **与 Google TV 的 browse 手势没有关系**——这正是 R27 把 gtv 线换走的原因。 */
    val MotionEasing = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.2f, 1f)
    const val MotionInMs = 300

    /**
     * **Ruling R27(2026-09-21,owner 真机反馈 Round 7)**:Google TV 浏览位移(焦点在网格里移动、
     * 内容跟着平移)专用的缓动曲线,逐字来自旧版 launcherx APK(1.0.595789376,资源名未混淆)的
     * `anim/tv_easing_browse` = `pathInterpolator(controlX1=0.18, controlY1=1, controlX2=0.22,
     * controlY2=1)`;同一条曲线在设计 token 里还有一份取整版
     * `interpolator/gtvm3_sys_motion_easing_browse` = cubic-bezier(0.2, 1, 0.2, 1),两者互为佐证。
     * 这里取未取整的 APK 值。
     *
     * **三条曲线各管一段,互相不能替换**:
     * - [BrowseEasing](0.18, 1, 0.22, 1):**浏览位移**。控制点的 y 在 18% 的进度处就冲到 1,
     *   位移几乎一上来就走完大半、尾巴长长地收住——「内容被甩过去再稳下来」的手感,
     *   owner 反馈里说的「上下滚动时页面内容的动效」就是这一条。
     * - [MotionEasing](0, 0, 0.2, 1):Material 通用减速曲线,起步比 browse 慢得多(见上)。
     *   gtv 线之外仍在用,不是错的曲线,只是**不是 Google TV browse 的那一条**。
     * - [AppFocusEasing](AccelerateDecelerate,`cos((t+1)π)/2+0.5`):**焦点缩放 / 淡入淡出**,
     *   两头慢中间快的对称曲线,与位移是两件不同的事(Google 自己也是分开的两份资源:
     *   `animator/card_focus` 不写 interpolator 走平台默认,`tv_easing_browse` 另有其名)。
     *   **owner 反馈 Round 7 已核实我们这一条与 Google 逐字相同,不要动它。**
     */
    val BrowseEasing = androidx.compose.animation.core.CubicBezierEasing(0.18f, 1f, 0.22f, 1f)

    /**
     * **Ruling R29(2026-09-22,owner 真机反馈 Round 8)**:浏览位移(首页行 x/y、编辑页纵向 /
     * 行内横向,四处)的动画规格——临界阻尼弹簧,取代 R27 的 `tween(BROWSE_SHIFT_MS, BrowseEasing)`。
     * 依据、拟合过程与「这不是资源原值」的说明见 [GtvLayout.BROWSE_SPRING_STIFFNESS] 的 KDoc。
     * [BrowseEasing] 自此在 gtv 线没有调用点,保留作 R27 的记录(那条曲线本身是真的,只是不是
     * 行位移用的那条);别把它接回位移上。
     *
     * 四处调用点必须都读这一个工厂,不各自写 `spring(...)`——`GtvMotionTest` 钉的是这个工厂的
     * 三个参数,调用点自己写就脱离了测试。
     */
    fun browseShiftSpec(): androidx.compose.animation.core.SpringSpec<Dp> =
        androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = GtvLayout.BROWSE_SPRING_STIFFNESS,
            visibilityThreshold = GtvLayout.BROWSE_SPRING_THRESHOLD_DP.dp,
        )

    /**
     * **Ruling R47(2026-09-22)**:首页行标题焦点态(放大 + 灰→白)的进度 0→1 走与整页位移**同一根**
     * 临界阻尼弹簧(stiffness = [GtvLayout.BROWSE_SPRING_STIFFNESS]),标题与位移同起同止——Google
     * 实测两者都在位移起步后 ~0.3 s 到 95%(数据见 `GtvLayout` 里 R47 一节)。阈值 0.002(进度量纲,
     * 1.78 倍放大下不到 0.2% 字宽,肉眼不可辨)。
     * **R48 起**首页没有行标题,这根弹簧只驱动行图标的灰 ↔ 近白(不缩放);函数名原是
     * `rowTitleFocusSpec`,整枝评审(2026-09-23)随之改为现名。
     */
    fun rowIconFocusSpec(): androidx.compose.animation.core.SpringSpec<Float> =
        androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = GtvLayout.BROWSE_SPRING_STIFFNESS,
            visibilityThreshold = 0.002f,
        )

    /**
     * owner 反馈 Round 4(2026-09-21):Google 的 app tile 聚焦动画的真实插值器,从旧版 launcherx
     * APK(1.0.595789376,资源名未混淆)反编译读出——`animator/card_focus`/`card_unfocus` 两个
     * ObjectAnimator **都没有写 `interpolator` 属性**,Android 对 `ObjectAnimator` 的平台默认值是
     * `AccelerateDecelerateInterpolator`,其真实实现(`android.view.animation
     * .AccelerateDecelerateInterpolator#getInterpolation`)是
     * `cos((t + 1) · π) / 2 + 0.5`——**不是** `FastOutSlowInEasing`,曲线形状不同,不能替换。
     * gtv 线用在:`GtvFocusStroke.gtvAppFocusFrame`(app 卡片聚焦缩放 + 描边,`GtvLayout
     * .APP_FOCUS_SCALE`/`FOCUS_FADE_IN_MS`/`FOCUS_FADE_OUT_MS`)、`GtvFocusStroke.gtvFocusStroke`
     * (描边淡入淡出,复用同一对时长常量)、`GearMenu.MenuPill` 的填色、`GtvTopBar` 顶栏图标的填色——
     * 后两处 Google 的资源只给了时长(`top_nav_animation_duration_focus/unfocus`,菜单项没有独立
     * 引用),插值器本身没有单独核实,按同一份 APK 里其它焦点动画一致沿用 AccelerateDecelerate、
     * 不额外引入第三条曲线来处理,这一点在各自调用点的注释里另有说明。 */
    val AppFocusEasing = androidx.compose.animation.core.Easing { t ->
        (kotlin.math.cos((t + 1f) * Math.PI) / 2.0 + 0.5).toFloat()
    }

    /**
     * **Ruling R34(2026-09-22,owner 真机反馈 Round 9)**:app 卡片**进焦**放大(缩放 + 描边 +
     * 柔光淡入,[GtvLayout.FOCUS_SCALE_IN_MS],R34 1200 ms、R37 起 600 ms)的曲线——Material 标准减速
     * `cubic-bezier(0, 0, 0.2, 1)`,前段快后段慢,与模拟器 pts 实测的慢放大形态一致。
     * **不是** [AppFocusEasing]:那条 AccelerateDecelerate 是 `card_focus` 150 ms 旧路径的平台
     * 默认插值器,只剩失焦缩回(150 ms)与内容卡描边 / 菜单药丸还在用。控制点与 [MotionEasing]
     * 恰好相同,但两者语义不同(那条是 main 线 / 图片选择器的位置动画),分开命名,别互相替换。
     * 只有 `GtvFocusStroke.gtvAppFocusFrame` 的进焦分支读它。
     */
    val AppFocusScaleInEasing = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.2f, 1f)
    /** 编辑页专用(观感不动,M8 不碰二级界面);随二级界面换皮时删。 */
    val EditRowSpacing = 25.4.dp
    val EditRowTitleGap = 2.3.dp
    /** 编辑页行名前的图标与行名之间的间距。M4b 起就是 8dp,当时与首页行标题的图标间距同值;gtv 线首页曾把
     *  那个值常量化为 `GtvLayout.ROW_TITLE_ICON_GAP`,R48 随首页行标题一起删掉,此后只剩编辑页在用。 */
    val EditRowIconGap = 8.dp

    // 邻居压暗的数值写在 AppCard 的 shade 里,以那里为准(左邻居 0.09、隔一张 0.06、
    // 右邻居 0.05)。这里不再复述——注释抄一份就会各自漂移,先前就漂成了 0.17/0.09/0.04。

    /** 待机默认时长(3 分钟),与 [Settings.idleAfterMs] 的默认值一致。
     *  实际计时已改由 MainActivity 读 homeSettings.idleAfterMs 驱动(0 = 永不待机、
     *  可在设置页调整),这个常量只留作默认值参考,不再被计时逻辑直接读取。 */
    const val IdleAfterMs = 3 * 60 * 1000L

    /**
     * 屏保轮播默认间隔(= [Settings.screensaverIntervalMs] 的默认值)。M5 起实际间隔读设置;
     * 这里只剩两个读者:播放器第一次 attach 之前的初值、图库全屏预览的 Ken Burns 时长。
     */
    const val ScreensaverIntervalMs = 30_000L
    /** 屏保轮播:两张图之间的交叉淡入时长。 */
    const val ScreensaverCrossfadeMs = 2000
    /** 屏保 Ken Burns:每张图缓慢放大到多少倍(1.0 = 不放大)。 */
    const val ScreensaverZoom = 1.08f

    /** 壁纸换图(选图 / 轮播 / 改参数)的交叉淡入时长。 */
    const val WallpaperCrossfadeMs = 1500
}
