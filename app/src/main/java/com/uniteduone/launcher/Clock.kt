package com.uniteduone.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** (时间格式, 日期格式)。12 小时制必须带 a(AM/PM),否则凌晨 2 点和下午 2 点长得一样。日期格式 = design §3 的 `EEE yyyy/M/d`。 */
fun clockPatterns(is24Hour: Boolean): Pair<String, String> =
    (if (is24Hour) "HH:mm" else "h:mm a") to "EEE yyyy/M/d"

/** 当前时刻、是否 24 小时制、时区/校时跳变计数(每收到一次 TIME/TIMEZONE 广播 +1)。 */
internal data class ClockState(val now: Date, val is24Hour: Boolean, val tzTick: Int)

/** 当前时刻 + 是否 24 小时制 + tzTick。整分钟对齐刷新;监听 TIME/TIMEZONE 广播接住跳变(校时、换时区、改 12/24 开关)。
 *  放宽到 internal(2026-09-20):gtv 线的 GtvTopBar(顶栏小时钟)也读这个,不想把 tick / 时区广播那套逻辑再抄一份。 */
@Composable
internal fun rememberClockState(): ClockState {
    var now by remember { mutableStateOf(Date()) }
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
    val is24Hour = remember(tzTick) { android.text.format.DateFormat.is24HourFormat(ctx) }
    return ClockState(now, is24Hour, tzTick)
}

