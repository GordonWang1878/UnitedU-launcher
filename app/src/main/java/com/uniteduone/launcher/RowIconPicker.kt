package com.uniteduone.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 选择器一行几格:12 个图标排成 4 × 3,一屏放得下,不需要任何滚动(铁律 1)。 */
private const val ICON_COLUMNS = 4

/**
 * 行图标选择器(M4b spec §0-6):整屏半透明底 + 4 × 3 图标格,打开时 [current] 那一格预先聚焦。
 * 确定 = [onPick](图标 id,见 [ROW_ICON_IDS]);返回 = [onDismiss]。
 *
 * 焦点账本(与 [GearMenu] 同一写法;这个浮层开着时编辑页的看门狗让路,**丢了焦点只能靠它自己**,铁律 3):
 * - 逐格一个 [FocusRequester];
 * - [focusedIdx] =「回来时落哪」的目标(打开时 = [current] 的下标,之后跟着自报的 got 走),
 *   [holder] =「现在谁持有」(只信控件自报,失去就清,铁律 4)——两者分开(铁律 5);
 * - `LaunchedEffect(nonce)` 初始循环:打开 / nonce 变时把焦点送到 [focusedIdx],直到有人自报持有,最多 60 帧(铁律 2);
 * - 看门狗以 `holder == null` 同时当 key 与守卫(铁律 6),落地后再丢自然重新武装,不是闩(铁律 7);
 * - 四边 `FocusRequester.Cancel`:按到头原地不动,焦点不会冒泡到蒙版后面的编辑页。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun RowIconPicker(current: String, nonce: Int, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val ids = ROW_ICON_IDS
    val rowFocus = remember(ids.size) { List(ids.size) { FocusRequester() } }
    // 两个循环都在协程里跑,读的必须是当前这一份 requester(同 GearMenu 的 requesters)
    val requesters by rememberUpdatedState(rowFocus)
    var focusedIdx by remember { mutableStateOf(ids.indexOf(current).coerceAtLeast(0)) }
    var holder by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(nonce) {
        val i = focusedIdx.coerceIn(0, requesters.lastIndex)
        var frames = 0
        while (holder == null && frames < 60) {
            withFrameNanos { }
            runCatching { requesters[i].requestFocus() }
            frames++
        }
    }
    LaunchedEffect(holder == null) {
        if (holder != null) return@LaunchedEffect
        repeat(3) { withFrameNanos { } }   // 换格时 lost / got 可能分属相邻两帧,中间那一帧的 null 不算丢
        var frames = 0
        while (holder == null && frames < 60) {
            runCatching { requesters[focusedIdx.coerceIn(0, requesters.lastIndex)].requestFocus() }
            withFrameNanos { }
            frames++
        }
    }

    androidx.activity.compose.BackHandler { onDismiss() }

    val rowsOfIds = ids.chunked(ICON_COLUMNS)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()   // 同 GearMenu:不圈起来焦点会跑到蒙版后面
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Theme.DialogSurface)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            BasicText(
                text = stringResource(R.string.edit_row_icon_heading),
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    fontWeight = FontWeight.Medium,
                    color = LocalThemeColors.current.highlight,
                    fontSize = 16.sp,
                    letterSpacing = 1.sp,
                ),
            )
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rowsOfIds.forEachIndexed { r, rowIds ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowIds.forEachIndexed { c, id ->
                            val idx = r * ICON_COLUMNS + c
                            IconCell(
                                id = id,
                                modifier = Modifier.focusRequester(rowFocus[idx]),
                                onFocusChange = { got ->
                                    // 得失顺序保护(同 GearMenu):只有「本格仍是持有者」时 lost 才作废
                                    if (got) { holder = idx; focusedIdx = idx } else if (holder == idx) holder = null
                                },
                                edgeLeft = c == 0,
                                edgeRight = c == rowIds.lastIndex,
                                edgeTop = r == 0,
                                edgeBottom = r == rowsOfIds.lastIndex,
                                onClick = { onPick(id) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            BasicText(
                text = stringResource(R.string.picker_back_to_cancel),
                style = TextStyle(fontFamily = Theme.Sans, color = Theme.FooterHintText, fontSize = 10.sp),
            )
        }
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
    val highlight = LocalThemeColors.current.highlight
    Column(
        modifier = modifier
            .width(96.dp)
            .focusProperties {
                if (edgeLeft) left = FocusRequester.Cancel
                if (edgeRight) right = FocusRequester.Cancel
                if (edgeTop) up = FocusRequester.Cancel
                if (edgeBottom) down = FocusRequester.Cancel
            }
            .clip(RoundedCornerShape(8.dp))
            // 聚焦底色 = 菜单项(GearMenu.MenuRow)同一个 highlight 12%
            .background(if (focused) highlight.copy(alpha = 0.12f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(
            imageVector = rowIconVector(id),
            contentDescription = null,   // 名字就在下面一行
            colorFilter = ColorFilter.tint(LocalThemeColors.current.accent),
            modifier = Modifier.size(32.dp),
        )
        BasicText(
            text = stringResource(rowIconLabel(id)),
            style = TextStyle(
                fontFamily = Theme.Sans,
                fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
                color = if (focused) Theme.EmphasisText else Theme.MenuItemText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/** 图标 id → 选择器里显示的名字(`row_icon_<id>`)。 */
private fun rowIconLabel(id: String): Int = when (id) {
    "movie" -> R.string.row_icon_movie
    "tv" -> R.string.row_icon_tv
    "live" -> R.string.row_icon_live
    "music" -> R.string.row_icon_music
    "games" -> R.string.row_icon_games
    "kids" -> R.string.row_icon_kids
    "tools" -> R.string.row_icon_tools
    "education" -> R.string.row_icon_education
    "sports" -> R.string.row_icon_sports
    "news" -> R.string.row_icon_news
    "photos" -> R.string.row_icon_photos
    else -> R.string.row_icon_apps
}
