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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay

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
    var landed by remember { mutableStateOf(false) }
    /**
     * **现在**持有焦点的那一格(只信控件自报,铁律 4);null = 网格里没有。与 [focusedIdx] 分开(铁律 5):
     * 后者是「回来时落哪」的目标,失焦时不清;这一个失焦就清,长按判据只认它。
     */
    var holderIdx by remember { mutableStateOf<Int?>(null) }
    val scroll = rememberScrollState()

    LaunchedEffect(nonce) {
        landed = false
        val i = focusedIdx.coerceIn(0, focusRequesters.lastIndex)
        var frames = 0
        while (!landed && frames < 60) {
            withFrameNanos { }
            runCatching { focusRequesters[i].requestFocus() }
            frames++
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

        Column(
            modifier = Modifier
                .heightIn(max = 600.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        rows.forEachIndexed { rowIdx, rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEachIndexed { colIdx, item ->
                    val idx = rowIdx * columns + colIdx
                    val focused = focusedIdx == idx
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
                                if (it.isFocused) { focusedIdx = idx; landed = true }
                                // 得失顺序保护(同 HomeScreen.report):只有「本格仍是持有者」时 lost 才作废,
                                // 新格先报 got、旧格后报 lost 时不会把新格抹掉。
                                if (it.isFocused) holderIdx = idx else if (holderIdx == idx) holderIdx = null
                            }
                            .clickable {
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
 * 关掉后(删除 / 取消都 focusNonce++)由网格的 nonce 循环把焦点接回原位置——删掉的那格由下一张补上,
 * 删的是末张就夹到上一张(`focusedIdx` 夹到新长度);删空换成空态,空态自己的循环接住焦点。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ScreensaverPoolViewer(
    directory: File,
    nonce: Int = 0,
    refresh: Int = 0,
    onFocusedFile: (File?) -> Unit = {},
    deleteTarget: File? = null,
    onConfirmDelete: (File) -> Unit = {},
    onCancelDelete: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val files = remember(directory, refresh) {
        directory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTS }
            ?.sortedBy { it.name }
            ?: emptyList()
    }
    var previewIndex by remember { mutableStateOf(-1) }
    // 全屏预览关掉时,它拿着的焦点随节点一起销毁,外层没有人会补请求(铁律 3:浮层自己负责恢复)。
    // M5 让图库重新可达(设置页入口),这条路因此变成常规路径。本地计数并进网格的 nonce:两个量都只增不减,
    // 任何一个变了和就变,网格的初始焦点循环据此再跑一轮,落回 focusedIdx。
    var previewCloses by remember { mutableStateOf(0) }

    androidx.activity.compose.BackHandler { onDismiss() }
    Box(
        modifier = Modifier.fillMaxSize().focusGroup()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        if (files.isEmpty()) {
            PoolEmptyState(nonce, onDismiss)
        } else {
            PickerGrid(
                items = files.map { PickerItem.Library(it) },
                title = stringResource(R.string.picker_screensaver_pool_title, files.size),
                columns = 3,
                thumbWidth = 170.dp,
                thumbHeight = 96.dp,
                nonce = nonce + previewCloses,
                onSelectFile = { file ->
                    val idx = files.indexOf(file)
                    if (idx >= 0) previewIndex = idx
                },
                onRestoreOriginal = null,
                onDismiss = onDismiss,
                onFocusedFile = onFocusedFile,
            )
        }
    }

    if (previewIndex in files.indices) {
        ScreensaverPreview(
            files = files,
            startIndex = previewIndex,
            onDismiss = { previewIndex = -1; previewCloses++ },
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

@Composable
private fun ScreensaverPreview(
    files: List<File>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    var index by remember { mutableStateOf(startIndex) }
    val fr = remember { FocusRequester() }
    var landed by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler { onDismiss() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(fr)
            .onFocusChanged { if (it.isFocused) landed = true }
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
            PreviewSlot(files.getOrNull(idx))
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

    LaunchedEffect(Unit) {
        landed = false
        var frames = 0
        while (!landed && frames < 60) {
            withFrameNanos { }
            runCatching { fr.requestFocus() }
            frames++
        }
    }
}

@Composable
private fun PreviewSlot(file: File?) {
    file ?: return
    val bmp by produceState<android.graphics.Bitmap?>(null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                Apps.decodeScaled(file.absolutePath, 1920, 1080, android.graphics.Bitmap.Config.RGBA_F16)
            }.getOrNull()
        }
    }
    val b = bmp ?: return
    val scale = remember { Animatable(1.0f) }
    LaunchedEffect(file.absolutePath) {
        scale.snapTo(1.0f)
        scale.animateTo(
            targetValue = Theme.ScreensaverZoom,
            animationSpec = tween(
                durationMillis = (Theme.ScreensaverIntervalMs + Theme.ScreensaverCrossfadeMs).toInt(),
                easing = LinearEasing,
            ),
        )
    }
    Image(
        bitmap = b.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().scale(scale.value),
    )
}
