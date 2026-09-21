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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
