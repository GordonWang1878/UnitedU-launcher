package com.uniteduone.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme

/**
 * 16:9 卡片(M8:tv-material `Card`)。按下 120ms 仍是库默认,这里不再自己画光晕 / 投影。
 * **焦点画法(owner 反馈 Round 4 改为 app tile 处理)**:不用库默认的 1.1×缩放/3dp
 * `colorScheme.border`——`scale`/`border` 都显式设成 1f/`Border.None`,自己接管:聚焦时缩放
 * [GtvLayout.APP_FOCUS_SCALE] 倍 + 描边贴着缩放后的边缘外扩,由
 * [GtvFocusStroke.gtvAppFocusFrame] 实现(取代了 Task 5 时套用的 content-card 静态描边,
 * 见该函数 KDoc「Google 对 app tile 的真实处理是放大」)。
 * 焦点上报仍挂在传给 Card 的 modifier 上:它排在库内部 `focusable` 之前,能观察到同一个焦点目标(铁律 2 / 4)。
 * 长按由 MainActivity.dispatchKeyEvent 按 600ms 判(M4),所以 `onLongClick = null`;
 * Activity 吞掉重复事件后库只看到「短按 DOWN → UP」= 点击。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppCard(
    app: AppEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 当前卡片档位的尺寸。**没有默认值**(Fix 5,终审 2026-09-20):两个调用点(HomeScreen、
     *  EditScreen)都显式传值,给一个默认值只会让将来某个漏传的 gtv 调用点静默拿到 main 线的
     *  几何、且编译期看不出来——比如以前 `Theme.cardMetrics(6)` 这个默认值本身就是 main 线的。 */
    metrics: CardMetrics,
    onFocusChange: (Boolean) -> Unit = {},
    /** 行首/行末:到边界后左右键不再跳到别的行。 */
    isRowStart: Boolean = false,
    isRowEnd: Boolean = false,
    /** 末行:下面没有任何可聚焦节点,不锁的话按「下」焦点会整棵树消失。 */
    isLastRow: Boolean = false,
    /** 首行锁「上」(编辑页用;首页上方有 pill 组接住,默认 false)。 */
    isFirstRow: Boolean = false,
    /** 上下移动的显式落点:相邻行里与当前列对齐的那一格,邻行更短则是它的末张(2026-09-18 起,同列规则)。 */
    upTarget: FocusRequester? = null,
    downTarget: FocusRequester? = null,
    /** 卡片下方一行小字;null = 不显示。 */
    title: String? = null,
    /**
     * 本卡不显示标题、但同屏应用行显示时,照样空出标题那一行的高度:每一行的高度才都等于
     * 各自那条线的 rowPitch(main 线 HomeLayout.rowPitch / gtv 线 GtvLayout.rowPitch),
     * 纵向锚点不会因为输入源行少一截标题高度而整体偏移(M8 终审遗留)。
     */
    reserveTitleSpace: Boolean = false,
    /** 无横幅回落卡的底色(图标边缘色);null 或有横幅时不铺。 */
    fallbackColor: Color? = null,
    /**
     * 首页原地移动态里被搬的那张卡(M4b spec §0-10,gtv 线 Task 5 改画法):描边跟普通聚焦一样画在
     * 布局框外(不再是库默认贴边的 3dp),但换成主题 highlight(accent 混 55% 白的近白色)——
     * 与普通聚焦描边的 accent 区分开,两者同时成立时才认得出哪张是被搬的那张。
     * **聚焦与否都画**:每搬一步,焦点要晚一两帧才追到新位置,那几帧里被搬的卡也得认得出来。
     */
    moving: Boolean = false,
    /**
     * Ruling R30(owner 反馈 Round 8):这次进焦是否伴随行位移(纵向切行 / 横向滑行)。为 true 时
     * 缩放 + 描边 + 柔光推迟 [GtvLayout.FOCUS_AFTER_SHIFT_DELAY_MS] 再淡入,让位移先走;判定由
     * `HomeScreen.CategoryRow` 在焦点回调里做(与 `focused` 同一个事件里写入,下一次重组一起生效)。
     * 编辑页不传,立即放大。
     */
    focusAfterShift: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalThemeColors.current.accent
    // 移动态的描边色:必须与下面 focused 用的 accent 不同,两者才能同时可辨(见 moving 参数上的说明)。
    val movingColor = LocalThemeColors.current.highlight
    val scheme = MaterialTheme.colorScheme
    // Google TV 实测(gtv 线 spec):聚焦**不放大**、描边画在布局框外 FOCUS_OUTSET dp 处,由
    // GtvFocusStroke.kt 的 drawBehind 负偏移实现(见下面 Card 的 modifier 链上两条 gtvFocusStroke)。
    // 库默认的 1.1x 缩放 / 3dp 描边都不要了,scale 显式钉 1f,border 显式钉 None——包括移动态:
    // 它以前借的是库的 Border 机制画贴边描边,现在改用同一套 gtvFocusStroke 外扩,只是换色。
    // Google TV 的 app tile 聚焦画法(owner 反馈 Round 4):缩放 + 描边贴着缩放后边缘,由
    // GtvFocusStroke.gtvAppFocusFrame 实现(取代 Task 5 时套用的 content-card 静态外扩描边)。
    // 库默认的 1.1x 缩放 / 3dp 描边都不要了,scale 显式钉 1f,border 显式钉 None——我们自己的缩放
    // 走 graphicsLayer(在 gtvAppFocusFrame 内部),不经过库的 CardDefaults.scale。
    val border = CardDefaults.border(focusedBorder = Border.None, border = Border.None)
    // 容器色:有图的卡透明(横幅铺满,库的 clip 裁圆角);图标回落卡铺边缘色;
    // 连图都没有(文字回落)用库的 surfaceVariant #49454F。accent 不进卡片中间(M7 §10.5)。
    // (「主题化卡片」开关 2026-09-23 删掉,gtv spec R58:卡片的亮度 / 饱和度已由 R49 淡化统一压下来。)
    val container = when {
        fallbackColor != null && app.card != null && !app.isWide -> fallbackColor
        app.card != null -> Color.Transparent
        else -> scheme.surfaceVariant
    }
    val shape = RoundedCornerShape(metrics.cardCorner)
    Column(
        // 聚焦卡浮到邻居上面(缩放 + 外扩描边不被右邻居盖住)。标题是 Card 外层 Column 的兄弟节点,
        // 不在 gtvAppFocusFrame 的 graphicsLayer 缩放范围内(那层只包在 Card 自己的 modifier 链上)——
        // 标题始终按未缩放的 metrics.cardWidth 布局、固定大小贴在卡片下方,不会跟着卡片一起放大
        // (owner 反馈 Round 4「no layout change」;卡片视觉上长大时标题原地不动,是设计而非疏漏)。
        modifier = Modifier.zIndex(if (focused) 1f else 0f).width(metrics.cardWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            onClick = onClick,
            onLongClick = null,
            modifier = modifier
                // 聚焦缩放 + 描边(贴缩放后边缘)+ 移动态高亮描边(固定几何,不缩放),三者都在
                // gtvAppFocusFrame 里(见其 KDoc 里的绘制顺序说明);R49 的淡化只包卡片内容(容器底色 +
                // banner / 图标 / 文字回落),挂在它**之内**——顺序由 gtvFocusFrameOverFade 一处固定。
                .gtvFocusFrameOverFade(focused, accent, metrics.cardCorner, moving, movingColor, afterShift = focusAfterShift)
                .size(metrics.cardWidth, metrics.cardHeight)
                .focusProperties {
                    if (isRowStart) left = FocusRequester.Cancel
                    if (isRowEnd) right = FocusRequester.Cancel
                    if (isLastRow) down = FocusRequester.Cancel else downTarget?.let { down = it }
                    if (isFirstRow) up = FocusRequester.Cancel else upTarget?.let { up = it }
                }
                .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) },
            shape = CardDefaults.shape(shape),
            colors = CardDefaults.colors(
                containerColor = container,
                contentColor = scheme.onSurface,
                focusedContainerColor = container,
                pressedContainerColor = container,
            ),
            // 库自己的缩放/glow 都关掉:缩放由 gtvAppFocusFrame 的 graphicsLayer 接管,
            // glow 仍用库默认 Glow.None。描边全部让给 gtvAppFocusFrame,这里钉 None。
            scale = CardDefaults.scale(focusedScale = 1f),
            border = border,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val bmp = app.card
                if (bmp != null && app.isWide) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = app.label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(metrics.cardWidth, metrics.cardHeight),
                    )
                } else if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = app.label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(metrics.cardHeight),
                    )
                } else {
                    BasicText(
                        text = app.label,
                        style = TextStyle(
                            fontFamily = Theme.Sans, color = scheme.onSurface,
                            fontSize = 15.sp, textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        }
        if (title != null) {
            // 库的 CardDefaults.SubtitleAlpha = 0.6;字号取 metrics.titleSize——main 线是
            // bodySmall 12sp(Theme.cardMetrics),gtv 线是 14sp(Theme.gtvCardMetrics,spec §2.3)。
            // Fix 1(owner 反馈 R2,2026-09-20):显式给 lineHeight 赋值为 metrics.titleLine——
            // 之前这里完全不设 lineHeight(Unspecified),14sp CJK 的自然行高(20dp,见
            // GtvLayout.CARD_TITLE_LINE 的 KDoc)比容器 Modifier.height(metrics.titleLine)(改前
            // 是 16dp)还高,字形下沿被硬裁。这里不直接写 GtvLayout 的常量——AppCard 对 main 线 /
            // gtv 线通用,只认 CardMetrics,由两条线各自的 Theme.xxxCardMetrics() 决定 titleLine
            // 取哪个常量;lineHeight 与容器高度共读同一个 metrics.titleLine,不会再各自漂移。
            BasicText(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    color = scheme.onSurface.copy(alpha = 0.6f),
                    fontSize = metrics.titleSize,
                    textAlign = TextAlign.Center,
                    lineHeight = metrics.titleLine.value.sp,
                ),
                modifier = Modifier.padding(top = metrics.titleGap).width(metrics.cardWidth).height(metrics.titleLine)
                    .gtvCardFade(),   // R49:卡片标题同样淡化
            )
        } else if (reserveTitleSpace) {
            // 与上面标题那一行等高(padding titleGap + 行高 titleLine),只占位不画
            Spacer(Modifier.height(metrics.titleGap + metrics.titleLine))
        }
    }
}

