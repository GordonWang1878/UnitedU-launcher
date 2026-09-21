package com.uniteduone.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **Ruling R28(2026-09-21,owner 真机反馈 Round 7)**:焦点柔光——在描边**外缘**之外再铺一层
 * 按指数衰减的大面积辉光,是 owner 说「从沙发上完全感觉不到动效」的正解(2dp 细环在 150ms 内
 * 淡入,那个距离上肉眼捕捉不到;30dp 的柔光捕捉得到)。数值出处、实测剖面表、为什么不用
 * `BlurMaskFilter`,全部见 [GtvLayout.APP_FOCUS_GLOW_DP] 一族常量的 KDoc。
 *
 * 画法:一串首尾相接的同心圆角矩形描边,每圈宽 [GtvLayout.APP_FOCUS_GLOW_RING_DP],
 * alpha 走 [GtvLayout.focusGlowAlpha];圆角半径随外扩距离同步增大(与本文件既有的
 * `r = corner + (outX + outY) / 2` 同一写法)。
 *
 * **柔光会铺出卡片间距之外**:30dp > `GtvLayout.CARD_GAP`(20dp),也大于卡片上方到上一行标题
 * 的 15dp(`rowVerticalPad` 7 + `ROW_GAP` 8),所以它必然会淡淡地盖到邻居卡与上一行标题区——
 * Google 那份实测剖面本身就是这样(它的行距比我们还紧),不是 bug,别为此砍短柔光。
 * 一处**已知的不对称**,留给装机复核:同一行里的卡片按组合顺序绘制,焦点卡左边的邻居先画、
 * 会被柔光盖住,右边的邻居后画、反而盖住柔光。真要对称得给焦点卡加 `Modifier.zIndex`,
 * 那会动到焦点相关的 modifier 链,R28 没有顺手改——d≥20dp 处 alpha 只剩 0.071→0.048,
 * 先看真机上能不能觉察。
 *
 * **这是绘制、不是布局**:整段画在 `drawBehind` 里,不改变任何测量尺寸。
 * `GtvLayout.appFocusOverflow`、`rowPitch`、`Theme.gtvCardMetrics.rowVerticalPad` 里**都没有**
 * 柔光这一项,**也不要"顺手补全"**——加进去会把行间距撑开 30dp,破坏已经与 Google 对齐的
 * 纵向节奏(理由与实测依据见 [GtvLayout.appFocusOverflow] 与 [GtvLayout.APP_FOCUS_GLOW_DP])。
 *
 * @param edgeX 布局框左/右边到**描边外缘**的距离(px)。注意是外缘,不是 `drawRoundRect` 那个
 *   走中心线的偏移量——调用方要自己加上半个描边宽。
 * @param edgeY 同上,上/下方向。
 * @param cornerAtEdge 描边外缘处的圆角半径(px)。
 * @param alpha 焦点动画的整体可见度(与描边共用同一份 `motionSpec`:柔光是焦点处理的一部分,
 *   不另起时长);调用方已确保 > 0 才调进来。
 */
private fun DrawScope.drawFocusGlow(
    edgeX: Float,
    edgeY: Float,
    cornerAtEdge: Float,
    color: Color,
    alpha: Float,
) {
    if (alpha <= 0f) return
    val ringDp = GtvLayout.APP_FOCUS_GLOW_RING_DP
    val ringPx = ringDp.dp.toPx()
    val rings = (GtvLayout.APP_FOCUS_GLOW_DP / ringDp).toInt()
    for (i in 0 until rings) {
        // 第 i 圈的中心线落在距描边外缘 (i + 0.5) × ringDp 处:圈与圈首尾相接,合起来正好铺满
        // 0 → APP_FOCUS_GLOW_DP,不重叠也不留缝。
        val ringAlpha = GtvLayout.focusGlowAlpha((i + 0.5f) * ringDp) * alpha
        if (ringAlpha <= 0f) continue
        val off = (i + 0.5f) * ringPx
        val r = cornerAtEdge + off
        drawRoundRect(
            color = color.copy(alpha = color.alpha * ringAlpha),
            topLeft = Offset(-(edgeX + off), -(edgeY + off)),
            size = Size(size.width + 2 * (edgeX + off), size.height + 2 * (edgeY + off)),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = ringPx),
        )
    }
}

