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

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")

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
            style = TextStyle(fontFamily = Theme.Sans, color = Theme.Champagne, fontSize = 16.sp),
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
) {
    val rows = items.chunked(columns)
    val focusRequesters = remember(items.size) {
        List(items.size.coerceAtLeast(1)) { FocusRequester() }
    }
    var focusedIdx by remember { mutableStateOf(0) }
    var landed by remember { mutableStateOf(false) }
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
            style = TextStyle(fontFamily = Theme.Sans, color = Theme.Champagne, fontSize = 16.sp),
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

    Column(
        modifier = modifier
            .width(thumbWidth)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Theme.Champagne.copy(alpha = 0.16f) else Theme.UnfocusedSurface)
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
                color = if (focused) Theme.Champagne else Theme.ThumbLabelText,
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
 * 屏保图库预览:显示 library/screensavers/ 里的全部图片。
 * 只读浏览——所有图片都参与轮播,不需要「选一张」。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ScreensaverPoolViewer(
    directory: File,
    nonce: Int = 0,
    onDismiss: () -> Unit,
) {
    val files = remember(directory) {
        directory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTS }
            ?.sortedBy { it.name }
            ?: emptyList()
    }
    var previewIndex by remember { mutableStateOf(-1) }

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
                nonce = nonce,
                onSelectFile = { file ->
                    val idx = files.indexOf(file)
                    if (idx >= 0) previewIndex = idx
                },
                onRestoreOriginal = null,
                onDismiss = onDismiss,
            )
        }
    }

    if (previewIndex in files.indices) {
        ScreensaverPreview(
            files = files,
            startIndex = previewIndex,
            onDismiss = { previewIndex = -1 },
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
            style = TextStyle(fontFamily = Theme.Sans, color = Theme.Champagne, fontSize = 16.sp),
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
