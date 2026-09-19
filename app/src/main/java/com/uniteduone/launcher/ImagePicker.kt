package com.uniteduone.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity

private sealed class PickerItem {
    data class Original(val bitmap: Bitmap) : PickerItem()
    data class Library(val file: File) : PickerItem()
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun WallpaperPicker(
    directory: File,
    title: String = stringResource(R.string.picker_wallpaper_title),
    nonce: Int = 0,
    onSelect: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    val files = remember(directory) {
        directory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTS }
            ?.sortedBy { it.name }
            ?: emptyList()
    }
    androidx.activity.compose.BackHandler { onDismiss() }
    Box(
        modifier = Modifier.fillMaxSize().focusGroup()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        if (files.isEmpty()) {
            EmptyState(title, nonce, onDismiss)
        } else {
            PickerGrid(
                items = files.map { PickerItem.Library(it) },
                title = title,
                columns = 3,
                thumbWidth = 170.dp,
                thumbHeight = 96.dp,
                nonce = nonce,
                onSelectFile = onSelect,
                onRestoreOriginal = null,
                onDismiss = onDismiss,
            )
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun IconPicker(
    directory: File,
    originalIcon: Bitmap?,
    nonce: Int = 0,
    onSelect: (File) -> Unit,
    onRestoreOriginal: () -> Unit,
    onDismiss: () -> Unit,
) {
    val files = remember(directory) {
        directory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTS }
            ?.sortedBy { it.name }
            ?: emptyList()
    }
    val items = remember(originalIcon, files) {
        buildList {
            if (originalIcon != null) add(PickerItem.Original(originalIcon))
            addAll(files.map { PickerItem.Library(it) })
        }.take(MAX_ICON_ITEMS)
    }
    androidx.activity.compose.BackHandler { onDismiss() }
    Box(
        modifier = Modifier.fillMaxSize().focusGroup()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        if (items.isEmpty()) {
            EmptyState(stringResource(R.string.picker_card_image_title), nonce, onDismiss)
        } else {
            PickerGrid(
                items = items,
                title = stringResource(R.string.picker_card_image_title),
                columns = 4,
                thumbWidth = 130.dp,
                thumbHeight = 73.dp,
                nonce = nonce,
                onSelectFile = onSelect,
                onRestoreOriginal = onRestoreOriginal,
                onDismiss = onDismiss,
            )
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun EmptyState(title: String, nonce: Int, onDismiss: () -> Unit) {
    val fr = remember { FocusRequester() }
    var landed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Theme.DialogSurface)
            .padding(24.dp)
            .width(400.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text = title,
            style = TextStyle(fontFamily = Theme.Sans, color = LocalThemeColors.current.highlight, fontSize = 16.sp),
        )
        BasicText(
            text = stringResource(R.string.picker_no_images),
            style = TextStyle(fontFamily = Theme.Sans, color = Theme.DialogBodyText, fontSize = 14.sp, textAlign = TextAlign.Center),
        )
        BasicText(
            text = stringResource(R.string.picker_adb_hint),
            style = TextStyle(fontFamily = Theme.Sans, color = Theme.HintText, fontSize = 12.sp, textAlign = TextAlign.Center),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            text = stringResource(R.string.picker_back_to_close),
            style = TextStyle(fontFamily = Theme.Sans, color = Theme.PickerFooterText, fontSize = 11.sp),
            modifier = Modifier
                .focusRequester(fr)
                .onFocusChanged { if (it.isFocused) landed = true }
                .focusProperties {
                    up = FocusRequester.Cancel; down = FocusRequester.Cancel
                    left = FocusRequester.Cancel; right = FocusRequester.Cancel
                }
                .clickable { onDismiss() },
        )
    }

    LaunchedEffect(nonce) {
        landed = false
        var frames = 0
        while (!landed && frames < 60) {
            withFrameNanos { }
            runCatching { fr.requestFocus() }
            frames++
        }
    }
}

/**
 * 图片网格视窗的首行(铁律 1:不用 verticalScroll,位移自己算)。焦点行还在视窗里就不动;
 * 往上出界 → 焦点行成为首行;往下出界 → 焦点行成为视窗里最后一个完整可见的行。
 * [visibleRows] ≤ 0 按 1 算(防御:量出来之前不会用到)。
 */
internal fun keepInView(focusedRow: Int, firstVisible: Int, visibleRows: Int): Int {
    val v = visibleRows.coerceAtLeast(1)
    return when {
        focusedRow < firstVisible -> focusedRow
        focusedRow >= firstVisible + v -> focusedRow - v + 1
        else -> firstVisible
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PickerGrid(
    items: List<PickerItem>,
    title: String,
    columns: Int,
    thumbWidth: Dp,
    thumbHeight: Dp,
    nonce: Int = 0,
    onSelectFile: (File) -> Unit,
    onRestoreOriginal: (() -> Unit)?,
    onDismiss: () -> Unit,
    /**
     * 网格上盖着别的浮层(全屏预览 / 删图确认框):它们自己负责焦点(铁律 3),网格的初始焦点循环让路——
     * 否则回到前台时(MainActivity.onResume → focusNonce++)这里会把焦点从预览底下抢走(M5 遗留)。
     */
    covered: Boolean = false,
    /**
     * 当前聚焦的图库文件(M5 spec §5,长按删图用):缩略图得到焦点报文件、失去报 null;只有屏保图库传它。
     * 全屏预览 / 确认框盖上来时网格失焦 → 报 null → 长按不生效。
     */
    onFocusedFile: ((File?) -> Unit)? = null,
) {
    val rows = items.chunked(columns)
    val focusRequesters = remember(items.size) {
        List(items.size.coerceAtLeast(1)) { FocusRequester() }
    }
    var focusedIdx by remember { mutableStateOf(0) }
    // fix round 1:删图后 focusedIdx 可能落在新列表的界外(删的正是末张)——统一在这里夹一次,
    // 定位效果、看门狗、渲染时的 focused 判据都读这一个,不再各处各夹各的。
    val clampedFocusedIdx = focusedIdx.coerceIn(0, focusRequesters.lastIndex)
    /**
     * **现在**持有焦点的那一格(只信控件自报,铁律 4);null = 网格里没有。与 [focusedIdx] 分开(铁律 5):
     * 后者是「回来时落哪」的目标,失焦时不清;这一个失焦就清,长按判据、定位效果的退出条件、
     * 看门狗都只认它(fix round 1:换掉了原来只增不减的 landed——旧格被销毁时没人把它拨回
     * false,循环会在假的「已经落地」上直接跳过)。
     */
    var holderIdx by remember { mutableStateOf<Int?>(null) }

    // 遗留 #8(实测复现,2026-09-19 模拟器):长按识别在 MainActivity.dispatchKeyEvent(组合树外),
    // 它把长按之后那次 DPAD_CENTER 的 key-up 吞掉——clickable 默认内建的 MutableInteractionSource
    // 因此永远等不到配对的 Release/Cancel,indication(按压暗色)永久卡住,直到进程重来都不会自己解开。
    // 每格自带一份 interactionSource + 记下「当前是否有未配对的 Press」;确认框 / 预览关闭那一刻
    // (covered 从 true 变 false,复用下面的定位效果)照 press 补发一个 Cancel 解开。
    // **SnapshotStateList,不是普通 List**(fix round 1):补发 Cancel 只清得掉 indication 观察到的
    // 那条流,foundation 的 ClickableNode 自己另有一份「当前哪个键按着」的记录绑在旧 source 对象上,
    // 外部发不进去——不换一个新对象,同一格下一次确定键会被当成「这键还按着」,按压视觉从此哑掉。
    // 换成可写的 list,发完 Cancel 顺手把那一格的元素替换成新对象,下面的收集效果会跟着 source
    // 这个 key 自动重订阅,ClickableNode 也会因为 interactionSource 参数变了而丢掉旧记录。
    val interactionSources = remember(items.size) {
        List(items.size.coerceAtLeast(1)) { MutableInteractionSource() }.toMutableStateList()
    }
    val pendingPress = remember(items.size) {
        arrayOfNulls<PressInteraction.Press?>(items.size.coerceAtLeast(1))
    }
    interactionSources.forEachIndexed { i, source ->
        LaunchedEffect(source) {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pendingPress[i] = interaction
                    is PressInteraction.Release, is PressInteraction.Cancel -> pendingPress[i] = null
                }
            }
        }
    }

    // 铁律 1:原来的 heightIn(max = 600.dp).verticalScroll(...) 换成「裁剪视窗 + 整块自算位移」,
    // 与 HomeScreen 纵向那套同一招。视窗外的行照常组合(图库张数有限,可接受)。
    val density = LocalDensity.current
    // 一份 val 两处用(fix round 1):下面 verticalArrangement 的行距与这里的位移算术必须是
    // 同一个数,分写两处迟早改一处漏一处、量出来的间距和实际渲染的间距对不上。
    val rowGap = 8.dp
    val rowGapPx = with(density) { rowGap.roundToPx() }
    /** 一行缩略图的实测高度(首行量出来,各行等高);0 = 还没量到,此时不位移。 */
    var rowHeightPx by remember { mutableStateOf(0) }
    /** 视窗实测高度(≤ 600dp)。 */
    var viewportPx by remember { mutableStateOf(0) }
    /** 视窗顶上是第几行:只在某格报「得到焦点」时由 [keepInView] 推进。 */
    var firstVisibleRow by remember { mutableStateOf(0) }
    val pitchPx = rowHeightPx + rowGapPx
    val visibleRows =
        if (rowHeightPx > 0 && viewportPx > 0) ((viewportPx + rowGapPx) / pitchPx).coerceAtLeast(1) else rows.size
    // 删图后行数变少:首行夹回合法范围,末页不会留一截空白
    val firstRow = firstVisibleRow.coerceIn(0, (rows.size - visibleRows).coerceAtLeast(0))
    val yShift by animateDpAsState(
        targetValue = with(density) { (-(firstRow * pitchPx)).toDp() },
        animationSpec = tween(Theme.MotionInMs, easing = Theme.MotionEasing),
        label = "pickerYShift",
    )

    // **定位效果**:进入 / nonce 变 / 浮层刚让路时把焦点送到 clampedFocusedIdx。
    // fix round 1(reviewer 实测复现,HEAD 4deae96 删完聚焦到背后盖住的设置页):删图后的重扫是
    // 异步的(produceState 在 IO 线程跑,见 [ScreensaverPoolViewer]),covered 变 false 那一刻
    // items 常常还是删除前的旧列表——原来只以 (nonce, covered) 为 key,请求打在这批旧
    // focusRequesters 上;新列表一到,`remember(items.size)` 把 focusRequesters 整表换新,而这个
    // 效果的 key 都没变、不会重跑,从此没有人再请求焦点。**把 focusRequesters 也编进 key**:
    // 它一变(items.size 变,亦即任何一次删除)这里就跟着重跑,用的是换新之后那一批。
    LaunchedEffect(nonce, covered, focusRequesters) {
        if (covered) return@LaunchedEffect
        // 遗留 #8:浮层刚让路,可能留了一个卡住的 Press(不一定是当前聚焦格——长按发生时聚焦的是
        // 被长按的那格,covered 期间焦点没有别处可去,所以就是它自己)。没有卡住的格子 press 为 null,不发。
        pendingPress.forEachIndexed { i, press ->
            if (press != null) {
                interactionSources[i].tryEmit(PressInteraction.Cancel(press))
                pendingPress[i] = null
                // 见 interactionSources 声明处的注释:换新对象,ClickableNode 才会真正忘掉这一格
                // 的按键状态,下一次确定键才会重新出现按压视觉。
                interactionSources[i] = MutableInteractionSource()
            }
        }
        // 退出条件是目标格自己报了 holderIdx == i(铁律 2),不再信只增不减的 landed——
        // 旧格被销毁那一刻不会有人把它拨回未落地,循环会在假的「已经落地」上直接跳过。
        val i = clampedFocusedIdx
        var frames = 0
        while (holderIdx != i && frames < 60) {
            withFrameNanos { }
            runCatching { focusRequesters[i].requestFocus() }
            frames++
        }
    }

    // **焦点看门狗**:上面那条管「我想去哪」,这条管「焦点莫名其妙没了」——旧格随删除被销毁、
    // 定位效果又恰好在新列表到达前已经打满 60 帧(或全打在行将销毁的旧格上、次次抛异常)时,
    // 谁都不会再补请求。不靠「在猜得到的几个时刻补请求」(铁律 3),镜像 SettingsScreen 的写法:
    // 守卫 covered / holderIdx==null 都在 key 里(铁律 6)。
    LaunchedEffect(holderIdx == null, covered) {
        if (covered || holderIdx != null) return@LaunchedEffect
        // D-pad 换格 / 节点销毁时得、失可能分属相邻两帧:旧格先报丢、新格下一帧才报得,
        // 中间那一帧的 null 不算真丢(与 HomeScreen / SettingsScreen 看门狗同一手法)。
        repeat(3) { withFrameNanos {} }
        if (holderIdx != null) return@LaunchedEffect
        while (holderIdx == null && !covered) {
            withFrameNanos { }
            runCatching { focusRequesters[focusedIdx.coerceIn(0, focusRequesters.lastIndex)].requestFocus() }
        }
    }

    // 上报只派生、不缓存(同 HomeScreen 的 onFocusedCard):删图后同一格换了文件、没有焦点事件,
    // items 变 → 这里按新列表再报一次。离开组合(关图库 / 删空换成空态)报 null,不留过期文件。
    if (onFocusedFile != null) {
        LaunchedEffect(holderIdx, items) {
            onFocusedFile((holderIdx?.let { items.getOrNull(it) } as? PickerItem.Library)?.file)
        }
        DisposableEffect(Unit) { onDispose { onFocusedFile(null) } }
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Theme.DialogSurface)
            .padding(16.dp)
            .widthIn(max = 700.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            text = title,
            style = TextStyle(fontFamily = Theme.Sans, color = LocalThemeColors.current.highlight, fontSize = 16.sp),
            modifier = Modifier.padding(bottom = 4.dp),
        )

        Box(
            modifier = Modifier
                .heightIn(max = 600.dp)
                .clipToBounds()
                .onSizeChanged { viewportPx = it.height },
        ) {
        Column(
            modifier = Modifier
                // **必须 unbounded**(铁律 1 的另一半):不放开测量,超出 600dp 的行会被压扁 / 量成 0 高,
                // offset 发生在测量之后救不回来
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .offset(y = yShift),
            verticalArrangement = Arrangement.spacedBy(rowGap),
        ) {
        rows.forEachIndexed { rowIdx, rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = if (rowIdx == 0) Modifier.onSizeChanged { rowHeightPx = it.height } else Modifier,
            ) {
                rowItems.forEachIndexed { colIdx, item ->
                    val idx = rowIdx * columns + colIdx
                    val focused = clampedFocusedIdx == idx
                    ThumbCard(
                        item = item,
                        focused = focused,
                        thumbWidth = thumbWidth,
                        thumbHeight = thumbHeight,
                        modifier = Modifier
                            .focusRequester(focusRequesters[idx])
                            .focusProperties {
                                if (colIdx == 0) left = FocusRequester.Cancel
                                if (colIdx == rowItems.lastIndex) right = FocusRequester.Cancel
                                if (rowIdx == 0) up = FocusRequester.Cancel
                                if (rowIdx == rows.lastIndex) down = FocusRequester.Cancel
                            }
                            .onFocusChanged {
                                if (it.isFocused) {
                                    focusedIdx = idx
                                    firstVisibleRow = keepInView(rowIdx, firstVisibleRow, visibleRows)
                                }
                                // 得失顺序保护(同 HomeScreen.report):只有「本格仍是持有者」时 lost 才作废,
                                // 新格先报 got、旧格后报 lost 时不会把新格抹掉。
                                if (it.isFocused) holderIdx = idx else if (holderIdx == idx) holderIdx = null
                            }
                            .clickable(
                                interactionSource = interactionSources[idx],
                                indication = LocalIndication.current,
                            ) {
                                when (item) {
                                    is PickerItem.Original -> onRestoreOriginal?.invoke()
                                    is PickerItem.Library -> onSelectFile(item.file)
                                }
                            },
                    )
                }
            }
        }
        }
        }

        BasicText(
            text = stringResource(R.string.picker_back_to_cancel),
            style = TextStyle(fontFamily = Theme.Sans, color = Theme.PickerFooterText, fontSize = 11.sp),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ThumbCard(
    item: PickerItem,
    focused: Boolean,
    thumbWidth: Dp,
    thumbHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val thumb by produceState<Bitmap?>(null, item) {
        value = when (item) {
            is PickerItem.Original -> item.bitmap
            is PickerItem.Library -> withContext(Dispatchers.IO) {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(item.file.absolutePath, opts)
                    val w = opts.outWidth; val h = opts.outHeight
                    if (w <= 0 || h <= 0) return@runCatching null
                    val sample = maxOf(1, minOf(w / 240, h / 135))
                    val decOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeFile(item.file.absolutePath, decOpts)
                }.getOrNull()
            }
        }
    }

    val label = when (item) {
        is PickerItem.Original -> stringResource(R.string.picker_restore_original)
        is PickerItem.Library -> item.file.nameWithoutExtension
    }
    val highlight = LocalThemeColors.current.highlight

    Column(
        modifier = modifier
            .width(thumbWidth)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) highlight.copy(alpha = 0.16f) else Theme.UnfocusedSurface)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val bmp = thumb
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(thumbHeight)
                    .clip(RoundedCornerShape(4.dp)),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().height(thumbHeight)
                    .clip(RoundedCornerShape(4.dp)).background(Theme.ThumbPlaceholderBackground),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("...", style = TextStyle(color = Theme.ThumbLoadingText, fontSize = 12.sp))
            }
        }

