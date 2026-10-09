package com.uniteduone.launcher

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

/*
 * 编辑桌面「货架」(R165)的界面零件。账本、按键与组装在 EditScreen.kt;数值一律读 ShelfLayout(EditShelves.kt,有单测)。
 * 所有零件:得失焦点都经 onFocusChange 上报(铁律 4);requester 与方向由调用方放在 modifier 里(在可聚焦节点之前);
 * 左右到头在零件里 Cancel,上下由编辑页根节点截获(verticalStep),零件不管。
 */

/**
 * 一层架子的外壳(spec §2.5):玻璃底 + 细边(新的一行是虚线)+ 焦点层的阴影,整层不透明度 [shelfAlpha]。[focus] 在绘制阶段读
 * (0 = 非焦点层,1 = 焦点层,调用方 200 ms 过渡),不改布局、不影响可聚焦性。
 * **整层淡化的离屏层四边各撑大 [ShelfLayout.LAYER_PAD](= 60 dp 柔光)**,外层 `layout` 按原尺寸上报(CLAUDE.md R129f):
 * 换层那 200 ms 新焦点层从 0.62 淡到 1,焦点卡的放大 + 描边 + 柔光画在架子外,不撑大会被离屏层裁掉一截。
 * 不可聚焦、不进任何焦点账本。
 */
@Composable
internal fun ShelfFrame(
    focus: () -> Float,
    dashed: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val pad = ShelfLayout.LAYER_PAD.dp
    Box(
        modifier
            .layout { measurable, constraints ->
                val e = pad.roundToPx()
                val p = measurable.measure(constraints.offset(horizontal = 2 * e, vertical = 2 * e))
                layout((p.width - 2 * e).coerceAtLeast(0), (p.height - 2 * e).coerceAtLeast(0)) { p.place(-e, -e) }
            }
            .graphicsLayer { alpha = shelfAlpha(focus()) }
            .padding(pad)
            .drawBehind { drawShelfGlass(focus(), dashed) },
        content = content,
    )
}

private fun DrawScope.drawShelfGlass(focus: Float, dashed: Boolean) {
    val look = shelfLook(focus, dashed)
    val cr = ShelfLayout.CORNER.dp.toPx()
    if (look.shadow > 0f) {
        // 阴影只画在架子外面(效果图的 box-shadow 不透过玻璃):挖掉架子本身的圆角矩形,一圈圈往外铺、越外越淡
        val hole = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(cr))) }
        clipPath(hole, ClipOp.Difference) {
            val rings = 6
            val spread = ShelfLayout.SHADOW_SPREAD.dp.toPx()
            val dy = ShelfLayout.SHADOW_DY.dp.toPx()
            for (k in 1..rings) {
                val e = spread * k / rings
                drawRoundRect(
                    color = Color.Black.copy(alpha = look.shadow / rings),
                    topLeft = Offset(-e, -e + dy),
                    size = Size(size.width + 2 * e, size.height + 2 * e),
                    cornerRadius = CornerRadius(cr + e),
                )
            }
        }
    }
    drawRoundRect(Color.White.copy(alpha = look.bg), cornerRadius = CornerRadius(cr))
    val sw = ShelfLayout.BORDER.dp.toPx()
    drawRoundRect(
        color = Color.White.copy(alpha = look.border),
        topLeft = Offset(sw / 2, sw / 2),
        size = Size(size.width - sw, size.height - sw),
        cornerRadius = CornerRadius(cr - sw / 2),
        style = Stroke(
            width = sw,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null,
        ),
    )
}

private fun chipLabel(chip: ShelfChip): Int = when (chip) {
    ShelfChip.ADD_APP -> R.string.edit_row_add_app
    ShelfChip.ICON -> R.string.edit_chip_icon
    ShelfChip.UP -> R.string.edit_chip_up
    ShelfChip.DOWN -> R.string.edit_chip_down
    ShelfChip.DELETE -> R.string.edit_chip_delete
    ShelfChip.REAUTHORIZE -> R.string.shelf_chip_reauthorize
}

private fun chipIcon(chip: ShelfChip): ImageVector = when (chip) {
    ShelfChip.ADD_APP -> Icons.Rounded.Add
    ShelfChip.ICON -> Icons.Outlined.Category
    ShelfChip.UP -> Icons.Rounded.ArrowUpward
    ShelfChip.DOWN -> Icons.Rounded.ArrowDownward
    ShelfChip.DELETE -> Icons.Outlined.Delete
    ShelfChip.REAUTHORIZE -> Icons.Rounded.LockOpen
}

