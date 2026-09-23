package com.uniteduone.launcher

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 胶囊右端画什么(R71,效果图 README 第 5、6 条):**右端永远表示「状态」**——第二层显示当前值,
 * 第三层(选项层)已保存那一档显示 ✓,可进入的项显示 ›。用户只要学一种读法。
 */
sealed interface Trailing {
    data object None : Trailing
    /** 可进入的项(进下一层,或打开一个整屏界面)。 */
    data object Chevron : Trailing
    /** 选项层里已保存的那一档:未聚焦主题色、聚焦黑色(与焦点填色是两个维度,不会搞混)。 */
    data object Check : Trailing
    /**
     * 当前值:同一行右对齐、颜色更淡(未聚焦 `MenuItemText` 55%,聚焦对比色 55%),字号与标签相同——主次靠颜色,不靠缩小字号。
     * [dot] 非空时值前面画一个色点(主题色);[chevron] = 值后面再跟一个 ›(带值的可进入项,如「恢复隐藏的输入源 2 个 ›」)。
     */
    data class Value(val text: String, val dot: Color? = null, val chevron: Boolean = false) : Trailing
}

/**
 * 滑块胶囊聚焦时画的东西(效果图 M4):标签、轨道(3 dp 高,已填黑 78% / 未填黑 16%)、11 dp 把手、`‹ 40% ›`。
 * [fraction] = 把手位置 0..1;[zero] = 填充起点(双向滑块是 0.5,从正中画到把手)。
 */
data class SliderLook(
    val fraction: Float,
    val zero: Float,
    val text: String,
    val canDecrease: Boolean,
    val canIncrease: Boolean,
)

