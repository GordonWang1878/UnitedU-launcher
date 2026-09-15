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
 * 确定(IME Done)保存;返回取消;清空 = 恢复应用名。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TitleDialog(ref: CardRef, current: String, onSave: (String) -> Unit, onCancel: () -> Unit, nonce: Int) {
    var text by remember(ref.pkg) { mutableStateOf(current) }
    val fr = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
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
                style = TextStyle(fontFamily = Theme.Sans, fontWeight = FontWeight.Medium, color = Theme.Champagne, fontSize = 16.sp),
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = ref.label.ifBlank { ref.pkg },
                style = TextStyle(fontFamily = Theme.Sans, color = Theme.HintText, fontSize = 12.sp),
            )
            Spacer(Modifier.height(16.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(MAX_TITLE_CHARS) },
                singleLine = true,
                textStyle = TextStyle(fontFamily = Theme.Sans, color = Theme.EmphasisText, fontSize = 16.sp),
                cursorBrush = SolidColor(Theme.Champagne),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                modifier = Modifier
                    .fillMaxWidth()
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
                    .background(if (focused) Theme.Champagne.copy(alpha = 0.16f) else Theme.UnfocusedSurface)
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
