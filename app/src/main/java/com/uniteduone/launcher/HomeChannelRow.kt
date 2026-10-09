package com.uniteduone.launcher

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row as ComposeRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults

/**
 * R164 首页频道行(spec `2026-10-08-channel-rows-design.md` §2.3、§4;R164-4)。与 [CategoryRow] 同一套骨架:
 * 不可滚动的 Row + `wrapContentWidth(unbounded = true)` + 自算 `offset`(铁律 1),requester 挂在目标格(铁律 3),
 * 得失焦点都上报(铁律 4);行的图层四边撑大柔光那么多(R129f,见 CategoryRow 的注释)。不同处:
 * 行头「应用名 · 频道名」在卡片上方、左边距不画行图标;卡宽按海报比例([PosterAspect.widthDp]),横向位移用变宽版
 * [GtvLayout.rowShiftX];焦点行每张卡下两行字,非焦点行同样占位(版面不跳);长按不做事(MainActivity 吞掉)。
 */
@Composable
internal fun ChannelRow(
    row: Row,
    firstCard: FocusRequester?,
    rowRequester: FocusRequester?,
    isLastRow: Boolean,
    upTarget: FocusRequester?,
    downTarget: FocusRequester?,
    targetIndex: Int,
    landingShiftsPage: Boolean,
    isFocusRow: Boolean,
    loadPosters: Boolean,
    rowAlpha: () -> Float,
    enterAlpha: () -> Float,
    onFocusChange: (Int, Boolean) -> Unit,
) {
    val ref = row.channel ?: return
    val ctx = LocalContext.current
    val showToast = LocalToast.current
    val appLabel = row.channelAppLabel.ifBlank { ref.pkg }
    val title = stringResource(R.string.channel_row_title, appLabel, ref.name)
    val formats = MetaFormats(
        seasonEpisode = stringResource(R.string.channel_meta_season_episode),
        episode = stringResource(R.string.channel_meta_episode),
        hoursMinutes = stringResource(R.string.channel_meta_hours_minutes),
        minutes = stringResource(R.string.channel_meta_minutes),
    )
    val widths = remember(row.programs) { row.programs.map { it.aspect.widthDp } }
    var focusedIndex by remember { mutableStateOf(0) }
    var landedWithShift by remember { mutableStateOf(false) }
    val focused = focusedIndex.coerceIn(0, (row.cellCount - 1).coerceAtLeast(0))
    val focusedNow by rememberUpdatedState(focused)
    val landingShiftsPageNow by rememberUpdatedState(landingShiftsPage)
    val widthsNow by rememberUpdatedState(widths)
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val xShift by animateDpAsState(
        targetValue = GtvLayout.rowShiftX(focused, widths, screenWidthDp).dp,
        animationSpec = Theme.browseShiftSpec(),
        label = "channelXShift",
    )
    val headerAlpha by animateFloatAsState(
        targetValue = if (isFocusRow) 1f else ChannelRowLayout.HEADER_IDLE_ALPHA,
        animationSpec = Theme.homeRowIconSpec(),
        label = "channelHeader",
    )
    val glowPad = GtvLayout.APP_FOCUS_GLOW_DP.dp
    Box(
        Modifier
            .layout { measurable, constraints ->
                val e = glowPad.roundToPx()
                val p = measurable.measure(constraints.offset(horizontal = 2 * e, vertical = 2 * e))
                layout((p.width - 2 * e).coerceAtLeast(0), (p.height - 2 * e).coerceAtLeast(0)) { p.place(-e, -e) }
            }
            .graphicsLayer { alpha = rowAlpha() * enterAlpha() }
            .padding(horizontal = glowPad, vertical = glowPad),
    ) {
        Column(Modifier.padding(top = GtvLayout.ROW_CARD_TOP.dp, bottom = GtvLayout.ROW_CARD_TOP.dp)) {
            BasicText(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = Type.section.copy(color = Ink.Primary),
                modifier = Modifier
                    .padding(start = GtvLayout.CONTENT_KEYLINE.dp, end = GtvLayout.CONTENT_KEYLINE.dp)
                    .height(ChannelRowLayout.HEADER_LINE.dp)
                    .graphicsLayer { alpha = headerAlpha },
            )
            Spacer(Modifier.height(ChannelRowLayout.headerGap().dp))
            ComposeRow(
                horizontalArrangement = Arrangement.spacedBy(GtvLayout.CARD_GAP.dp),
                modifier = Modifier
                    // 铁律 1:必须 unbounded,否则屏幕宽用完之后的卡被量成 0 宽、永远聚焦不到
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .offset(x = xShift)
                    .padding(start = GtvLayout.CONTENT_KEYLINE.dp),
            ) {
                row.programs.forEachIndexed { index, p ->
                    PosterCard(
                        program = p,
                        widthDp = widths[index],
                        showInfo = isFocusRow,
                        subtitle = remember(p, formats) { programSubtitle(p, formats) },
                        loadPoster = loadPosters,
                        focusAfterShift = landedWithShift && index == focused,
                        onClick = {
                            if (!ChannelLaunch.open(ctx, ref.pkg, p.intentUri)) {
                                showToast(ctx.getString(R.string.toast_cant_open_app, appLabel), false)
                            }
                        },
                        modifier = Modifier
                            .let { m ->
                                val t = targetIndex.coerceIn(0, (row.cellCount - 1).coerceAtLeast(0))
                                if (rowRequester != null && index == t) m.focusRequester(rowRequester) else m
                            }
                            .let { m -> if (index == 0 && firstCard != null) m.focusRequester(firstCard) else m },
                        onFocusChange = { got ->
                            if (got) {
                                val xBefore = GtvLayout.rowShiftX(focusedNow, widthsNow, screenWidthDp)
                                val xAfter = GtvLayout.rowShiftX(index, widthsNow, screenWidthDp)
                                landedWithShift = landingShiftsPageNow || xAfter != xBefore
                                focusedIndex = index
                            }
                            onFocusChange(index, got)
                        },
                        isRowStart = index == 0,
                        isRowEnd = index == row.programs.lastIndex,
                        isLastRow = isLastRow,
                        upTarget = upTarget,
                        downTarget = downTarget,
                    )
                }
            }
        }
    }
}

