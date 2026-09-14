package com.uniteduone.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 右上角时钟。格式照 v4 屏幕上的实际样子:`HH:mm EEE yyyy/M/d`,英文星期。
 * Locale 固定 ENGLISH——Projectivy 当初是靠把整个应用语言改成 English 才拿到英文星期的,
 * 这里直接指定,不依赖系统语言。
 */
@Composable
fun Clock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(Date()) }
    // 时区变更 / NTP 校时是**跳变**,整分钟定时器接不住:不监听的话最多显示错一分钟,
    // 换时区则会一直错到下一个整分。
    var tzTick by remember { mutableStateOf(0) }
    val ctx = LocalContext.current
    DisposableEffect(ctx) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { now = Date(); tzTick++ }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        runCatching { ctx.registerReceiver(r, filter) }
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    LaunchedEffect(tzTick) {
        while (true) {
            now = Date()
            val msIntoMinute = System.currentTimeMillis() % 60_000L
            delay(60_000L - msIntoMinute)
        }
    }
    // tzTick 作为 key:SimpleDateFormat 出生时就把时区绑死了,换时区后必须重建。
    val fmt = remember(tzTick) { SimpleDateFormat("HH:mm EEE yyyy/M/d", Locale.ENGLISH) }
    BasicText(
        text = fmt.format(now),
        modifier = modifier,
        style = TextStyle(fontFamily = Theme.Sans, color = Theme.Champagne.copy(alpha = 0.55f), fontSize = 16.sp),
    )
}
