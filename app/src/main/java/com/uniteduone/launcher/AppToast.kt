package com.uniteduone.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * **应用内提示条**(R139,2026-09-30 Gordon:「系统 Toast 没换皮」)。系统 Toast 是另一套视觉:Roboto、灰底方角、
 * 与我们的字号七档 / 胶囊都对不上。现在所有提示都画成我们自己的一颗胶囊:屏幕下方居中,[GtvTokens.PillTrack] 底
 * (与顶栏药丸组同一个颜色)、1 dp 淡白描边、15 sp 主文字色,最多两行。
 *
 * - **时长**照系统 Toast:短 2 s(LENGTH_SHORT)、长 3.5 s(LENGTH_LONG)。新的一条到来时顶掉旧的(同系统 Toast)。
 * - **动效**:淡入 + 上浮 8 dp(200 ms),淡出 250 ms;换一条时旧的先快速淡出(120 ms)再淡入新的。
 * - **不可聚焦、不收按键**:纯显示,画在整棵树最上层,不进任何焦点账本(同 R131 左侧说明区)。
 * - 无障碍:`liveRegion = Polite`,读屏会念出来(系统 Toast 原本也会)。
 * - **只在前台用**:Activity 不在前台时(例如刚把用户送去别的应用)这里画了也没人看见,
 *   MainActivity.toast 照旧交给系统 Toast(见那里)。
 */
data class ToastMessage(val text: String, val long: Boolean, val id: Long)

/** 页面里发提示用它(代替 `Toast.makeText`):第二个参数 = 长提示(3.5 s)。MainActivity 在根上提供。 */
val LocalToast = staticCompositionLocalOf<(String, Boolean) -> Unit> { { _, _ -> } }

private const val TOAST_SHORT_MS = 2000L
private const val TOAST_LONG_MS = 3500L
private const val TOAST_IN_MS = 200
private const val TOAST_OUT_MS = 250
private const val TOAST_SWAP_MS = 120
private const val TOAST_RISE_DP = 8f
/** 距屏幕底边(dp):与系统 Toast 在这台电视上的位置相近。 */
private const val TOAST_BOTTOM_DP = 48f
private const val TOAST_MAX_WIDTH_DP = 640f
/** 圆角 = 单行胶囊高度的一半(15 sp 一行 + 上下 14 dp ≈ 48 dp),两行时是圆角矩形。 */
private const val TOAST_CORNER_DP = 24f

/**
 * 画 [message];到时 [onTimeout](参数是那一条的 id,调用方只在它仍是当前那条时清掉——换了新的一条就不清)。
 */
@Composable
fun ToastHost(message: ToastMessage?, onTimeout: (Long) -> Unit) {
    var shown by remember { mutableStateOf<ToastMessage?>(null) }
    val fade = remember { Animatable(0f) }
    val timeout by rememberUpdatedState(onTimeout)
    LaunchedEffect(message?.id) {
        if (message == null) {
            fade.animateTo(0f, tween(TOAST_OUT_MS))
            shown = null
            return@LaunchedEffect
        }
        if (shown != null && fade.value > 0f) fade.animateTo(0f, tween(TOAST_SWAP_MS))
        shown = message
        fade.animateTo(1f, tween(TOAST_IN_MS))
        delay(if (message.long) TOAST_LONG_MS else TOAST_SHORT_MS)
        timeout(message.id)
    }
    val m = shown ?: return
    val shape = RoundedCornerShape(TOAST_CORNER_DP.dp)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        BasicText(
            text = m.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = Type.label.copy(color = Ink.Primary, textAlign = TextAlign.Center, lineBreak = Type.Balanced),
            modifier = Modifier
                .padding(bottom = TOAST_BOTTOM_DP.dp)
                .graphicsLayer {
                    alpha = fade.value
                    translationY = (1f - fade.value) * TOAST_RISE_DP.dp.toPx()
                }
                .widthIn(max = TOAST_MAX_WIDTH_DP.dp)
                .clip(shape)
                .background(GtvTokens.PillTrack)
                .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                .padding(horizontal = 28.dp, vertical = 14.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
