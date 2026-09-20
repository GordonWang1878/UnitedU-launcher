package com.uniteduone.launcher

import androidx.compose.ui.Modifier
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
 */
fun Modifier.gtvFocusStroke(focused: Boolean, color: Color, corner: Dp): Modifier =
    this.drawBehind {
        if (!focused) return@drawBehind
        val out = GtvLayout.FOCUS_OUTSET.dp.toPx()
        val w = GtvLayout.FOCUS_STROKE.dp.toPx()
        val r = (corner.toPx() + out)
        drawRoundRect(
            color = color,
            topLeft = Offset(-out, -out),
            size = Size(size.width + 2 * out, size.height + 2 * out),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = w),
        )
    }