/** 一颗矢量图标([boxSize] 见方),颜色在绘制阶段着色。 */
@Composable
internal fun ShelfIcon(vector: ImageVector, tint: Color, boxSize: Dp, modifier: Modifier = Modifier) {
    val painter = rememberVectorPainter(vector)
    Box(modifier.size(boxSize).drawBehind { with(painter) { draw(size, colorFilter = ColorFilter.tint(tint)) } })
}

/**
 * 架子顶部的一颗操作胶囊(spec §2.5):28 dp 高、14 dp 圆角、白 8% 底、12 sp;聚焦填主题 accent、文字按亮度取对比色
 * ([contrastingTextColor],与 `MenuPill` / `CompactPill` 同一套)、放大 1.08 + 一圈影子;填色 / 放大与卡片焦点同一个时长与曲线。
 * [visible] 在绘制阶段读:非焦点层的胶囊淡到 0(效果图 c1 里只有焦点层露出胶囊),但**照常布局、照常可聚焦**——
 * 从上一层卡片按下要能落到它上面,落上之后这一层变成焦点层、胶囊随之淡入。透明度与放大写在同一个 graphicsLayer 里:
 * 放大不会被一个更小的离屏层裁掉。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ShelfChipPill(
    chip: ShelfChip,
    visible: () -> Float,
    onClick: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalThemeColors.current.accent
    val ms = if (focused) GtvLayout.FOCUS_FADE_IN_MS else GtvLayout.FOCUS_FADE_OUT_MS
    val fill by animateColorAsState(
        targetValue = if (focused) accent else Color.White.copy(alpha = ShelfLayout.CHIP_IDLE_ALPHA),
        animationSpec = tween(ms, easing = Theme.AppFocusEasing),
        label = "shelfChipFill",
    )
    val grow by animateFloatAsState(if (focused) 1f else 0f, tween(ms, easing = Theme.AppFocusEasing), label = "shelfChipGrow")
    val ink = if (focused) contrastingTextColor(accent) else Ink.Label
    val shape = RoundedCornerShape(ShelfLayout.CHIP_CORNER.dp)
    Row(
        modifier
            .height(ShelfLayout.CHIP_HEIGHT.dp)
            .graphicsLayer {
                val s = 1f + (ShelfLayout.CHIP_FOCUS_SCALE - 1f) * grow
                scaleX = s; scaleY = s
                alpha = visible().coerceIn(0f, 1f)
                shadowElevation = ShelfLayout.CHIP_SHADOW.dp.toPx() * grow
                this.shape = shape
            }
            .clip(shape)
            .background(fill)
            .focusProperties {
                if (isFirst) left = FocusRequester.Cancel
                if (isLast) right = FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = ShelfLayout.CHIP_PAD_H.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ShelfLayout.CHIP_ICON_GAP.dp),
    ) {
        ShelfIcon(chipIcon(chip), ink, ShelfLayout.CHIP_ICON.dp)
        BasicText(
            text = stringResource(chipLabel(chip)),
            maxLines = 1,
            style = Type.caption.copy(color = ink, fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal),
        )
    }
}

private fun choiceTitle(c: NewRowChoice): Int = when (c) { NewRowChoice.APP_ROW -> R.string.edit_choice_app_row }
private fun choiceDesc(c: NewRowChoice): Int = when (c) { NewRowChoice.APP_ROW -> R.string.edit_choice_app_row_desc }
private fun choiceIcon(c: NewRowChoice): ImageVector = when (c) { NewRowChoice.APP_ROW -> Icons.Rounded.Apps }

/**
 * 「新的一行」里的一张选择卡(c2):300 × 110、圆角 16、白 7% 底;图标(主题色)+ 名字(17 sp)+ 一句说明(12 sp);
 * 聚焦填 accent、文字取对比色、放大 1.05 + 影子。[full](spec §2.4)= 整卡压到 45%、说明换成「已满 5 行」,**仍可聚焦**
 * (好让人看到原因),确定由调用方不响应。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ChoiceCard(
    choice: NewRowChoice,
    full: Boolean,
    onClick: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalThemeColors.current.accent
    val ms = if (focused) GtvLayout.FOCUS_FADE_IN_MS else GtvLayout.FOCUS_FADE_OUT_MS
    val fill by animateColorAsState(
        targetValue = if (focused) accent else Color.White.copy(alpha = ShelfLayout.CHOICE_IDLE_ALPHA),
        animationSpec = tween(ms, easing = Theme.AppFocusEasing),
        label = "choiceFill",
    )
    val grow by animateFloatAsState(if (focused) 1f else 0f, tween(ms, easing = Theme.AppFocusEasing), label = "choiceGrow")
    val ink = if (focused) contrastingTextColor(accent) else Ink.Primary
    val sub = if (focused) contrastingTextColor(accent).copy(alpha = 0.75f) else Ink.Secondary
    val shape = RoundedCornerShape(ShelfLayout.CHOICE_CORNER.dp)
    Column(
        modifier
            .size(ShelfLayout.CHOICE_WIDTH.dp, ShelfLayout.CHOICE_HEIGHT.dp)
            .graphicsLayer {
                val s = 1f + (ShelfLayout.CHOICE_FOCUS_SCALE - 1f) * grow
                scaleX = s; scaleY = s
                alpha = if (full) ShelfLayout.CHOICE_FULL_ALPHA else 1f
                shadowElevation = ShelfLayout.CHOICE_SHADOW.dp.toPx() * grow
                this.shape = shape
            }
            .clip(shape)
            .background(fill)
            .focusProperties {
                if (isFirst) left = FocusRequester.Cancel
                if (isLast) right = FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = ShelfLayout.CHOICE_PAD_H.dp, vertical = ShelfLayout.CHOICE_PAD_V.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ShelfIcon(choiceIcon(choice), if (focused) ink else accent, ShelfLayout.CHOICE_ICON.dp)
        BasicText(stringResource(choiceTitle(choice)), maxLines = 1, style = Type.section.copy(color = ink, fontWeight = FontWeight.Normal))
        BasicText(
            text = if (full) stringResource(R.string.edit_choice_full, MAX_ROWS) else stringResource(choiceDesc(choice)),
            maxLines = 2,
            style = Type.caption.copy(color = sub),
        )
    }
}

/**
 * 空架子在卡片位置的「添加应用」方块(spec §2.1,取代行尾「+」):卡片大小、半透明底、「＋」+ 一行字;聚焦样式同首页卡片
 * (`gtvAppFocusFrame`:放大 + 描边 + 柔光)。它是那一层卡片条里唯一的一格,左右都到头。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AddAppTile(
    metrics: CardMetrics,
    onClick: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalThemeColors.current.accent
    val highlight = LocalThemeColors.current.highlight
    Column(
        modifier
            .gtvAppFocusFrame(focused, accent, metrics.cardCorner)
            .size(metrics.cardWidth, metrics.cardHeight)
            .clip(RoundedCornerShape(metrics.cardCorner))
            .background(GtvTokens.SurfacePlaceholder)
            .focusProperties {
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PlusGlyph(color = highlight, size = 20.dp)
        BasicText(
            text = stringResource(R.string.edit_row_add_app),
            style = Type.caption.copy(color = if (focused) Ink.Primary else Ink.Label),
        )
    }
}

/** 数据还在加载时的中性占位(取代旧 EditScreen 的 PendingCard,多一个行尾锁)。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ShelfPendingCard(
    pkg: String,
    metrics: CardMetrics,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
    isRowStart: Boolean = false,
    isRowEnd: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(metrics.cardWidth, metrics.cardHeight)
            .clip(RoundedCornerShape(metrics.cardCorner))
            .background(if (focused) Theme.PendingCardFocusedBackground else Theme.PendingCardBackground)
            .focusProperties {
                if (isRowStart) left = FocusRequester.Cancel
                if (isRowEnd) right = FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .focusable(interactionSource = remember { MutableInteractionSource() }),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = pkg.substringAfterLast('.'),
            style = Type.micro.copy(color = Ink.Secondary, textAlign = TextAlign.Center),
            modifier = Modifier.padding(6.dp),
        )
    }
}

@Composable
private fun Kbd(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        BasicText(text, style = Type.micro.copy(color = Ink.Primary))
    }
}

/** 页头右边的按键提示(spec §2.1;c1 / c2):每组 = 一两个键帽 + 一个词。纯展示、不可聚焦。 */
@Composable
internal fun EditKeyHints(set: EditHintSet, modifier: Modifier = Modifier) {
    val ok = stringResource(R.string.edit_key_ok)
    val back = stringResource(R.string.edit_key_back)
    val chunks: List<Pair<List<String>, String>> = when (set) {
        EditHintSet.BROWSE -> listOf(
            listOf(ok) to stringResource(R.string.edit_hint_pick),
            listOf("↑") to stringResource(R.string.edit_hint_row),
            listOf(back) to stringResource(R.string.edit_hint_done),
        )
        EditHintSet.NEW_ROW -> listOf(
            listOf("←", "→") to stringResource(R.string.edit_hint_choose),
            listOf(ok) to stringResource(R.string.edit_hint_add),
            listOf(back) to stringResource(R.string.edit_hint_done),
        )
        EditHintSet.CARRY -> listOf(
            listOf("←", "→", "↑", "↓") to stringResource(R.string.edit_hint_move),
            listOf(ok) to stringResource(R.string.edit_hint_drop),
            listOf(back) to stringResource(R.string.edit_hint_cancel),
        )
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        chunks.forEachIndexed { i, (keys, label) ->
            if (i > 0) Spacer(Modifier.width(ShelfLayout.HINT_GAP.dp))
            keys.forEach { k -> Kbd(k); Spacer(Modifier.width(4.dp)) }
            BasicText(label, style = Type.caption.copy(color = Ink.Secondary))
        }
    }
}

