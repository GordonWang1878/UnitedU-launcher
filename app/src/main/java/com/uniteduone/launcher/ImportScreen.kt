package com.uniteduone.launcher

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun BitMatrix.toBitmap(): Bitmap {
    val px = IntArray(width * height)
    for (y in 0 until height) for (x in 0 until width) {
        px[y * width + x] = if (get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
}

/**
 * 「导入图片」页(spec §3):进入即起 HTTP 服务,显示地址 + 二维码 + 已收到计数;返回键关闭并停止服务。
 * 焦点账本最简:根节点是唯一可聚焦项;守卫 `focused` 同时是 key(铁律 2、3、6);
 * 焦点是否落下只信自报 isFocused,不信 requestFocus 的返回。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ImportScreen(onExit: () -> Unit, focusNonce: Int = 0) {
    val ctx = LocalContext.current
    var received by remember { mutableStateOf(0) }
    var lastName by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }   // R.string 资源 id

    // 服务寿命 = 本页寿命:起在这里、停在 onDispose(返回键 → MainActivity 把本页拆掉)。
    DisposableEffect(Unit) {
        val ip = UploadServer.localAddress()
        var server: UploadServer? = null
        if (ip == null) {
            error = R.string.import_error_no_network
        } else {
            server = UploadServer.startOnFreePort(ctx) { name -> received++; lastName = name }
            if (server == null) error = R.string.import_error_port
            else url = "http://$ip:${server.listeningPort}/"
        }
        onDispose { server?.stop() }
    }
    val qr by produceState<Bitmap?>(null, url) {
        value = url?.let { u -> withContext(Dispatchers.IO) { runCatching { qrMatrix(u).toBitmap() }.getOrNull() } }
    }

    val fr = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler { onExit() }
    LaunchedEffect(focusNonce, focused) {
        if (focused) return@LaunchedEffect
        var frames = 0
        while (!focused && frames < 60) {
            withFrameNanos { }
            runCatching { fr.requestFocus() }
            frames++
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Theme.EditScreenBackground)
            .focusRequester(fr)
            .focusProperties {
                up = FocusRequester.Cancel; down = FocusRequester.Cancel
                left = FocusRequester.Cancel; right = FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            BasicText(
                text = stringResource(R.string.import_title),
                style = TextStyle(fontFamily = Theme.Sans, fontWeight = FontWeight.Medium, color = Theme.EmphasisText, fontSize = 24.sp),
            )
            val err = error
            if (err != null) {
                BasicText(
                    text = stringResource(err),
                    style = TextStyle(fontFamily = Theme.Sans, color = Theme.HintText, fontSize = 16.sp),
                )
            } else {
                qr?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(300.dp)) }
                BasicText(
                    text = url ?: "",
                    style = TextStyle(fontFamily = Theme.Sans, fontWeight = FontWeight.Medium, color = Theme.Champagne, fontSize = 28.sp),
                )
                val last = lastName
                BasicText(
                    text = stringResource(R.string.import_received, received) +
                        (if (last != null) " · " + stringResource(R.string.import_last, last) else ""),
                    style = TextStyle(fontFamily = Theme.Sans, color = Theme.SecondaryText, fontSize = 15.sp),
                )
            }
            BasicText(
                text = stringResource(R.string.import_hint_back),
                style = TextStyle(fontFamily = Theme.Sans, color = Theme.FooterHintText, fontSize = 12.sp),
            )
        }
    }
}
