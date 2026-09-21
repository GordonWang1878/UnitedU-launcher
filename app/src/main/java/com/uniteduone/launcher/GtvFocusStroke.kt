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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Google TV 的内容卡焦点画法(实测):**不缩放**,在布局框外扩 [GtvLayout.FOCUS_OUTSET] dp 处
 * 画一圈 [GtvLayout.FOCUS_STROKE] dp 的描边。
 *
 * 为什么不用 `Modifier.border`:border 画在布局框**上**,画不到框外。`drawBehind` 的画布不受
 * 布局框限制(只要父链上没有 clip),所以用负偏移把矩形撑出去。
 * **父容器不能 clip**:行容器已经有 `wrapContentWidth(unbounded)`,别再加 `clipToBounds`。
 *
 * Fix 3(owner 反馈 R2,2026-09-20):`focused` 曾经直接门控这条 `drawBehind`——瞬间出现 / 瞬间
 * 消失,是「焦点一下子跳到这、一下子跳到那」的卡顿感来源之一(decision B1 去掉聚焦缩放之后,
 * 描边是唯一的连续性提示,却完全没有过渡)。改成 `composed {}`:每个调用点(AppCard/AddCard/
 * MissingCard/RowIconPicker,共 6 处 `.gtvFocusStroke(...)` 调用)各自带一份独立的
 * `animateFloatAsState`,只 animate 这一圈描边的**可见度**——`focused` 参数本身的语义、以及
 * 调用方各自 `onFocusChanged` 里上报给焦点账本的时机,都不受影响,保持即时(动画不门控焦点逻辑,
 * 焦点铁律)。选 `composed` 而不是要求调用方自带 `Animatable`/改签名:六个调用点零改动,
 * 各自独立动画状态,互不干扰(同一张卡两条 `gtvFocusStroke`——focused 一条、moving 一条——
 * 各自淡入淡出,不共用一个 alpha)。
 * 时长见 [GtvLayout.FOCUS_FADE_IN_MS] / [GtvLayout.FOCUS_FADE_OUT_MS] 的 KDoc(是否量到了
 * Google 真值,还是占位)。
 */
fun Modifier.gtvFocusStroke(focused: Boolean, color: Color, corner: Dp): Modifier = composed {
    val alpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (focused) GtvLayout.FOCUS_FADE_IN_MS else GtvLayout.FOCUS_FADE_OUT_MS,
            easing = Theme.MotionEasing,
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
