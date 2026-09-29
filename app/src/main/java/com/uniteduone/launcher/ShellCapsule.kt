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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
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
    /** 滑块展开时标签位的文字;null = 用胶囊标签。英文全称放不进让出轨道后剩下的约 104 dp,换短名。 */
    val label: String? = null,
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
 * @param hint Ruling R17 的第二行说明小字(外壳第一层用;R127 起屏保组「关闭屏幕」行也用,与右端的值同时出现;
 *   null / 空白 = 单行)。
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
    /**
     * 字号在基准([GtvLayout.MENU_ITEM_TEXT] / [GtvLayout.MENU_ITEM_HINT_TEXT])上加多少 sp(R109):设置类页面的胶囊列
     * ([CapsuleColumn])传 [GtvLayout.SETTINGS_TYPE_STEP];长按 / 编辑页菜单不传,仍是基准。胶囊高度不变。
     */
    textStep: Float = 0f,
) {
    val type = PillType(textStep)
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
    // 值放不进同一行(英文「System Animation Scale」配「1.5×, UI animations run slower」这类整句摘要)时,
    // 改成两行胶囊:值挪到标签下面当说明,右端只留 ›(交互测试 2026-09-23,评审 #4)。按**聚焦加粗**的宽度判,
    // 聚焦前后同一个结论,胶囊高度不会随焦点跳。
    // R127:带说明小字([hint])的胶囊也可能带值(「关闭屏幕」行)——同一个判据:放得下就「标签 … 值」一行、说明在下;
    // 放不下值挪到标签下面、说明再往下一行。
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val valueWraps = trailing is Trailing.Value && slider == null &&
        remember(label, trailing, density, type) {
            val style = TextStyle(fontFamily = Theme.Sans, fontSize = type.text.sp)
            val labelW = measurer.measure(label, style.copy(fontWeight = FontWeight.Medium)).size.width
            val valueW = measurer.measure(trailing.text, style).size.width
            with(density) {
                val extras = 12.dp.toPx() + (if (trailing.dot != null) 16.dp.toPx() else 0f) +
                    (if (trailing.chevron) 20.dp.toPx() else 0f)
                val inner = (GtvLayout.MENU_ITEM_WIDTH - 2 * GtvLayout.MENU_ITEM_PADDING_H).dp.toPx()
                labelW + valueW + extras > inner
            }
        }
    // 标签下面的小字行:挪下来的值在前,说明在后。没有带值的说明胶囊之前(R127 之前)这里最多一行,与原来逐位相同。
    val secondary = listOfNotNull(
        if (valueWraps) (trailing as Trailing.Value).text else null,
        hint?.takeIf { it.isNotBlank() },
    )
    @Suppress("NAME_SHADOWING")
    val trailing = if (valueWraps) {
        if ((trailing as Trailing.Value).chevron) Trailing.Chevron else Trailing.None
    } else trailing
    val hasHint = secondary.isNotEmpty()
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
            SliderContent(slider.label ?: label, slider, textColor, type)
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
                // R127:带值且放得下(valueWraps 已判过)时第一行是「标签 … 值」,说明在下面占满整宽。
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(GtvLayout.MENU_ITEM_HINT_GAP.dp),
                ) {
                    if (trailing is Trailing.Value) {
                        // 放得下才走到这里,值不设 VALUE_MAX_WIDTH 上限(那个上限是给单行胶囊「标签至少留一半」的)。
                        LabelTrailingRow(label, trailing, focused, textColor, accent, type, valueMax = null, Modifier.fillMaxWidth())
                    } else {
                        MenuPillLabel(label, focused, textColor, type)
                    }
                    secondary.forEach { line ->
                        BasicText(
                            text = line,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                fontFamily = Theme.Sans,
                                fontWeight = FontWeight.Normal,
                                // 从属于标题的次要文字(R17):同一个文字色减透明度,两态都算得出更淡的版本。
                                color = textColor.copy(alpha = 0.7f),
                                fontSize = type.hint.sp,
                            ),
                        )
                    }
                }
                if (trailing != Trailing.None && trailing !is Trailing.Value) {
                    Spacer(Modifier.width(12.dp))
                    TrailingContent(trailing, focused, textColor, accent, type)
                }
            } else {
                if (trailing == Trailing.None) {
                    MenuPillLabel(label, focused, textColor, type)
                } else {
                    LabelTrailingRow(label, trailing, focused, textColor, accent, type, valueMax = VALUE_MAX_WIDTH, Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 「标签 … 右端」同一行。右端先量(有 [valueMax] 时以它为上限,再长才省略),标签拿剩下的宽度、放不下就省略号。
 * 交互测试 2026-09-23:原先标签先量、值拿剩下的,英文长标签(System Animation Scale、
 * Follow Wallpaper Color 聚焦加粗后)把值挤成「O…」或整个挤没——值是这一行要看的信息,优先保它。
 * 单行胶囊传 [VALUE_MAX_WIDTH];带说明的两行胶囊(R127)传 null。
 */
@Composable
private fun LabelTrailingRow(
    label: String,
    trailing: Trailing,
    focused: Boolean,
    textColor: Color,
    accent: Color,
    type: PillType,
    valueMax: Float?,
    modifier: Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuPillLabel(label, focused, textColor, type, Modifier.weight(1f, fill = false))
        Box(
            Modifier.padding(start = 12.dp).then(if (valueMax != null) Modifier.widthIn(max = valueMax.dp) else Modifier),
            contentAlignment = Alignment.CenterEnd,
        ) {
            TrailingContent(trailing, focused, textColor, accent, type)
        }
    }
}

@Composable
private fun TrailingContent(trailing: Trailing, focused: Boolean, textColor: Color, accent: Color, type: PillType) {
    val faded = textColor.copy(alpha = 0.55f)
    val style = TextStyle(fontFamily = Theme.Sans, fontSize = type.text.sp, color = faded)
    when (trailing) {
        Trailing.None -> Unit
        Trailing.Chevron -> BasicText("›", style = style.copy(fontSize = type.chevron.sp))
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
                BasicText("›", style = style.copy(fontSize = type.chevron.sp))
            }
        }
    }
}