/**
 * **内容卡**(content card)焦点画法(实测):不缩放,在布局框外扩 [GtvLayout.FOCUS_OUTSET] dp 处
 * 画一圈 [GtvLayout.FOCUS_STROKE] dp 的描边。owner 反馈 Round 4(2026-09-21)之后,gtv 线首页的
 * 应用卡片已经全部改用 [gtvAppFocusFrame](app tile 的缩放 + 描边处理,见该函数 KDoc);这个
 * 函数继续被 `RowIconPicker`(小网格图标,不是 app)与 `gtvAppFocusFrame` 的 `moving` 分支
 * (首页原地移动态的高亮描边,Google 没有对应物)使用,不是死代码。
 *
 * 为什么不用 `Modifier.border`:border 画在布局框**上**,画不到框外。`drawBehind` 的画布不受
 * 布局框限制(只要父链上没有 clip),所以用负偏移把矩形撑出去。
 * **父容器不能 clip**:行容器已经有 `wrapContentWidth(unbounded)`,别再加 `clipToBounds`。
 *
 * Fix 3(owner 反馈 R2,2026-09-20):`focused` 曾经直接门控这条 `drawBehind`——瞬间出现 / 瞬间
 * 消失,是「焦点一下子跳到这、一下子跳到那」的卡顿感来源之一。改成 `composed {}`:每个调用点
 * 各自带一份独立的 `animateFloatAsState`,只 animate 这一圈描边的**可见度**——`focused` 参数
 * 本身的语义、以及调用方各自 `onFocusChanged` 里上报给焦点账本的时机,都不受影响,保持即时
 * (动画不门控焦点逻辑,焦点铁律)。
 * 时长/曲线见 [GtvLayout.FOCUS_FADE_IN_MS]/[GtvLayout.FOCUS_FADE_OUT_MS]/[Theme.AppFocusEasing]
 * 的 KDoc(owner 反馈 Round 4 起已是 Google 实测值,不再是占位)。
 *
 * **Ruling R28(owner 反馈 Round 7)**:描边外缘之外再铺一层柔光([drawFocusGlow]),几何基准
 * 换成这个函数自己的 `FOCUS_OUTSET`/`FOCUS_STROKE`,alpha 与描边共用同一个动画量。
 */
fun Modifier.gtvFocusStroke(focused: Boolean, color: Color, corner: Dp): Modifier = composed {
    val alpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (focused) GtvLayout.FOCUS_FADE_IN_MS else GtvLayout.FOCUS_FADE_OUT_MS,
            easing = Theme.AppFocusEasing,
        ),
        label = "gtvFocusStrokeAlpha",
    )
    this.drawBehind {
        if (alpha <= 0f) return@drawBehind
        val out = GtvLayout.FOCUS_OUTSET.dp.toPx()
        val w = GtvLayout.FOCUS_STROKE.dp.toPx()
        val r = (corner.toPx() + out)
        // R28 柔光(先画,描边盖在上面才保持清脆):几何基准是这个函数自己的
        // FOCUS_OUTSET/FOCUS_STROKE——描边走中心线,外缘在 out + w/2 处。柔光的三个数值
        // (总距离/峰值/半衰期)是从 app tile 那份实测剖面**借用**的,不是又给内容卡单独量了
        // 一份;与 FOCUS_FADE_IN_MS/OUT_MS 同一种借用关系(见那两个常量的 KDoc),如实记录。
        drawFocusGlow(
            edgeX = out + w / 2f,
            edgeY = out + w / 2f,
            cornerAtEdge = r + w / 2f,
            color = color,
            alpha = alpha,
        )
        drawRoundRect(
            color = color.copy(alpha = color.alpha * alpha),
            topLeft = Offset(-out, -out),
            size = Size(size.width + 2 * out, size.height + 2 * out),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = w),
        )
    }
}

