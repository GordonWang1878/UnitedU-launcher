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
        // 不缩放了,行内上下留白只需容下外扩描边
        rowVerticalPad = (GtvLayout.FOCUS_OUTSET + GtvLayout.FOCUS_STROKE).dp,
        titleGap = GtvLayout.CARD_TITLE_GAP.dp,
        titleLine = GtvLayout.CARD_TITLE_LINE.dp,
        titleSize = 14.sp,
    )

    val SidePadding = HomeLayout.SIDE_PADDING.dp
    /** 焦点 / 位移动效:tv-material SurfaceScaleTokens 同一条减速曲线与进焦时长。main 线的行位移、
     *  gtv 线的行位移(row shift x/y)、图片选择器的位移动画仍读这个——它们与下面的
     *  [AppFocusEasing] 是两件不同的事:这个管「位置」,那个管「焦点淡入淡出/缩放」。 */
    val MotionEasing = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.2f, 1f)
    const val MotionInMs = 300

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
    /** 编辑页专用(观感不动,M8 不碰二级界面);随二级界面换皮时删。 */
    val EditRowSpacing = 25.4.dp
    val EditRowTitleGap = 2.3.dp

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
