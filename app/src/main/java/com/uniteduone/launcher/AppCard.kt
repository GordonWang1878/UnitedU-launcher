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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
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
 * 16:9 卡片(M8:tv-material `Card`)。进 300 / 出 500 / 按下 120ms 仍是库默认,这里不再自己画光晕 / 投影。
 * **焦点画法改自 Google TV 实测(gtv 线 Task 5)**:不放大、不用库默认的 3dp `colorScheme.border`——
 * `scale`/`border` 都显式设成 1f/`Border.None`,换成 [GtvFocusStroke.gtvFocusStroke] 在布局框外
 * `GtvLayout.FOCUS_OUTSET` dp 处画 `GtvLayout.FOCUS_STROKE` dp 细描边,颜色用主题 accent。
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
    /** 主题化卡片:去色→染 accent。 */
    themed: Boolean = false,
    /**
     * 首页原地移动态里被搬的那张卡(M4b spec §0-10,gtv 线 Task 5 改画法):描边跟普通聚焦一样画在
     * 布局框外(不再是库默认贴边的 3dp),但换成主题 highlight(accent 混 55% 白的近白色)——
     * 与普通聚焦描边的 accent 区分开,两者同时成立时才认得出哪张是被搬的那张。
     * **聚焦与否都画**:每搬一步,焦点要晚一两帧才追到新位置,那几帧里被搬的卡也得认得出来。
     */
    moving: Boolean = false,
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
    val border = CardDefaults.border(focusedBorder = Border.None, border = Border.None)
    val cardTint = if (themed) ColorFilter.colorMatrix(ColorMatrix(cardTintMatrix(accent.toArgb() and 0xFFFFFF))) else null
    // 容器色:有图的卡透明(横幅铺满,库的 clip 裁圆角);主题化统一铺深 accent 底;图标回落卡铺边缘色;
    // 连图都没有(文字回落)用库的 surfaceVariant #49454F。accent 不进卡片中间(M7 §10.5)——只有主题化开关是用户主动要的例外。
    val container = when {
        themed && app.card != null -> accent.copy(alpha = 0.20f)
        fallbackColor != null && app.card != null && !app.isWide -> fallbackColor
        app.card != null -> Color.Transparent
        else -> scheme.surfaceVariant
    }
    val shape = RoundedCornerShape(metrics.cardCorner)
    Column(
        // 聚焦卡浮到邻居上面(外扩描边不被右邻居盖住)。标题是 Card 外层 Column 的兄弟节点,
        // 不在库的 graphicsLayer 缩放范围内——反正现在也不缩放了,标题始终固定大小贴在卡片下方。
        modifier = Modifier.zIndex(if (focused) 1f else 0f).width(metrics.cardWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            onClick = onClick,
            onLongClick = null,
            modifier = modifier
                // 两条画在布局框外(负偏移,见 GtvFocusStroke.kt);同时成立时 moving 的 highlight 描边
                // 排在后面、盖在 accent 描边上面——被搬的卡在搬运过程中始终认得出来。
                .gtvFocusStroke(focused, accent, metrics.cardCorner)
                .gtvFocusStroke(moving, movingColor, metrics.cardCorner)
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
            // 不放大(Google TV 实测不缩放);glow 仍用库默认 Glow.None。描边全部让给上面
            // 两条 gtvFocusStroke,这里钉 None,避免库自己再画一圈贴边的 3dp 描边。
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
                        colorFilter = cardTint,
                        modifier = Modifier.size(metrics.cardWidth, metrics.cardHeight),
                    )
                } else if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = app.label,
                        colorFilter = cardTint,
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
                modifier = Modifier.padding(top = metrics.titleGap).width(metrics.cardWidth).height(metrics.titleLine),
            )
        } else if (reserveTitleSpace) {
            // 与上面标题那一行等高(padding titleGap + 行高 titleLine),只占位不画
            Spacer(Modifier.height(metrics.titleGap + metrics.titleLine))
        }
    }
}