        BasicText(
            text = label,
            style = TextStyle(
                fontFamily = Theme.Sans,
                color = if (focused) highlight else Theme.ThumbLabelText,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val MAX_ICON_ITEMS = 16

/**
 * 屏保图库:显示 library/screensavers/ 里的全部图片,确定键全屏预览;长按缩略图 → 删除确认框
 * (M5 spec §5;长按识别在 MainActivity.dispatchKeyEvent,这里只画与上报)。
 * [refresh] = 图库版本,删图后 +1,文件列表据此重读。
 * [deleteTarget] 非 null 时在自身之上画 [ConfirmDialog]:它自己负责焦点(nonce + focusedBtn,默认在取消);
 * 关掉后(删除 / 取消都 focusNonce++)由 [PickerGrid] 接回焦点——删掉的那格由下一张补上,删的是末张
 * 就夹到上一张(读的时候夹,见 PickerGrid 的 clampedFocusedIdx)。**重扫是异步的**(下面的
 * produceState 在 IO 线程跑):covered 变 false 那一刻 items 常常还是删除前的旧列表,新列表一到 focusRequesters
 * 因 items.size 变而整表换新——PickerGrid 的定位效果把 focusRequesters 也编进 key 应对这一步,
 * 另配一个只认「有没有人持有焦点」的看门狗兜底(fix round 1,镜像 SettingsScreen 那一份的写法;
 * 这一段实测复现过焦点漏给背后盖住的设置页,删最后一张 / 小图库删任意一张都会中招)。
 * 删空换成空态,空态自己的循环接住焦点。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ScreensaverPoolViewer(
    nonce: Int = 0,
    refresh: Int = 0,
    onFocusedFile: (File?) -> Unit = {},
    deleteTarget: File? = null,
    onConfirmDelete: (File) -> Unit = {},
    onCancelDelete: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    // IO 线程扫、与播放器同一条规则(scanScreensaverLibrary)——原来在主线程按自己的扩展名表列目录,两份口径。
    // produceState 的值跨 key 保留:删图后 refresh+1 重扫期间仍显示旧列表,不会闪一下空态。null = 首次还没扫完。
    val scanned by produceState<List<File>?>(null, refresh) {
        value = withContext(Dispatchers.IO) {
            runCatching { scanScreensaverLibrary(ctx) }.getOrDefault(emptyList())
        }
    }
    val files = scanned ?: emptyList()
    var previewIndex by remember { mutableStateOf(-1) }

    androidx.activity.compose.BackHandler { onDismiss() }
    Box(
        modifier = Modifier.fillMaxSize().focusGroup()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            scanned == null -> Unit   // 首次扫描中(几十毫秒):只有半透明底
            files.isEmpty() -> PoolEmptyState(nonce, onDismiss)
            else -> PickerGrid(
                items = files.map { PickerItem.Library(it) },
                title = stringResource(R.string.picker_screensaver_pool_title, files.size),
                columns = 3,
                thumbWidth = 170.dp,
                thumbHeight = 96.dp,
                nonce = nonce,
                onSelectFile = { file ->
                    val idx = files.indexOf(file)
                    if (idx >= 0) previewIndex = idx
                },
                onRestoreOriginal = null,
                onDismiss = onDismiss,
                covered = previewIndex >= 0 || deleteTarget != null,
                onFocusedFile = onFocusedFile,
            )
        }
    }

    if (previewIndex in files.indices) {
        ScreensaverPreview(
            files = files,
            startIndex = previewIndex,
            nonce = nonce,
            onDismiss = { previewIndex = -1 },
        )
    }

    // 删除确认框(spec §5):画在最上层;BackHandler 比查看器的更晚注册,返回键先关它。
    val target = deleteTarget
    if (target != null) {
        ConfirmDialog(
            title = stringResource(R.string.pool_delete_title),
            body = stringResource(R.string.pool_delete_body, target.name),
            okLabel = stringResource(R.string.pool_delete_ok),
            cancelLabel = stringResource(R.string.dialog_cancel),
            nonce = nonce,
            onOk = { onConfirmDelete(target) },
            onCancel = onCancelDelete,
        )
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PoolEmptyState(nonce: Int, onDismiss: () -> Unit) {
    val fr = remember { FocusRequester() }
    var landed by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Theme.DialogSurface)
            .padding(24.dp)
            .width(400.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text = stringResource(R.string.picker_screensaver_title),
            style = TextStyle(fontFamily = Theme.Sans, color = LocalThemeColors.current.highlight, fontSize = 16.sp),
        )
        BasicText(
            text = stringResource(R.string.picker_no_screensavers),
            style = TextStyle(fontFamily = Theme.Sans, color = Theme.DialogBodyText, fontSize = 14.sp, textAlign = TextAlign.Center),
        )
        BasicText(
            text = stringResource(R.string.picker_adb_hint_screensaver),
            style = TextStyle(fontFamily = Theme.Sans, color = Theme.HintText, fontSize = 12.sp, textAlign = TextAlign.Center),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            text = stringResource(R.string.picker_back_to_close),
            style = TextStyle(fontFamily = Theme.Sans, color = Theme.PickerFooterText, fontSize = 11.sp),
            modifier = Modifier
                .focusRequester(fr)
                .onFocusChanged { if (it.isFocused) landed = true }
                .focusProperties {
                    up = FocusRequester.Cancel; down = FocusRequester.Cancel
                    left = FocusRequester.Cancel; right = FocusRequester.Cancel
                }
                .clickable { onDismiss() },
        )
    }
    LaunchedEffect(nonce) {
        landed = false
        var frames = 0
        while (!landed && frames < 60) {
            withFrameNanos { }
            runCatching { fr.requestFocus() }
            frames++
        }
    }
}

/** @param nonce 外层焦点 nonce(MainActivity.focusNonce):回到前台等时刻 +1,预览据此重新落焦点。 */
@Composable
private fun ScreensaverPreview(
    files: List<File>,
    startIndex: Int,
    nonce: Int,
    onDismiss: () -> Unit,
) {
    var index by remember { mutableStateOf(startIndex) }
    val fr = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler { onDismiss() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(fr)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> { if (index > 0) index--; true }
                        Key.DirectionRight -> { if (index < files.lastIndex) index++; true }
                        else -> false
                    }
                } else false
            }
            .focusable()
    ) {
        Crossfade(
            targetState = index,
            animationSpec = tween(500),
            label = "previewCrossfade",
        ) { idx ->
            ScreensaverSlot(files.getOrNull(idx), Theme.ScreensaverIntervalMs)
        }

        var showInfo by remember { mutableStateOf(true) }
        LaunchedEffect(index) {
            showInfo = true
            delay(3000)
            showInfo = false
        }
        if (showInfo) {
            val file = files[index]
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.BottomStart) {
                BasicText(
                    text = "${file.nameWithoutExtension}  (${index + 1}/${files.size})",
                    style = TextStyle(
                        fontFamily = Theme.Sans,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                    ),
                )
            }
        }
    }

    // 以 nonce 为 key(原来是 Unit,只落一次地):回到前台时 nonce 变,预览自己把焦点要回来。
    // 判据是自报的 focused(得失都报),不是只写 true 的 landed——已经有焦点时这里一次都不请求。
    LaunchedEffect(nonce) {
        var frames = 0
        while (!focused && frames < 60) {
            withFrameNanos { }
            runCatching { fr.requestFocus() }
            frames++
        }
    }
}