/** 聚焦的滑块胶囊:标签 · 轨道 + 把手 · `‹ 值 ›`。标签过长(英文)时省略,轨道至少留 28 dp。 */
@Composable
private fun SliderContent(label: String, look: SliderLook, textColor: Color, type: PillType) {
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
                fontSize = type.text.sp,
            ),
        )
        Spacer(Modifier.width(10.dp))
        SliderTrack(look, textColor, Modifier.weight(1f).widthIn(min = 28.dp))
        Spacer(Modifier.width(8.dp))
        val arrow = TextStyle(fontFamily = Theme.Sans, fontSize = type.text.sp)
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
                fontSize = type.text.sp,
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

/** 单行胶囊右端值的宽度上限(约内宽 220 dp 的一半):值优先量,再长的摘要省略,标签至少留一半。 */
private const val VALUE_MAX_WIDTH = 110f

/** [MenuPill] 的标题行,单行/两行两种布局共用,避免样式在两处漂移。 */
@Composable
private fun MenuPillLabel(label: String, focused: Boolean, textColor: Color, type: PillType, modifier: Modifier = Modifier) {
    BasicText(
        text = label,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            fontFamily = Theme.Sans,
            fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
            color = textColor,
            fontSize = type.text.sp,
        ),
    )
}

/**
 * 一颗胶囊里各处文字的字号(sp,R109):标签 / 值 / ✓ / 滑块数值与 ‹ › = [text],第二行说明 = [hint],右端 › = [chevron]
 * (标签 × [GtvLayout.CHEVRON_SCALE],按比例)。[step] = 0 时与 R109 之前逐位相同(16 / 12 / 20)。
 */
data class PillType(val step: Float = 0f) {
    val text: Float get() = GtvLayout.MENU_ITEM_TEXT + step
    val hint: Float get() = GtvLayout.MENU_ITEM_HINT_TEXT + step
    val chevron: Float get() = text * GtvLayout.CHEVRON_SCALE
}

/** 按 WCAG 相对亮度选深/浅文字色,不写死一种——accent 是用户选的,白/黑两端都可能出现。 */
internal fun contrastingTextColor(fill: Color): Color =
    if (fill.luminance() > 0.5f) Color.Black else Color.White