private val CardFadePaint by lazy {
    androidx.compose.ui.graphics.Paint().apply {
        colorFilter = ColorFilter.colorMatrix(ColorMatrix(GtvLayout.cardFadeMatrix()))
    }
}

/**
 * **Ruling R49**:卡片淡化(效果图 B4,算法与常量见 [GtvLayout.CARD_FADE_SATURATION])。把本节点及其
 * 内层画的全部内容放进一个带颜色矩阵的离屏层(`saveLayer` + paint 的 colorFilter,效果同
 * `graphicsLayer { compositingStrategy = Offscreen }` 再上滤镜)。**只挂在卡片内容那一层**:
 * 外层的聚焦描边 / 柔光 / 搬运态描边(`gtvAppFocusFrame` 的 drawBehind)不在这层里,颜色不变。
 * 用在首页 / 编辑页的 [AppCard] 与长按菜单左侧 banner;「添加应用」列表与图片选择器不用。
 * 与聚焦框同用时走 [gtvFocusFrameOverFade],不要自己拼链(顺序是硬约束,见那里)。
 */
fun Modifier.gtvCardFade(): Modifier = drawWithContent {
    drawIntoCanvas { it.saveLayer(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset.Zero, size), CardFadePaint) }
    drawContent()
    drawIntoCanvas { it.restore() }
}

/**
 * **R49 边界**:卡片的聚焦框([gtvAppFocusFrame]:柔光 → 聚焦描边 → 搬运描边 + 缩放)与淡化层
 * ([gtvCardFade])只在这里组合,顺序是硬约束——聚焦框在外层(modifier 链更前),它的 `drawBehind`
 * 画在淡化层的离屏图层之外、主题色原样;反过来,描边与柔光会被去饱和压暗,而且因为离屏图层以卡片
 * 布局框为界,框外的描边与柔光会被整个裁掉。`GtvGlowTest` 按 modifier 链的元素顺序钉住这一点。
 */
internal fun Modifier.gtvFocusFrameOverFade(
    focused: Boolean,
    accentColor: Color,
    corner: androidx.compose.ui.unit.Dp,
    moving: Boolean = false,
    movingColor: Color = Color.Unspecified,
    afterShift: Boolean = false,
): Modifier = gtvAppFocusFrame(focused, accentColor, corner, moving, movingColor, afterShift).gtvCardFade()
