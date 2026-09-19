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

/** 一档卡片布局的尺寸(Dp)。全部由 [HomeLayout] 推导,这里只做单位包装,不要在这里写任何数字。 */
data class CardMetrics(
    val cardWidth: Dp,
    val cardHeight: Dp,
    val cardCorner: Dp,
    val cardSpacing: Dp,
    /** 行内上下留白:放大 10% 的溢出一半 + 描边。 */
    val rowVerticalPad: Dp,
    val titleGap: Dp,
    val titleLine: Dp,
    val titleSize: TextUnit,
)

/** 全部视觉常量集中在这里,对应 docs/DESIGN-custom-launcher.md §4 的规格表。 */
object Theme {
    val Background = Color(0xFF000000)

    /**
     * Projectivy 用的就是 DM Sans(2026-09-10 从它的 APK 里读 name 表确认),
     * 字体本身是 SIL Open Font License,可以合法内置。不用系统 Roboto,否则字形一眼看得出不同。
     */
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    val Sans = FontFamily(
        Font(R.font.dm_sans_regular, FontWeight.Normal),
        Font(R.font.dm_sans_bold, FontWeight.Bold),
        // Medium 必须来自**可变字体**:Projectivy 的 APK 里除了这两个静态字重,还带一个
        // 「DM Sans 9pt」可变字体,行标题用的就是它的 500 轴。只注册 Normal/Bold 时
        // FontWeight.Medium 会静默回落到 Normal —— 复审实测行标题 cap 矮 3.0%、竖笔细 9%,
        // 而**同一个 15.5sp 下没有任何字号能同时补上高度和宽度**,因为差的是字形不是缩放
        // (时钟用 Normal,实测数字高 23.02 vs 23.00 已经对上,可作对照)。
        Font(
            R.font.dm_sans_var,
            FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500)),
        ),
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
    /** 编辑页「未安装」卡片(聚焦)背景。 */
    val MissingCardFocusedBackground = Color(0xFF3A2020)
    /** 编辑页「加载中」占位卡片(聚焦)背景。 */
    val PendingCardFocusedBackground = Color(0xFF3A3A3C)
    /** 弹窗底部「返回关闭」一类提示文字:齿轮菜单、默认桌面卡片共用。 */
    val FooterHintText = Color(0xFF4A4A4A)
    /** 齿轮菜单条目副标题(未聚焦)。 */
    val MenuHintText = Color(0xFF5A5A5A)
    /** 图片选择器底部「返回关闭/取消」提示文字。 */
    val PickerFooterText = Color(0xFF666666)
    /** 次要说明文字:编辑页应用包名、默认桌面卡片注释行共用。 */
    val FootnoteText = Color(0xFF7A7A7A)
    /** 图片选择器缩略图加载中的「...」占位文字。 */
    val ThumbLoadingText = Color(0xFF888888)
    /** 提示性文字:图片选择器 adb 提示、默认桌面卡片「当前」标签共用。 */
    val HintText = Color(0xFF8A8A8A)
    /** 齿轮菜单条目副标题(聚焦)。 */
    val MenuHintTextFocused = Color(0xFF999999)
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
    /** 强调/高亮文字:齿轮菜单条目标题(聚焦)、默认桌面卡片当前标签共用。 */
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

    val SidePadding = HomeLayout.SIDE_PADDING.dp
    /** 焦点 / 位移动效:tv-material SurfaceScaleTokens 同一条减速曲线与进焦时长。 */
    val MotionEasing = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.2f, 1f)
    const val MotionInMs = 300
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
