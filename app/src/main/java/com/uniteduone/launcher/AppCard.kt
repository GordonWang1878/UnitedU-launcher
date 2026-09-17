package com.uniteduone.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme

/**
 * 16:9 卡片(M8:tv-material `Card`)。放大 1.1、3dp `colorScheme.border` 描边、进 300 / 出 500 / 按下 120ms
 * 全是库默认(spec §1.2 ★),这里不再自己画光晕 / 投影 / 缩放。
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
    /** 当前卡片档位的尺寸。默认中档。 */
    metrics: CardMetrics = Theme.cardMetrics(6),
    onFocusChange: (Boolean) -> Unit = {},
    /** 行首/行末:到边界后左右键不再跳到别的行。 */
    isRowStart: Boolean = false,
    isRowEnd: Boolean = false,
    /** 末行:下面没有任何可聚焦节点,不锁的话按「下」焦点会整棵树消失。 */
    isLastRow: Boolean = false,
    /** 首行锁「上」(编辑页用;首页上方有 pill 组接住,默认 false)。 */
    isFirstRow: Boolean = false,
    /** 上下移动的显式落点:相邻行「记住的那一格」。 */
    upTarget: FocusRequester? = null,
    downTarget: FocusRequester? = null,
    /** 卡片下方一行小字;null = 不显示。 */
    title: String? = null,
    /** 无横幅回落卡的底色(图标边缘色);null 或有横幅时不铺。 */
    fallbackColor: Color? = null,
    /** 主题化卡片:去色→染 accent。 */
    themed: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalThemeColors.current.accent
    val scheme = MaterialTheme.colorScheme
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
        // 聚焦卡浮到邻居上面(3dp 描边不被右邻居盖住),标题一起放大——与库的 graphicsLayer 缩放同一个节点树
        modifier = Modifier.zIndex(if (focused) 1f else 0f).width(metrics.cardWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            onClick = onClick,
            onLongClick = null,
            modifier = modifier
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
            // scale / border / glow 用库默认:1.1、3dp colorScheme.border、Glow.None
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
            // 库的 CardDefaults.SubtitleAlpha = 0.6,字号 bodySmall 12sp(metrics.titleSize)
            BasicText(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    color = scheme.onSurface.copy(alpha = 0.6f),
                    fontSize = metrics.titleSize,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.padding(top = metrics.titleGap).width(metrics.cardWidth).height(metrics.titleLine),
            )
        }
    }
}
