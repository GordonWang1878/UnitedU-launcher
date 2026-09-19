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
private data class ClockState(val now: Date, val is24Hour: Boolean, val tzTick: Int)

/** 当前时刻 + 是否 24 小时制 + tzTick。整分钟对齐刷新;监听 TIME/TIMEZONE 广播接住跳变(校时、换时区、改 12/24 开关)。 */
@Composable
private fun rememberClockState(): ClockState {
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

/**
 * M8 hero 主体:大字时钟 84sp Medium + 日期 24sp(spec §1.4),颜色 accent(spec §0「accent 落点」)。
 * 位置由调用方给(HomeScreen / UnitedUDream:左对齐 SidePadding、顶 HomeLayout.HERO_TOP);不可聚焦。
 * [shadow](M5 spec §1.5):自定义屏保 / 系统屏保轮播照片时为真——照片可能很亮,时间与日期各加一层淡阴影
 * (黑 α0.55、下移 2、模糊 16);不做描边、不做底板,平时不加(壁纸本来就压暗过)。
 */
@Composable
fun HeroClock(modifier: Modifier = Modifier, showDate: Boolean = true, shadow: Boolean = false) {
    val accent = LocalThemeColors.current.accent
    val state = rememberClockState()
    val locale = AppLocale.current ?: Locale.getDefault()
    val (timePattern, datePattern) = clockPatterns(state.is24Hour)
    // SimpleDateFormat 出生时就把时区绑死:tzTick 变(换时区 / 校时 / 改 12-24 开关)就重建,不能只看 pattern。
    val timeFmt = remember(state.tzTick, timePattern, locale) { SimpleDateFormat(timePattern, locale) }
    val dateFmt = remember(state.tzTick, datePattern, locale) { SimpleDateFormat(datePattern, locale) }
    val textShadow = if (shadow) {
        Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 2f), blurRadius = 16f)
    } else {
        null
    }
    Column(modifier) {
        BasicText(
            text = timeFmt.format(state.now),
            style = TextStyle(
                fontFamily = Theme.Sans, fontWeight = FontWeight.Medium,
                fontSize = 84.sp, lineHeight = 84.sp, color = accent, shadow = textShadow,
            ),
        )
        if (showDate) {
            BasicText(
                text = dateFmt.format(state.now),
                modifier = Modifier.padding(top = 8.dp),
                style = TextStyle(
                    fontFamily = Theme.Sans, fontSize = 24.sp,
                    color = accent.copy(alpha = 0.85f), shadow = textShadow,
                ),
            )
        }
    }
}
