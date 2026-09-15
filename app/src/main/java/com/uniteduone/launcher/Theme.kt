package com.uniteduone.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 一档卡片布局的全部尺寸(随「每行张数」变化的那些)。
 * 中档(6/行)= [Theme] 里像素标定的原始常量原样;5/8 档由「可见跨度守恒」推导(见 [Theme.cardMetrics])。
 * 不随档位变的量(FocusScale、SidePadding、RowVerticalPad、Shadow* 等)仍直接读 [Theme],不进这里。
 */
data class CardMetrics(
    val cardWidth: Dp,
    val cardHeight: Dp,
    val cardCorner: Dp,
    val cardSpacing: Dp,
    val glowRadius: Dp,
    /** 卡顶到下一行卡顶的间距,只用于「焦点行会不会掉出屏幕」的位移计算(见 HomeScreen 的 overflow)。 */
    val rowPitch: Dp,
    /** 第一行卡片顶到内容顶的距离,同样只用于 overflow 计算。 */
    val firstCardTop: Dp,
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

    /** 香槟白:Projectivy 里是金格 (1,5) 亮度 +80,取到的近似色。用于光晕与时钟。 */
    val Champagne = Color(0xFFFFF5DC)

    /** 香槟金:状态栏齿轮。复审取参考图齿轮前 1% 像素的中位色 #BEA438、去模糊后约 #C0A73A;
     *  原来的 #D9B970 蓝通道 112,参考只有 56 —— 偏白偏冷了一档。 */
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
    /** 设置页聚焦在壁纸分组时的浮层底色:黑 35%,让壁纸透出来做实时预览。 */
    val SettingsPreviewScrim = Color(0x59000000)
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
    /** 齿轮菜单条目标题(未聚焦)。 */
    val MenuItemText = Color(0xFFB0B0B0)
    /** 默认桌面卡片主按钮文字(未聚焦)。 */
    val ButtonText = Color(0xFFCFCFCF)
    /** 应用卡片没有图标位图时的回退文字标签。 */
    val CardFallbackText = Color(0xFFDDDDDD)
    /** 弹窗正文文字:图片选择器空态提示、选应用弹窗条目标题(未聚焦)共用。 */
    val DialogBodyText = Color(0xFFE8E8E8)
    /** 强调/高亮文字:齿轮菜单条目标题(聚焦)、默认桌面卡片当前标签共用。 */
    val EmphasisText = Color(0xFFF5F5F5)

    // 注:曾按 Projectivy 资源表的 default_icon_bg(#333333)与 icons_scale(0.8)给方形图标
    // 加底色并缩放,复审用像素证明参考图里两者都没有生效——资源存在不代表用在这个位置。
    // 现在方形图标不画底、按卡片高铺满。

    // 尺寸全部按 v4 截图像素级实测(1920x1080,density 2.0,故 dp = px/2):
    //   卡片 254x142px、左边距 166px、卡片间距 28px、
    //   第一行卡片顶 486px、行间距(卡片底到下行卡片顶)182px。
    // 三行按这个尺寸会超出屏幕——**v4 本身就是这样**(MUSIC 行在屏幕外),所以配垂直滚动。
    val CardWidth = 127.75.dp   // 复审实测 255.33px(自身重复性 ±0.08px)
    val CardHeight = 71.85.dp      // 16:9;复审实测 143.8px,且 255.33×9/16=143.6 独立吻合
    val CardCorner = 13.75.dp   // 复审弧线拟合 27.4px;与 Projectivy「圆角 40%」(0.4×71.85)一致
    val CardSpacing = 8.9.dp   // 复审三轮实测参考间隙 17.88/17.78px(9.5dp 给出 19.0px)
    /**
     * 行与行的额外间距。注意它不等于「行距」——行距还包含标题、间隙和行内上下留白,
     * 所以改 RowVerticalPad 时这里要跟着反向调整,否则行距会跑掉(实测一次调大留白后
     * 行距从 324px 变成 358px)。目标是让卡片顶到卡片顶正好 RowPitch。
     */
    // 标定到卡片顶间距 328px。行标题图标从 16dp 放大到 24dp 后每行都高了 16px,
    // 行距会跟着涨,所以这个值比单看间距推出来的要小。
    val RowSpacing = 25.4.dp
    // 标题正文 cap 中心到卡顶:参考 69.0px,原 4.5dp 给出 73.1px。
    // 注意参考图里**正文比行图标高 3.33px**,而我们两者齐平,所以这里以正文为基准
    // (图标字形本来就不是同一个,拿它当基准会把正文推错位)。
    val RowTitleGap = 2.3.dp
    val SidePadding = 84.5.dp   // 复审三轮实测参考左边缘 168.4/168.6px(85dp 给出 169.5px)
    val TopPadding = 195.3.dp   // 与 RowTitleGap/RowSpacing 联立解出:第一行卡顶落在 483.4px
    /** 行内上下留白:要放得下聚焦后放大 31% 的卡片,否则会被行高裁掉。 */
    val RowVerticalPad = 20.dp

    /**
     * 非当前行的卡片压暗——v4 实测:同一张白底卡片在当前行是 (255,255,255),
     * 在非当前行是 (75,73,63)。三通道各不同(R 29.4% / G 28.6% / B 24.7%),
     * 说明 Projectivy 在 alpha 之外还有色温偏移;单一 alpha 无法完美复现,
     * 取 0.248 是偏暗偏冷的折中(更接近 B 通道),0.29 偏亮偏暖(更接近 R)。
     */
    const val InactiveRowAlpha = 0.248f

    /**
      * 只用于「焦点行会不会掉出屏幕」的位移计算,必须与实际渲染出来的位置一致。
      * 复审实测:第一行卡顶 483.4px = 241.7dp;卡顶到卡顶 327.5px = 163.75dp。
      */
    val FirstCardTop = 241.7.dp
    val RowPitch = 163.75.dp
    /** 焦点行底部至少离屏幕底这么远,不够就整体上移。 */
    val BottomKeepout = 28.dp

    /**
     * 每行张数 → 一档卡片尺寸。门 1(Gordon 定):**只定每行张数,其余按比例算,中档锚定**。
     *
     * - **中档 6(以及任何意外值)返回上面这批像素标定的原始常量原样** —— 零回归的锚点,
     *   不要在这里「优化」标定值。
     * - **5 / 8 档由「可见跨度守恒」推导**:当前 6 张卡的可见跨度
     *   `S = 6*CardWidth + 5*CardSpacing`;换成 N 张时保持同一个 S、且 [CardSpacing] 不变,
     *   于是 `cardWidth(N) = (S - (N-1)*CardSpacing) / N`。再用比例 `f = cardWidth(N)/CardWidth`
     *   缩放随卡宽变的量:高、圆角、光晕。[FocusScale] 与 [CardSpacing] 跨档不变。
     *   → 5 张更大、8 张更小、6 张不变。
     * - **纵向**:行距 [RowPitch] 里只有卡高一项随档变(标题、间隙、留白、行间距都不变),
     *   所以 `rowPitch(N) = RowPitch + (cardHeight(N) - CardHeight)`。
     *   卡「顶」的位置不受卡高影响,故 [FirstCardTop] 跨档不变。
     */
    fun cardMetrics(cardsPerRow: Int): CardMetrics {
        // 6 与任何意外值都走中档:原始常量原样,保证与本次改动前逐像素一致。
        if (cardsPerRow != 5 && cardsPerRow != 8) {
            return CardMetrics(
                cardWidth = CardWidth,
                cardHeight = CardHeight,
                cardCorner = CardCorner,
                cardSpacing = CardSpacing,
                glowRadius = GlowRadius,
                rowPitch = RowPitch,
                firstCardTop = FirstCardTop,
            )
        }
        val span = CardWidth * 6 + CardSpacing * 5           // 6 张时的可见跨度 S = 811.0dp
        val cardWidth = (span - CardSpacing * (cardsPerRow - 1)) / cardsPerRow
        val f = cardWidth / CardWidth                         // Dp/Dp = Float 比例因子
        val cardHeight = CardHeight * f
        return CardMetrics(
            cardWidth = cardWidth,
            cardHeight = cardHeight,
            cardCorner = CardCorner * f,
            cardSpacing = CardSpacing,                        // 跨档不变
            glowRadius = GlowRadius * f,
            rowPitch = RowPitch + (cardHeight - CardHeight),  // 只有卡高随档变
            firstCardTop = FirstCardTop,                      // 卡顶位置与卡高无关
        )
    }

    /**
     * 聚焦卡片放大比例:v4 实测,同一张卡片未聚焦时高 143px、聚焦时 187px,187/143 ≈ 1.31。
     * 第一版拍脑袋写 1.06,屏幕上几乎看不出放大——这是「凭感觉设参数」的典型代价。
     */
    const val FocusScale = 1.31f
    /**
     * 光晕半径。注意 LazyRow 会裁剪超出行边界的绘制,半径设得比行内留白还大时
     * 光晕会被整块裁掉、屏幕上几乎看不见(第一版 34dp 就是这样)。
     * 现在的值配合 RowVerticalPad 20dp 刚好落在可见区域内。
     */
    val GlowRadius = 6.8.dp   // 复审用无模型拟合:参考光晕比原来窄 37%、亮 18%
    // 邻居压暗的数值写在 AppCard 的 shade 里,以那里为准(左邻居 0.09、隔一张 0.06、
    // 右邻居 0.05)。这里不再复述——注释抄一份就会各自漂移,先前就漂成了 0.17/0.09/0.04。
    const val GlowPeriodMs = 2500    // v4 实测周期 2.50 s

    /**
     * 聚焦卡片投在邻居身上的**投影**。参考图里这层压暗是二维的:邻居顶部几乎不暗(5%),
     * 底部最深 18%,且左邻居的亏损是右邻居的 3 倍 —— 光源在右上、影子落向左下。
     * 先前做成了 `Brush.horizontalGradient`,任何 y 上都一模一样,是最大的一处可见差异
     * (最深处差 31/255)。它同时解释了光晕的上下不对称:参考图上重下轻 43%,
     * 正是因为下方光晕被这层投影吃掉了 —— **所以不要去调光晕的 alpha 或半径**。
     */
    // 参数是按实测反解的,不是拍脑袋:第一版 dy 只有 3.5dp,投影整片盖住邻居
    // (实测左邻居顶 213 / 底 205,几乎均匀),而参考是 243 / 212 —— **上下差 3.6 倍**。
    // 要的是「影子的上边缘正好落在邻居中部」,所以 dy 必须与卡片高同量级。
    // 注意这些值在**卡片自身坐标系**里,聚焦时整体还会被放大 1.31 倍。
    val ShadowRadius = 12.5.dp   // 屏幕上约 33px
    val ShadowDx = (-4).dp       // 屏幕上约 -10.5px,影子偏左
    val ShadowDy = 21.75.dp      // 屏幕上约 +57px
    val ShadowColor = Color(0x60000000)

    /** 待机:3 分钟无按键,除时钟外淡出。 */
    const val IdleAfterMs = 3 * 60 * 1000L

    /** 屏保轮播:每张图显示多久。 */
    const val ScreensaverIntervalMs = 30_000L
    /** 屏保轮播:两张图之间的交叉淡入时长。 */
    const val ScreensaverCrossfadeMs = 2000
    /** 屏保 Ken Burns:每张图缓慢放大到多少倍(1.0 = 不放大)。 */
    const val ScreensaverZoom = 1.08f

    /** 壁纸换图(选图 / 轮播 / 改参数)的交叉淡入时长。 */
    const val WallpaperCrossfadeMs = 1500
}
