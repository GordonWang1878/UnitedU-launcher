package com.uniteduone.launcher

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable


import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/**
 * 16:9 卡片。聚焦时略放大,并画一圈**呼吸光晕**——用原生 Paint 的 setShadowLayer,
 * 因为 Compose 没有直接的外发光 API;半径随 2.5 s 的正弦往复变化(v4 实测周期)。
 */
@Composable
private fun rememberGlowBreath(): State<Float> =
    rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(Theme.GlowPeriodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "breath",
    )

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppCard(
    app: AppEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 当前卡片档位的尺寸(卡宽/高/圆角/光晕)。默认中档,保证未接线的调用点仍与今日一致。 */
    metrics: CardMetrics = Theme.cardMetrics(6),
    /** 呼吸光晕的颜色(highlight 主题色)。默认今日的 [Theme.Champagne] —— 编辑页的卡片不接线,
     *  保持原样;首页把选中预设的 highlight 穿进来。 */
    glowColor: Color = Theme.Champagne,
    onFocusChange: (Boolean) -> Unit = {},
    /** 行首/行末:到边界后左右键不再跳到别的行(Compose 默认会按几何位置找最近的可聚焦项,
     *  表现就是「按右键从第一行末尾跳进了第二行」,Projectivy 不会这样)。 */
    isRowStart: Boolean = false,
    isRowEnd: Boolean = false,
    /** 末行:下面没有任何可聚焦节点,不锁的话按「下」焦点会整棵树消失(看门狗会把它捞回
     *  第一行第一张 —— 能自愈,但落点是错的:焦点从第三行瞬移到第一行)。 */
    isLastRow: Boolean = false,
    /** 首行锁「上」。编辑界面第一行上方只有两行不可聚焦的说明文字。
     *  **真机实测:不锁也不会失焦**(外层 verticalScroll 挡住了越界搜索),所以这是防御性的,
     *  不是在修已复现的故障 —— 理由同 PickerRow:别把正确性寄托在容器的副作用上。
     *  首页不需要(上方有齿轮接住),默认 false。 */
    isFirstRow: Boolean = false,
    /** 上下移动时的显式落点:指向相邻行「记住的那一格」。不给的话 Compose 走纯几何搜索,
     *  从第 8 张下去再上来会落到第 5 张,而 Projectivy 是记住列的。 */
    upTarget: FocusRequester? = null,
    downTarget: FocusRequester? = null,
    /** 卡片下方一行小字(design §2):null = 不显示(全局开关关着,或输入源行)。 */
    title: String? = null,
    /** 无横幅回落卡的底色(图标主色);null 或有横幅时不铺底,保持透明。 */
    fallbackColor: Color? = null,
) {
    var focused by remember { mutableStateOf(false) }
    // 呼吸动画只在聚焦时存在:放在外面的话每张卡片都会一直跑,待机后也停不下来。
    // 返回的是 State 而不是 Float,**必须在 drawBehind 里读**:在组合期读会让
    // 聚焦卡片按刷新率整片重组(Image、渐变全跟着重跑),而真正需要重来的只有绘制。
    val breath: State<Float>? = if (focused) rememberGlowBreath() else null
    val scale by animateFloatAsState(
        targetValue = if (focused) Theme.FocusScale else 1f,
        animationSpec = tween(180),
        label = "scale",
    )

    val shape = RoundedCornerShape(metrics.cardCorner)
    Column(
        // 聚焦卡整体(含标题)浮到邻居上面并放大;标题关着时 Column 只有卡片,缩放中心与原来完全一致(零回归)
        modifier = Modifier
            // 聚焦的卡片必须浮到邻居上面:否则右邻居会盖住它的右圆角和整条右侧光晕,
            // 屏幕上表现为「右边被切平」(独立复审实测:少了 11.4px,切口正好落在邻居左边缘)。
            .zIndex(if (focused) 1f else 0f)
            .width(metrics.cardWidth)
            .scale(scale),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = modifier
                .size(metrics.cardWidth, metrics.cardHeight)
                .drawBehind {
                    if (!focused) return@drawBehind
                    // 正弦呼吸:0→1→0
                    val phase = kotlin.math.sin((breath?.value ?: 0f) * 2f * Math.PI).toFloat()
                    val amount = (phase * 0.5f + 0.5f)
                    val radiusPx = metrics.glowRadius.toPx() * (0.80f + 0.20f * amount)
                    val r = metrics.cardCorner.toPx()
                    drawIntoCanvas { canvas ->
                        fun shadowPaint(radius: Float, dx: Float, dy: Float, argb: Int) =
                            android.graphics.Paint().apply {
                                isAntiAlias = true
                                color = android.graphics.Color.TRANSPARENT
                                setShadowLayer(radius, dx, dy, argb)
                            }
                        // ① 先画深色投影。聚焦卡的 zIndex 是 1,所以这层会盖在邻居**上面**,
                        //    正是参考图里那种「邻居顶部几乎不暗、底部最深 18%」的二维压暗。
                        //    不随呼吸变化:参考图里投影是静态的,只有香槟光晕在呼吸。
                        canvas.nativeCanvas.drawRoundRect(
                            0f, 0f, size.width, size.height, r, r,
                            shadowPaint(
                                Theme.ShadowRadius.toPx(),
                                Theme.ShadowDx.toPx(), Theme.ShadowDy.toPx(),
                                Theme.ShadowColor.toArgb(),
                            ),
                        )
                        // ② 再画香槟光晕,压在投影之上
                        canvas.nativeCanvas.drawRoundRect(
                            0f, 0f, size.width, size.height, r, r,
                            shadowPaint(
                                radiusPx, 0f, 0f,
                                glowColor.copy(alpha = 0.88f + 0.12f * amount).toArgb(),
                            ),
                        )
                    }
                }
                .clip(shape)
                // 方形图标也要有卡片底,否则图标浮在黑底上、整行不齐(Projectivy 同样给了底色)
                // 参考图里方形图标**没有**卡片底:整个格子的蓝通道 0–3、槽位边界无台阶。
                // 我先前按资源表的 default_icon_bg 加了 #333333 是错的——那个资源存在,
                // 但不用在这个位置(资源存在 ≠ 在这里生效)。
                // 无横幅回落卡:图标主色作底(design §2.3);有横幅/自定义图仍透明。
                .background(if (fallbackColor != null && app.card != null && !app.isWide) fallbackColor else Color.Transparent)
                .focusProperties {
                    if (isRowStart) left = FocusRequester.Cancel
                    if (isRowEnd) right = FocusRequester.Cancel
                    if (isLastRow) down = FocusRequester.Cancel
                    else downTarget?.let { down = it }
                    if (isFirstRow) up = FocusRequester.Cancel
                    else upTarget?.let { up = it }
                }
                .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = app.card
            if (bmp != null && app.isWide) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = app.label,
                    // 保比例留边,不裁切:复审实测参考里虎牙横幅只有 243.3px 宽(卡片 256),
                    // 聚焦卡 331.9 而我们 334.8;而**高度只差 0.14px**——缩放会同时改两者,
                    // 只有填充方式不会,所以差的是裁切方式。
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(metrics.cardWidth, metrics.cardHeight),
                )
            } else if (bmp != null) {
                // 方形图标:居中留边,不拉伸也不裁切
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
                        fontFamily = Theme.Sans,
                        color = Theme.CardFallbackText, fontSize = 15.sp, textAlign = TextAlign.Center,
                    ),
                )
            }
        }
        if (title != null) {
            BasicText(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    color = Theme.RowTitle.copy(alpha = 0.85f),
                    fontSize = Theme.CardTitleSize,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.padding(top = Theme.CardTitleGap).width(metrics.cardWidth).height(Theme.CardTitleLine),
            )
        }
    }
}
