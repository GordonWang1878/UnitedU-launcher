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
 * 某条提示该把 ON_STOP 豁免窗撑多久(epoch ms 的增量,spec §3)。
 * 默认 30 s 够系统安装器走完;「请先允许安装未知应用」那条不够——用户要在电视的设置页里
 * 翻到本应用、开开关、再返回,遥控器上这几步在真机上常常超过 30 s,窗一过页面就被关掉、
 * 服务也停了,他回来只看见桌面(Minor #6)。这条给 120 s。
 */
private fun windowFor(noticeId: Int): Long =
    if (noticeId == R.string.import_apk_needs_permission) 120_000L else 30_000L

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
    var notice by remember { mutableStateOf<Int?>(null) }  // R.string 资源 id(APK 安装提示,spec §4)
    // epoch ms:系统安装器 / 「允许安装未知应用」设置页把本 Activity 推到后台时也会触发 ON_STOP,
    // 这条路径下不能照常关页(会把安装流程中间的服务杀掉)——用时间戳而非布尔闩(铁律 7),
    // 窗口到期自然失效,不需要谁去清。窗长见 [windowFor];只有页面还在前台时才撑窗。
    var suppressStopUntil by remember { mutableStateOf(0L) }
    // 被豁免窗压掉的 ON_STOP 计数(不是布尔闩,铁律 7):每压掉一次 +1,下面的效果靠它重新触发到期复查。
    var suppressedStopTick by remember { mutableStateOf(0) }

    // 本页整段生命周期里 LocalLifecycleOwner 就是宿主 Activity,不会中途换人——所以下面几个
    // DisposableEffect 捕获它是安全的,不需要把它写进 key(写进去反而会让服务白白重起)。
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle

    // 服务寿命 = 本页寿命:起在这里、停在 onDispose(返回键 → MainActivity 把本页拆掉)。
    DisposableEffect(Unit) {
        val ip = UploadServer.localAddress()
        var server: UploadServer? = null
        if (ip == null) {
            error = R.string.import_error_no_network
        } else {
            server = UploadServer.startOnFreePort(
                ctx,
                onSaved = { name -> received++; lastName = name; notice = null },
                // **只有前台才撑窗**(spec §4):窗的用途是「别把正在进行的安装流程关掉」,
                // 页面本就不在前台时没有这样的流程可护——照撑的话,局域网上任何人每 30 s 传一次
                // APK 就能让这个无密码服务在用户已经切去看视频之后无限期活着。
                onNotice = { id ->
                    notice = id
                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                        suppressStopUntil = System.currentTimeMillis() + windowFor(id)
                    }
                },
                isForeground = { lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) },
            )
            if (server == null) error = R.string.import_error_port
            else url = "http://$ip:${server.listeningPort}/"
        }
        onDispose { server?.stop() }
    }

    // 无密码的局域网服务不能活过用户离开:待机 / 切到别的应用(ON_STOP)→ 关掉本页
    // (上面那个 DisposableEffect 的 onDispose 负责真正停服务)。HOME 键另有 MainActivity.onNewIntent
    // 兜底——onNewIntent 不保证总是先于 ON_STOP,两条路都要收。
    // 例外(spec §4 末段,T2 复审发现):系统安装器 / 未知来源设置页同样是全屏 Activity、同样触发
    // ON_STOP——豁免窗内(suppressStopUntil)不关页,窗口过后再照常关。
    // **这里的 onExit() 能生效,靠的是 Compose(≥1.5)在 Activity STOPPED 期间照常重组**
    // ——停的只有帧时钟,重组与 LaunchedEffect 的协程都还在跑,所以下面那个到期复查也收得到。
    // 不要因为「后台还能跑」看着可疑就把 stop() 挪到别处(比如挪进 ON_STOP 观察者里直接停服务):
    // 服务寿命必须与本页组合寿命绑死在同一个 onDispose 上,拆开就会出现「页面还在、服务已停」。
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                if (System.currentTimeMillis() > suppressStopUntil) onExit()
                // 窗内压下的这次交给下面的到期复查效果兜底,而不是就此不管。
                else suppressedStopTick++
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // 被压掉的 ON_STOP 到期复查(T4 复审补,spec §4 末段):窗口过了 Activity 仍没回到前台
    // (待机、切到别的应用——不是从安装流程正常返回)→ 关页停服务,否则无密码的局域网服务会
    // 无限期活着。while 而非单次 delay:等待期间若又传一个 APK 把窗口续了,按最新的
    // suppressStopUntil 重新等一轮,不会提前关掉正在进行的安装。
    LaunchedEffect(suppressedStopTick) {
        if (suppressedStopTick == 0) return@LaunchedEffect
        while (System.currentTimeMillis() < suppressStopUntil) {
            kotlinx.coroutines.delay((suppressStopUntil - System.currentTimeMillis()).coerceAtLeast(0L))
        }
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) onExit()
    }

    val qr by produceState<Bitmap?>(null, url) {
        value = url?.let { u -> withContext(Dispatchers.IO) { runCatching { qrMatrix(u).toBitmap() }.getOrNull() } }
    }

    val fr = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val highlight = LocalThemeColors.current.highlight   // 地址与提示文字跟主题 highlight
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
                    style = TextStyle(fontFamily = Theme.Sans, fontWeight = FontWeight.Medium, color = highlight, fontSize = 28.sp),
                )
                val last = lastName
                BasicText(
                    text = stringResource(R.string.import_received, received) +
                        (if (last != null) " · " + stringResource(R.string.import_last, last) else ""),
                    style = TextStyle(fontFamily = Theme.Sans, color = Theme.SecondaryText, fontSize = 15.sp),
                )
                val n = notice
                if (n != null) BasicText(
                    text = stringResource(n),
                    style = TextStyle(fontFamily = Theme.Sans, color = highlight, fontSize = 15.sp),
                )
            }
            BasicText(
                text = stringResource(R.string.import_hint_back),
                style = TextStyle(fontFamily = Theme.Sans, color = Theme.FooterHintText, fontSize = 12.sp),
            )
        }
    }
}
