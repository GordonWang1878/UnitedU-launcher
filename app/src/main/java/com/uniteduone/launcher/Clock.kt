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
 * 右上角时钟。格式照 v4 屏幕上的实际样子:`<时间> EEE yyyy/M/d`。design §3:
 * 12/24 小时跟系统的「使用24小时格式」开关走,星期跟系统语言走(比如中文系统显示"周一"),
 * [showDate] 关掉时只留时间,星期和 `yyyy/M/d` 一起隐藏。
 * Locale 优先用 [AppLocale.current](应用内语言切换,见 `LocaleOverride.kt`),
 * 未覆盖(跟随系统)时落回 [Locale.getDefault]——不再像早期版本那样固定 ENGLISH。
 */
@Composable
fun Clock(
    modifier: Modifier = Modifier,
    showDate: Boolean = true,
) {
    // 文字色 = 主题 highlight 叠 0.55 alpha,读全局主题色。
    val highlight = LocalThemeColors.current.highlight
    var now by remember { mutableStateOf(Date()) }
    // 时区变更 / NTP 校时是**跳变**,整分钟定时器接不住:不监听的话最多显示错一分钟,
    // 换时区则会一直错到下一个整分。系统的 12/24 小时开关也会触发 ACTION_TIME_CHANGED
    // (AOSP Settings 改这项时就是发的这个广播),所以不用再单独监听 Settings.System。
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
    // is24Hour 挂在 tzTick 上重读就够:不必每分钟都查一次系统设置,理由同上面的广播说明。
    val is24Hour = remember(tzTick) { android.text.format.DateFormat.is24HourFormat(ctx) }
    val pattern = remember(showDate, is24Hour) {
        // 12 小时制必须带 a(AM/PM):"h:mm" 单看数字,凌晨 2 点和下午 2 点长得一模一样,
        // 一天里一半时间是歧义的——2026-09-15 复审发现,补上标记。
        val timePart = if (is24Hour) "HH:mm" else "h:mm a"
        if (showDate) "$timePart EEE yyyy/M/d" else timePart
    }
    // tzTick 作为 key:SimpleDateFormat 出生时就把时区绑死了,换时区后必须重建。
    val fmt = remember(tzTick, pattern) { SimpleDateFormat(pattern, AppLocale.current ?: Locale.getDefault()) }
    BasicText(
        text = fmt.format(now),
        modifier = modifier,
        style = TextStyle(fontFamily = Theme.Sans, color = highlight.copy(alpha = 0.55f), fontSize = 16.sp),
    )
}
