package com.uniteduone.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「修改标题」对话框(spec §3):一个文本框是唯一可聚焦项,系统输入法负责输入。
 * 焦点账本:初始焦点只信自报 isFocused,nonce 变化重请求(铁律 2、3);守卫 `focused` 同时是 key(铁律 6)。
 * 确定(IME Done / 确定键)保存;返回取消;清空 = 恢复应用名。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TitleDialog(ref: CardRef, current: String, onSave: (String) -> Unit, onCancel: () -> Unit, nonce: Int) {
    var text by remember(ref.pkg) { mutableStateOf(current) }
    val fr = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val highlight = LocalThemeColors.current.highlight   // 标题、光标、聚焦底色都跟主题 highlight
    androidx.activity.compose.BackHandler { onCancel() }
    LaunchedEffect(nonce, focused) {
        if (focused) { keyboard?.show(); return@LaunchedEffect }
        var frames = 0
        while (!focused && frames < 60) {
            withFrameNanos { }
            runCatching { fr.requestFocus() }
            frames++
        }
    }
    Box(
        Modifier.fillMaxSize().focusGroup().background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(14.dp)).background(Theme.DialogSurface).width(420.dp).padding(24.dp),
        ) {
            BasicText(
                text = stringResource(R.string.title_dialog_title),
                style = TextStyle(fontFamily = Theme.Sans, fontWeight = FontWeight.Medium, color = highlight, fontSize = 16.sp),
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = ref.label.ifBlank { ref.pkg },
                style = TextStyle(fontFamily = Theme.Sans, color = Theme.HintText, fontSize = 12.sp),
            )
            Spacer(Modifier.height(16.dp))
            BasicTextField(
                value = text,
                // 截断与 sanitizeTitle 同一个 helper:光在这里 take(40) 会把第 40/41 个单元的 emoji 劈成半个代理项。
                onValueChange = { text = truncateTitle(it) },
                singleLine = true,
                textStyle = TextStyle(fontFamily = Theme.Sans, color = Theme.EmphasisText, fontSize = 16.sp),
                cursorBrush = SolidColor(highlight),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    // **确定键 = 保存**(spec §3「IME 动作 Done / 确定键」,终审 Important #2)。BasicTextField 只把
                    // Key.Enter 映射到 IME 动作;第一次返回键把输入法收起、对话框还在时,DPAD_CENTER 会原样到达
                    // Compose 却没人接(keyboard.show() 只在 focused 翻转时跑,输入法也不会回来),唯一出口只剩
                    // 返回 = 取消,刚打的字全丢。抬起时保存,按下与抬起都吞掉(不吞的话按下会落进文本框自己的
                    // 按键处理)。输入法显示着时 DPAD_CENTER 被输入法窗口先吃掉、走它的 Done → onDone,不会双重
                    // 保存;Enter 仍走原来的 onDone 路径。这里**不放「已提交」布尔闩**(铁律 7):极端时序下的
                    // 重复触发由 MainActivity.onRenameSave 的幂等守卫(renameTarget 已清则返回)吸收。
                    .onPreviewKeyEvent {
                        if (it.key == Key.DirectionCenter) {
                            if (it.type == KeyEventType.KeyUp) onSave(text)
                            true
                        } else false
                    }
                    .focusRequester(fr)
                    // 对话框里只有这一个可聚焦节点,但边界仍要自己锁死(铁律 4 推论,T5 review
                    // Important #3):不锁的话 D-pad 上下会让焦点搜索冒泡出对话框、落到蒙版
                    // 后面的卡片或齿轮上。左右在有文本时先被 BasicTextField 自己吃掉去移动
                    // 光标,Cancel 只在光标已经到头(行首/行尾)时才会真正起作用——够用。
                    .focusProperties {
                        up = FocusRequester.Cancel; down = FocusRequester.Cancel
                        left = FocusRequester.Cancel; right = FocusRequester.Cancel
                    }
                    .onFocusChanged { focused = it.isFocused }
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (focused) highlight.copy(alpha = 0.16f) else Theme.UnfocusedSurface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(12.dp))
            BasicText(
                text = stringResource(R.string.title_dialog_hint),
                style = TextStyle(fontFamily = Theme.Sans, color = Theme.FooterHintText, fontSize = 10.sp),
            )
        }
    }
}