/**
 * 一张节目海报卡(R164-4):高 [ChannelRowLayout.CARD_HEIGHT],宽 [widthDp];聚焦画法与应用卡同一套([gtvFocusFrameOverFade]);
 * 没图 / 失败 / 不在加载范围 → [GtvTokens.PosterFallback] 底 + 标题。卡下:[showInfo] 时标题 + [subtitle],否则等高占位。
 * 无障碍名 = 节目标题(e2e 靠它认焦点卡)。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PosterCard(
    program: Program,
    widthDp: Float,
    showInfo: Boolean,
    subtitle: String?,
    loadPoster: Boolean,
    focusAfterShift: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    onFocusChange: (Boolean) -> Unit,
    isRowStart: Boolean,
    isRowEnd: Boolean,
    isLastRow: Boolean,
    upTarget: FocusRequester?,
    downTarget: FocusRequester?,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalThemeColors.current.accent
    val fade = LocalCardFade.current
    val ctx = LocalContext.current
    val heightPx = with(LocalDensity.current) { ChannelRowLayout.CARD_HEIGHT.dp.roundToPx() }
    val uri = program.posterUri
    val poster by produceState(uri?.let { PosterCache.peek(it) }, uri, loadPoster) {
        // produceState 的状态不随 key 重建:同一格换了节目(重排 / 删减,卡片按位置组合)时先换成新 uri 的缓存(没有 = null),
        // 否则旧节目的海报会留在新节目的卡上
        value = uri?.let { PosterCache.peek(it) }
        if (uri != null && value == null && loadPoster) value = PosterCache.load(ctx, uri, heightPx)
    }
    val corner = GtvLayout.CARD_CORNER.dp
    val shape = RoundedCornerShape(corner)
    Column(Modifier.zIndex(if (focused) 1f else 0f).width(widthDp.dp)) {
        Card(
            onClick = onClick,
            onLongClick = null,
            modifier = modifier
                .gtvFocusFrameOverFade(focused, accent, corner, afterShift = focusAfterShift, fade = fade, restAlpha = fade.restAlpha)
                .size(widthDp.dp, ChannelRowLayout.CARD_HEIGHT.dp)
                .focusProperties {
                    if (isRowStart) left = FocusRequester.Cancel
                    if (isRowEnd) right = FocusRequester.Cancel
                    if (isLastRow) down = FocusRequester.Cancel else downTarget?.let { down = it }
                    upTarget?.let { up = it }
                }
                .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
                .semantics { contentDescription = program.title },
            shape = CardDefaults.shape(shape),
            colors = CardDefaults.colors(
                containerColor = GtvTokens.PosterFallback,
                contentColor = Ink.Primary,
                focusedContainerColor = GtvTokens.PosterFallback,
                pressedContainerColor = GtvTokens.PosterFallback,
            ),
            scale = CardDefaults.scale(focusedScale = 1f),
            border = CardDefaults.border(focusedBorder = Border.None, border = Border.None),
        ) {
            Box(Modifier.fillMaxSize().cardHairline(corner), contentAlignment = Alignment.Center) {
                val b = poster
                if (b != null) {
                    Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    BasicText(
                        text = program.title,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = Type.caption.copy(color = Ink.Primary, textAlign = TextAlign.Center),
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(ChannelRowLayout.infoGap().dp))
        if (showInfo) {
            BasicText(
                text = program.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = Type.body.copy(color = Ink.Primary),
                modifier = Modifier.height(ChannelRowLayout.INFO_TITLE_LINE.dp),
            )
            BasicText(
                text = subtitle.orEmpty(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = Type.caption.copy(color = Ink.Primary.copy(alpha = 0.7f)),
                modifier = Modifier.height(ChannelRowLayout.INFO_META_LINE.dp),
            )
        } else {
            Spacer(Modifier.height((ChannelRowLayout.INFO_TITLE_LINE + ChannelRowLayout.INFO_META_LINE).dp))
        }
    }
}
