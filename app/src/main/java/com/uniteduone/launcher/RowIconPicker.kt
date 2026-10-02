package com.uniteduone.launcher

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 选择器一行几格:12 个图标排成 4 × 3,一屏放得下,不需要任何滚动(铁律 1)。 */
private const val ICON_COLUMNS = 4

/** 一格的宽高与格距(dp,R135):4 × 96 + 3 × 12 = 420,放在右半屏(480)正中。 */
private const val ICON_CELL_WIDTH = 96f
private const val ICON_CELL_HEIGHT = 84f
private const val ICON_CELL_GAP = 12f
private const val ICON_CELL_CORNER = 18f

/**
 * 行图标选择器(M4b spec §0-6):4 × 3 图标格,打开时 [current] 那一格预先聚焦。
 * 确定 = [onPick](图标 id,见 [ROW_ICON_IDS]);返回 = [onDismiss]。
 *
 * **R135(2026-09-30 外观轮)换皮**:与设置各层同一个版式——整屏 `MenuBg`,左边页名、上方一行小字写是哪一行(R163 起行没有名字,
 * 改成画这一行现在的图标,即 [current]),右边图标格;每一格与胶囊同一个焦点画法(聚焦填主题色、图标与字取对比色,150 ms 过渡)。
 * 此前是屏幕中间的小面板、标题 16 sp 带字距、聚焦画外扩描边、底部 10 sp 深灰的「按返回键取消」。焦点账本逐字未动。
 *
 * 焦点账本(与 [GearMenu] 同一写法;这个浮层开着时编辑页的看门狗让路,**丢了焦点只能靠它自己**,铁律 3):
 * - 逐格一个 [FocusRequester];
 * - [focusedIdx] =「回来时落哪」的目标(打开时 = [current] 的下标,之后跟着自报的 got 走),
 *   [holder] =「现在谁持有」(只信控件自报,失去就清,铁律 4)——两者分开(铁律 5);
 * - `LaunchedEffect(nonce)` 初始循环:打开 / nonce 变时把焦点送到 [focusedIdx],直到有人自报持有,最多 60 帧(铁律 2);
 * - 看门狗以 `holder == null` 同时当 key 与守卫(铁律 6),落地后再丢自然重新武装,不是闩(铁律 7);
 * - 四边 `FocusRequester.Cancel`:按到头原地不动,焦点不会冒泡到底下的编辑页;
 * - **淡出中的残影**([LocalPageGhost]):两个循环让路、每格 `canFocus = false`、不收返回键、点击不回调。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun RowIconPicker(
    current: String,
    nonce: Int,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ids = ROW_ICON_IDS
    val rowFocus = remember(ids.size) { List(ids.size) { FocusRequester() } }
    // 两个循环都在协程里跑,读的必须是当前这一份 requester(同 GearMenu 的 requesters)
    val requesters by rememberUpdatedState(rowFocus)
    var focusedIdx by remember { mutableStateOf(ids.indexOf(current).coerceAtLeast(0)) }
    var holder by remember { mutableStateOf<Int?>(null) }
    val ghost = LocalPageGhost.current

    LaunchedEffect(nonce, ghost) {
        if (ghost) return@LaunchedEffect
        val i = focusedIdx.coerceIn(0, requesters.lastIndex)
        var frames = 0
        while (holder == null && frames < 60) {
            withFrameNanos { }
            runCatching { requesters[i].requestFocus() }
            frames++
        }
    }
    LaunchedEffect(holder == null, ghost) {
        if (ghost || holder != null) return@LaunchedEffect
        repeat(3) { withFrameNanos { } }   // 换格时 lost / got 可能分属相邻两帧,中间那一帧的 null 不算丢
        var frames = 0
        while (holder == null && frames < 60) {
            runCatching { requesters[focusedIdx.coerceIn(0, requesters.lastIndex)].requestFocus() }
            withFrameNanos { }
            frames++
        }
    }

    androidx.activity.compose.BackHandler(enabled = !ghost) { onDismiss() }

    val rowsOfIds = ids.chunked(ICON_COLUMNS)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()   // 同 GearMenu:不圈起来焦点会跑到底下那一层
            .pageBackdrop(),   // R142
    ) {
        ShellScaffold(
            // 页头画这一行现在的图标(current 在页面开着期间不变:选中即关页,聚焦在格子间移动不改它)
            left = { ShellTitle(path = null, icon = current, title = stringResource(R.string.edit_row_icon_heading)) },
            right = {
                Column(verticalArrangement = Arrangement.spacedBy(ICON_CELL_GAP.dp)) {
                    rowsOfIds.forEachIndexed { r, rowIds ->
                        Row(horizontalArrangement = Arrangement.spacedBy(ICON_CELL_GAP.dp)) {
                            rowIds.forEachIndexed { c, id ->
                                val idx = r * ICON_COLUMNS + c
                                IconCell(
                                    id = id,
                                    modifier = Modifier
                                        .focusRequester(rowFocus[idx])
                                        .focusProperties { if (ghost) canFocus = false },
                                    onFocusChange = { got ->
                                        // 得失顺序保护(同 GearMenu):只有「本格仍是持有者」时 lost 才作废
                                        if (got) { holder = idx; focusedIdx = idx } else if (holder == idx) holder = null
                                    },
                                    edgeLeft = c == 0,
                                    edgeRight = c == rowIds.lastIndex,
                                    edgeTop = r == 0,
                                    edgeBottom = r == rowsOfIds.lastIndex,
                                    onClick = { if (!ghost) onPick(id) },
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun IconCell(
    id: String,
    modifier: Modifier,
    /** 得到 / 失去都报(铁律 4):选择器的看门狗靠「失去」知道格子里已经没有焦点。 */
    onFocusChange: (Boolean) -> Unit,
    edgeLeft: Boolean,
    edgeRight: Boolean,
    edgeTop: Boolean,
    edgeBottom: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalThemeColors.current.accent
    // 与胶囊同一个焦点画法(R135):聚焦填主题色、内容取对比色;填色 150 ms 过渡,内容色两态瞬切(见 MenuPill)。
    val fill by animateColorAsState(
        targetValue = if (focused) accent else GtvTokens.SurfaceIdle,   // R144
        animationSpec = tween(
            durationMillis = if (focused) GtvLayout.FOCUS_FADE_IN_MS else GtvLayout.FOCUS_FADE_OUT_MS,
            easing = Theme.AppFocusEasing,
        ),
        label = "rowIconCellFill",
    )
    val ink = if (focused) contrastingTextColor(accent) else Ink.Label
    Column(
        modifier = modifier
            .size(ICON_CELL_WIDTH.dp, ICON_CELL_HEIGHT.dp)
            .focusProperties {
                if (edgeLeft) left = FocusRequester.Cancel
                if (edgeRight) right = FocusRequester.Cancel
                if (edgeTop) up = FocusRequester.Cancel
                if (edgeBottom) down = FocusRequester.Cancel
            }
            .clip(RoundedCornerShape(ICON_CELL_CORNER.dp))
            .background(fill)
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            // 聚焦由填色表达;clickable 默认的聚焦蒙层会把主题色压暗一层,关掉(同 MenuPill)。
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Image(
            imageVector = rowIconVector(id),
            contentDescription = null,   // 名字就在下面一行
            colorFilter = ColorFilter.tint(if (focused) ink else accent),
            modifier = Modifier.size(28.dp),
        )
        BasicText(
            text = stringResource(rowIconLabel(id)),
            maxLines = 1,
            style = Type.caption.copy(
                fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
                color = ink,
                textAlign = TextAlign.Center,
            ),
        )
    }
}