/**
 * 拿起时卡片四周的方向箭头(照 Google TV `gtv-04`:只画挪得动的方向,[carryArrows] 算)。画在卡片框外 [ShelfLayout.ARROW_GAP]
 * (放大溢出之外),实心小三角,主题 highlight 色。只是绘制,不改布局、不进焦点。
 */
internal fun Modifier.carryArrows(dirs: Set<MoveDir>, color: Color): Modifier = drawWithContent {
    drawContent()
    if (dirs.isEmpty()) return@drawWithContent
    val gap = ShelfLayout.ARROW_GAP.dp.toPx()
    val s = ShelfLayout.ARROW_SIZE.dp.toPx()
    val cx = size.width / 2
    val cy = size.height / 2
    fun tri(a: Offset, b: Offset, c: Offset) =
        drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() }, color)
    if (MoveDir.LEFT in dirs) tri(Offset(-gap - s, cy), Offset(-gap, cy - s), Offset(-gap, cy + s))
    if (MoveDir.RIGHT in dirs) tri(Offset(size.width + gap + s, cy), Offset(size.width + gap, cy - s), Offset(size.width + gap, cy + s))
    if (MoveDir.UP in dirs) tri(Offset(cx, -gap - s), Offset(cx - s, -gap), Offset(cx + s, -gap))
    if (MoveDir.DOWN in dirs) tri(Offset(cx, size.height + gap + s), Offset(cx - s, size.height + gap), Offset(cx + s, size.height + gap))
}