/**
 * **app tile**(应用图块)聚焦画法——owner 反馈 Round 4(2026-09-21):controller 指出这条线的
 * 首页 100% 是 app,不是 Google 无边界推荐流里的 content card;之前套用 content card 的静态
 * 外扩描边(只因为我们的卡片形状恰好也是 16:9),是错认了 Google 的分类。Google 对 app tile
 * 的真实处理是**聚焦放大 [GtvLayout.APP_FOCUS_SCALE] 倍**(旧版 launcherx APK
 * `animator/card_focus`/`card_unfocus`,`duration` 引用
 * `@integer/default_focused_animation_duration_ms`,`interpolator` 属性缺失 → 平台默认
 * `AccelerateDecelerateInterpolator`,即 [Theme.AppFocusEasing]),描边贴着**缩放后**的边缘
 * 外扩 [GtvLayout.APP_FOCUS_GAP] + [GtvLayout.APP_FOCUS_STROKE]。
 *
 * **缩放不能影响布局**(owner 反馈原话「no layout change」):用 `graphicsLayer(scaleX/scaleY)`
 * 而不是 `Modifier.scale()` 或改 `.size()`——前者只在绘制阶段变换像素,父级看到的测量尺寸
 * 始终是未缩放的 `cardWidth × cardHeight`,卡片下方的标题文字(`AppCard` 里的兄弟节点,按
 * 未缩放的布局尺寸定位)不会跟着跳动或错位。
 *
 * **描边为什么不能简单套一层 `graphicsLayer` 就跟着放大**:Google 的间隙/描边宽度是固定 dp
 * 值,不随缩放倍数变粗——如果描边跟着卡片一起进同一个 `graphicsLayer`,描边本身的粗细也会被
 * 放大 1.105 倍,不符实测。所以这里的画法是:描边在 `drawBehind` 里**手动**按「未缩放尺寸 ×
 * 当前动画中的 scale 值」算出缩放后边缘的位置,再往外加固定的 gap/stroke,画完之后才对
 * **后续**的实际内容(卡片背景/图片/圆角裁剪)应用 `graphicsLayer` 缩放——`drawBehind` 在
 * `graphicsLayer` 之前(链上更外层),不受它影响,数值计算与视觉缩放各管一段。
 *
 * **Ruling R28(owner 反馈 Round 7)**:描边只是焦点处理的一半,另一半是描边外面那层大面积
 * 柔光([drawFocusGlow]);Google 有、我们此前一点没画,这才是 owner 说「从沙发上完全感觉不到
 * 动效」的主因。柔光的几何与描边同源(同样跟着 `scale` 长大)、alpha 与描边共用同一个
 * `ringAlpha`,**但只是绘制,不进任何布局量**(见 [GtvLayout.appFocusOverflow] 的 KDoc)。
 *
 * **绘制顺序**:柔光 → 聚焦描边 → 移动描边,由外向内、后画的盖在先画的上面。
 *
 * @param moving 首页原地移动态(M4b):被搬的那张卡的高亮描边,与 [gtvFocusStroke] 的 `moving`
 *   分支是同一件事、同一套固定外扩几何(不随 `focused` 的缩放变化——它标的是「正在搬哪张」,
 *   与 Google 的 app 聚焦缩放无关)。默认 `false`(`AddCard` 没有搬运概念,不传)。
 *   **绘制顺序**:聚焦描边先画、移动描边后画、盖在上面——两者都在缩放之外(同一个
 *   `drawBehind`,不是分成两次 `gtvFocusStroke`/`gtvAppFocusFrame` 调用叠链),不会因为
 *   `graphicsLayer` 在中间插了一刀而让后画的移动描边被意外裹进缩放里。
 */
fun Modifier.gtvAppFocusFrame(
    focused: Boolean,
    accentColor: Color,
    corner: Dp,
    moving: Boolean = false,
    movingColor: Color = Color.Unspecified,
): Modifier = composed {
    val motionSpec = tween<Float>(
        durationMillis = if (focused) GtvLayout.FOCUS_FADE_IN_MS else GtvLayout.FOCUS_FADE_OUT_MS,
        easing = Theme.AppFocusEasing,
    )
    val scale by animateFloatAsState(
        targetValue = if (focused) GtvLayout.APP_FOCUS_SCALE else 1f,
        animationSpec = motionSpec,
        label = "gtvAppFocusScale",
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = motionSpec,
        label = "gtvAppFocusRingAlpha",
    )
    this
        .drawBehind {
            if (ringAlpha > 0f) {
                val growX = size.width * (scale - 1f) / 2f
                val growY = size.height * (scale - 1f) / 2f
                val gap = GtvLayout.APP_FOCUS_GAP.dp.toPx()
                val stroke = GtvLayout.APP_FOCUS_STROKE.dp.toPx()
                val outX = growX + gap + stroke / 2f
                val outY = growY + gap + stroke / 2f
                val r = corner.toPx() + (outX + outY) / 2f
                // R28 柔光:几何与描边同源(outX/outY 里已经含了当前动画中的 scale,所以柔光
                // 跟着卡片一起长大),透明度与描边共用同一个 ringAlpha —— 同一份 motionSpec,
                // 150ms AccelerateDecelerate,柔光是焦点处理的一部分,不另起时长。
                // 描边走中心线,外缘在 outX + stroke/2 处;先画柔光、描边盖在上面。
                drawFocusGlow(
                    edgeX = outX + stroke / 2f,
                    edgeY = outY + stroke / 2f,
                    cornerAtEdge = r + stroke / 2f,
                    color = accentColor,
                    alpha = ringAlpha,
                )
                drawRoundRect(
                    color = accentColor.copy(alpha = accentColor.alpha * ringAlpha),
                    topLeft = Offset(-outX, -outY),
                    size = Size(size.width + 2 * outX, size.height + 2 * outY),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = stroke),
                )
            }
            if (moving) {
                // 与 gtvFocusStroke 的 moving 分支同一套固定几何,不随 scale 变化(见函数 KDoc)。
                val out = GtvLayout.FOCUS_OUTSET.dp.toPx()
                val w = GtvLayout.FOCUS_STROKE.dp.toPx()
                val r = corner.toPx() + out
                drawRoundRect(
                    color = movingColor,
                    topLeft = Offset(-out, -out),
                    size = Size(size.width + 2 * out, size.height + 2 * out),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = w),
                )
            }
        }
        .graphicsLayer(scaleX = scale, scaleY = scale)
}