/**
 * 一颗胶囊。宽 [GtvLayout.MENU_ITEM_WIDTH],高至少 [GtvLayout.MENU_ITEM_HEIGHT](两行说明文字时按内容撑高,见 [hint]),
 * 全圆角;聚焦填主题 accent(150 ms 淡入淡出)、文字按亮度取对比色;未聚焦填 [GtvTokens.MenuItemIdle]。
 *
 * 从 `GearMenu` 里抽出来(R69):设置页外壳每一层、关于页、长按卡片菜单、编辑页的两个菜单共用这一颗。
 * 长按 / 编辑页菜单只传 [label](单行、没有右端内容),像素与抽出前逐位相同。
 *
 * 焦点:上下在首末项 `Cancel`、左右恒 `Cancel`(焦点永远出不了这一列);得失都经 [onFocusChange] 上报(铁律 4)。
 * [onStep] 非空 = 滑块:左右键在 `onKeyEvent` 里消费(按下那一下调一格,抬起也吞掉),焦点一格不横移。
 *
 * @param hint Ruling R17 的第二行说明小字(外壳第一层用;null / 空白 = 单行)。
 * @param slider 非空且聚焦时画成滑块;未聚焦时照 [trailing] 画值。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MenuPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
    isFirst: Boolean = false,
    isLast: Boolean = false,
    hint: String? = null,
    trailing: Trailing = Trailing.None,
    slider: SliderLook? = null,
    onStep: ((Int) -> Unit)? = null,
    /** 标签前的色点(主题色选项层:每一档画自己的预设色)。 */
    leadingDot: Color? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalThemeColors.current.accent
    // Fix 3(owner 反馈 R2,2026-09-20):填色 150 ms 过渡,时长与曲线与卡片焦点共用(见 GtvLayout.FOCUS_FADE_IN_MS)。
    // 文字色只在两态之间瞬切(文字做透明度过渡会有一瞬对比度不够)。
    val fill by animateColorAsState(
        targetValue = if (focused) accent else GtvTokens.MenuItemIdle,
        animationSpec = tween(
            durationMillis = if (focused) GtvLayout.FOCUS_FADE_IN_MS else GtvLayout.FOCUS_FADE_OUT_MS,
            easing = Theme.AppFocusEasing,
        ),
        label = "menuPillFill",
    )
    val textColor = if (focused) contrastingTextColor(accent) else Theme.MenuItemText
    // clickable() 默认的 indication 会在聚焦时叠一层约 10% 黑的状态层,把 accent 拉暗成另一个颜色——
    // 填色本身已经是完整的聚焦指示,关掉。
    val interactionSource = remember { MutableInteractionSource() }
    val hasHint = !hint.isNullOrBlank()
    Box(
        modifier = modifier
            .width(GtvLayout.MENU_ITEM_WIDTH.dp)
            .heightIn(min = GtvLayout.MENU_ITEM_HEIGHT.dp)
            .focusProperties {
                if (isFirst) up = FocusRequester.Cancel
                if (isLast) down = FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            .clip(RoundedCornerShape(percent = 50))
            .background(fill)
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .then(
                if (onStep != null) Modifier.onKeyEvent { ke ->
                    when (ke.key) {
                        Key.DirectionLeft -> { if (ke.type == KeyEventType.KeyDown) onStep(-1); true }
                        Key.DirectionRight -> { if (ke.type == KeyEventType.KeyDown) onStep(+1); true }
                        else -> false
                    }
                } else Modifier,
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = GtvLayout.MENU_ITEM_PADDING_H.dp, vertical = GtvLayout.MENU_ITEM_PADDING_V.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (focused && slider != null) {
            SliderContent(label, slider, textColor)
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingDot != null) {
                // 细描边:聚焦时胶囊填的就是主题色,光标停在某个预设上时它的色点与底色同色,没有这圈描边就看不见了。
                Box(
                    Modifier.size(12.dp).clip(CircleShape).background(leadingDot)
                        .border(1.dp, textColor.copy(alpha = 0.35f), CircleShape),
                )
                Spacer(Modifier.width(10.dp))
            }
            if (hasHint) {
                // 两行胶囊(外壳第一层):标题 + 说明占满剩余宽度,右端只可能是一个 ›(自然宽度)。
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(GtvLayout.MENU_ITEM_HINT_GAP.dp),
                ) {
                    MenuPillLabel(label, focused, textColor)
                    BasicText(
                        text = hint!!,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontFamily = Theme.Sans,
                            fontWeight = FontWeight.Normal,
                            // 从属于标题的次要文字(R17):同一个文字色减透明度,两态都算得出更淡的版本。
                            color = textColor.copy(alpha = 0.7f),
                            fontSize = GtvLayout.MENU_ITEM_HINT_TEXT.sp,
                        ),
                    )
                }
                if (trailing != Trailing.None) {
                    Spacer(Modifier.width(12.dp))
                    TrailingContent(trailing, focused, textColor, accent)
                }
            } else {
                MenuPillLabel(label, focused, textColor)
                if (trailing != Trailing.None) {
                    // 标签先量(不加权),右端内容拿剩下的宽度、右对齐、放不下就省略号——标签永远完整。
                    Box(Modifier.weight(1f).padding(start = 12.dp), contentAlignment = Alignment.CenterEnd) {
                        TrailingContent(trailing, focused, textColor, accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrailingContent(trailing: Trailing, focused: Boolean, textColor: Color, accent: Color) {
    val faded = textColor.copy(alpha = 0.55f)
    val style = TextStyle(fontFamily = Theme.Sans, fontSize = GtvLayout.MENU_ITEM_TEXT.sp, color = faded)
    when (trailing) {
        Trailing.None -> Unit
        Trailing.Chevron -> BasicText("›", style = style.copy(fontSize = (GtvLayout.MENU_ITEM_TEXT + 4).sp))
        Trailing.Check -> BasicText(
            "✓",
            style = style.copy(color = if (focused) textColor else accent, fontWeight = FontWeight.Medium),
        )
        is Trailing.Value -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (trailing.dot != null) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(trailing.dot))
                Spacer(Modifier.width(6.dp))
            }
            BasicText(
                text = trailing.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = style.copy(textAlign = TextAlign.End),
                modifier = Modifier.weight(1f, fill = false),
            )
            if (trailing.chevron) {
                Spacer(Modifier.width(8.dp))
                BasicText("›", style = style.copy(fontSize = (GtvLayout.MENU_ITEM_TEXT + 4).sp))
            }
        }
    }
}

/** 聚焦的滑块胶囊:标签 · 轨道 + 把手 · `‹ 值 ›`。标签过长(英文)时省略,轨道至少留 28 dp。 */
@Composable
private fun SliderContent(label: String, look: SliderLook, textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        BasicText(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 104.dp),
            style = TextStyle(
                fontFamily = Theme.Sans,
                fontWeight = FontWeight.Medium,
                color = textColor,
                fontSize = GtvLayout.MENU_ITEM_TEXT.sp,
            ),
        )
        Spacer(Modifier.width(10.dp))
        SliderTrack(look, textColor, Modifier.weight(1f).widthIn(min = 28.dp))
        Spacer(Modifier.width(8.dp))
        val arrow = TextStyle(fontFamily = Theme.Sans, fontSize = GtvLayout.MENU_ITEM_TEXT.sp)
        // 到头的那一侧箭头更淡:告诉人这个方向已经按不动了。
        BasicText("‹", style = arrow.copy(color = textColor.copy(alpha = if (look.canDecrease) 0.45f else 0.15f)))
        Spacer(Modifier.width(4.dp))
        BasicText(
            look.text,
            maxLines = 1,
            style = TextStyle(
                fontFamily = Theme.Sans,
                fontWeight = FontWeight.Medium,
                color = textColor,
                fontSize = GtvLayout.MENU_ITEM_TEXT.sp,
            ),
        )
        Spacer(Modifier.width(4.dp))
        BasicText("›", style = arrow.copy(color = textColor.copy(alpha = if (look.canIncrease) 0.45f else 0.15f)))
    }
}