/**
 * 一层频道架子(R164 / R165 §2.1):顶行 = 频道图标 +「应用名 · 频道名」+ 小标签「频道」+ 胶囊;下面是海报预览或状态文字。
 * 高度同应用架子(海报预览高 = 应用卡高),纵向位移的累计不需要区分种类。
 * **焦点**:可聚焦的只有胶囊,写法与 `AppShelfView` 的胶囊行逐字相同——逐项 requester(按 `ShelfSpot` 取)、得失都上报(铁律 4)、
 * 量水平中心;不新增任何焦点状态,恢复由编辑页的账本(目标 / 持有者、看门狗、显式重定位)负责。
 */
@Composable
internal fun ChannelShelfView(
    shelf: Shelf.ChannelShelf,
    chips: List<ShelfChip>,
    focus: () -> Float,
    active: Boolean,
    accent: Color,
    loadPosters: Boolean,
    req: (ShelfSpot) -> FocusRequester,
    report: (ShelfSpot, Boolean) -> Unit,
    place: (ShelfSpot, Float) -> Unit,
    onChip: (ShelfChip) -> Unit,
    modifier: Modifier,
) {
    val si = shelf.row
    ShelfFrame(focus = focus, dashed = false, modifier = modifier.fillMaxWidth().height(ShelfLayout.APP_SHELF_HEIGHT.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = ShelfLayout.PAD_START.dp, end = ShelfLayout.PAD_END.dp, top = ShelfLayout.HEADER_TOP.dp)
                .height(ShelfLayout.HEADER_HEIGHT.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowIcon(CHANNEL_ROW_ICON, tint = { if (active) accent else Ink.Secondary }, boxSize = ShelfLayout.ICON.dp)
            Spacer(Modifier.width(ShelfLayout.ICON_GAP.dp))
            BasicText(
                stringResource(R.string.channel_row_title, shelf.appLabel, shelf.ref.name),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = Type.body.copy(color = Ink.Label),
                modifier = Modifier.weight(1f, fill = false),
            )
            Box(
                Modifier
                    .padding(start = 10.dp)
                    .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(percent = 50))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                BasicText(stringResource(R.string.shelf_channel_tag), style = Type.micro.copy(color = Ink.Label))
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(ShelfLayout.CHIP_GAP.dp), verticalAlignment = Alignment.CenterVertically) {
                chips.forEachIndexed { ci, chip ->
                    val spot = ShelfSpot(si, ShelfZone.CHIPS, ci)
                    ShelfChipPill(
                        chip = chip,
                        visible = focus,
                        onClick = { onChip(chip) },
                        onFocusChange = { report(spot, it) },
                        isFirst = ci == 0,
                        isLast = ci == chips.lastIndex,
                        modifier = Modifier.focusRequester(req(spot)).onGloballyPositioned { place(spot, it.boundsInRoot().center.x) },
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = ShelfLayout.PAD_START.dp, end = ShelfLayout.PAD_END.dp, top = ShelfLayout.CARDS_TOP.dp)) {
            ChannelShelfBody(shelf.state, loadPosters)
        }
    }
}

/**
 * R164 频道架子的正文:海报预览(按比例、高 [SHELF_POSTER_HEIGHT]、最多画到架子右缘,**不可聚焦**——一个 focusable 都没有,
 * 不进任何焦点账本),或一行状态文字(「暂无内容」/「需要重新授权」)。海报只在焦点层 ± 1 才加载,同首页。
 * `clipToBounds` 放在本文件而不是 EditScreen.kt:`EditIronRulesTest` 禁止 EditScreen.kt 出现它(防卡片条裁掉焦点卡);
 * 这里裁的是不可聚焦的预览,不在那条要防的范围。
 */
@Composable
internal fun ChannelShelfBody(state: ChannelShelfState, loadPosters: Boolean) {
    when (state) {
        // 外层按架子宽裁;里层 Row 放开测量(unbounded),右缘那张是被裁掉一截,而不是被约束挤窄(铁律 1 同一个测量坑)
        is ChannelShelfState.Posters -> Box(Modifier.fillMaxWidth().clipToBounds()) {
            Row(
                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                horizontalArrangement = Arrangement.spacedBy(ShelfLayout.CARD_GAP.dp),
            ) {
                val ctx = LocalContext.current
                // 解码高度用首页卡高(220 px):海报缓存与首页共用,同一张图只缓存一份
                val heightPx = with(LocalDensity.current) { ChannelRowLayout.CARD_HEIGHT.dp.roundToPx() }
                state.programs.forEach { p ->
                    val uri = p.posterUri
                    val bmp by produceState(uri?.let { PosterCache.peek(it) }, uri, loadPosters) {
                        value = uri?.let { PosterCache.peek(it) }   // 同 PosterCard:同一格换了节目先换掉旧图
                        if (uri != null && value == null && loadPosters) value = PosterCache.load(ctx, uri, heightPx)
                    }
                    Box(
                        Modifier
                            .size(shelfPosterWidthDp(p.aspect).dp, SHELF_POSTER_HEIGHT.dp)
                            .clip(RoundedCornerShape(GtvLayout.CARD_CORNER.dp))
                            .background(GtvTokens.PosterFallback),
                        contentAlignment = Alignment.Center,
                    ) {
                        val b = bmp
                        if (b != null) {
                            Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            BasicText(
                                p.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = Type.micro.copy(color = Ink.Primary, textAlign = TextAlign.Center),
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                    }
                }
            }
        }
        ChannelShelfState.Empty -> BasicText(stringResource(R.string.shelf_channel_empty), style = Type.body)
        ChannelShelfState.NeedsPermission -> BasicText(stringResource(R.string.shelf_channel_needs_permission), style = Type.body)
    }
}