/** 轨道 3 dp 高、把手 11 dp(效果图 README「新定的三个值」)。自己量、自己摆,不用 Slider 控件(不可聚焦的纯绘制)。 */
@Composable
private fun SliderTrack(look: SliderLook, color: Color, modifier: Modifier) {
    val knob = 11.dp
    Layout(
        modifier = modifier.height(knob),
        content = {
            Box(Modifier.clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = 0.16f)))
            Box(Modifier.clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = 0.78f)))
            Box(Modifier.clip(CircleShape).background(color))
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val k = knob.roundToPx()
        val trackH = 3.dp.roundToPx()
        val usable = (w - k).coerceAtLeast(1)
        val at = { f: Float -> (k / 2 + usable * f.coerceIn(0f, 1f)).toInt() }
        val lo = minOf(at(look.fraction), at(look.zero))
        val hi = maxOf(at(look.fraction), at(look.zero))
        val bg = measurables[0].measure(androidx.compose.ui.unit.Constraints.fixed(w, trackH))
        val filled = measurables[1].measure(androidx.compose.ui.unit.Constraints.fixed((hi - lo).coerceAtLeast(0), trackH))
        val kn = measurables[2].measure(androidx.compose.ui.unit.Constraints.fixed(k, k))
        layout(w, k) {
            bg.place(0, (k - trackH) / 2)
            filled.place(lo, (k - trackH) / 2)
            kn.place(at(look.fraction) - k / 2, 0)
        }
    }
}

/** [MenuPill] 的标题行,单行/两行两种布局共用,避免样式在两处漂移。 */
@Composable
private fun MenuPillLabel(label: String, focused: Boolean, textColor: Color) {
    BasicText(
        text = label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            fontFamily = Theme.Sans,
            fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
            color = textColor,
            fontSize = GtvLayout.MENU_ITEM_TEXT.sp,
        ),
    )
}

/** 按 WCAG 相对亮度选深/浅文字色,不写死一种——accent 是用户选的,白/黑两端都可能出现。 */
internal fun contrastingTextColor(fill: Color): Color =
    if (fill.luminance() > 0.5f) Color.Black else Color.White
