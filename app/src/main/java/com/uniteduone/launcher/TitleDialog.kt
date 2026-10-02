package com.uniteduone.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 改名页输入框的宽度(dp):比胶囊(268)宽,40 个字的名字不至于一进来就横向滚动;仍在右半屏(480)之内。 */
private const val TITLE_FIELD_WIDTH = 340f

/**
 * 单行改名页(spec §3;M4b 起通用化):一个文本框是唯一可聚焦项,系统输入法负责输入。
 * 不认识「卡片 / 输入源」:标题、提示、清空的含义都由调用方给——首页改卡片标题、输入源改名共用这一份
 * (R163 起行没有名字,编辑页不再有「改行名」这一处调用)。
 * 焦点账本:初始焦点只信自报 isFocused,nonce 变化重请求(铁律 2、3);守卫 `focused` 同时是 key(铁律 6)。
 * 确定(IME Done / 确定键)保存;返回取消。
 *
 * **R135(2026-09-30 外观轮)换皮**:与设置各层同一个版式——整屏 `MenuBg`,左边页名([heading])、上方一行小字写改的是谁
 * ([subtitle])、下方写按键说明([hint]);右边是一颗胶囊形的输入框(聚焦填主题色、字按亮度取对比色,与聚焦的胶囊同一个画法)。
 * 此前是屏幕中间 420 dp 的小面板,标题 16 sp、按键说明 10 sp 深灰。焦点账本、按键处理逐字未动。
 * **淡出中的残影**([LocalPageGhost]):输入框 `canFocus = false`(焦点一清输入法随之收起)、不收返回键、不再请求焦点。
 *
 * @param key 这次改的是谁(卡片的包名 / 输入 id);换了对象,输入框里的草稿随之重置。
 * @param heading 页名(如「修改标题」「改名」)。
 * @param hint 页名下方的说明:确定 / 返回 / 清空各是什么意思。
 * @param subtitle 页名上方一行小字(卡片改名 = 这张卡的显示名);null = 不画这一行。
 * @param placeholder 输入框空着时垫在里面的淡字(R135):清空之后会恢复成的那个名字(应用名 / 系统给的输入源名)。
 *   原来空着就是一条空胶囊,不知道该往里打什么、清空了会变成什么。null = 不垫。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TitleDialog(
    key: String,
    current: String,
    heading: String,
    hint: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    nonce: Int,
    subtitle: String? = null,
    placeholder: String? = null,
) {
    var text by remember(key) { mutableStateOf(current) }
    val fr = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val ghost = LocalPageGhost.current
    val accent = LocalThemeColors.current.accent
    androidx.activity.compose.BackHandler(enabled = !ghost) { onCancel() }
    LaunchedEffect(nonce, focused, ghost) {
        if (ghost) return@LaunchedEffect
        if (focused) { keyboard?.show(); return@LaunchedEffect }
        var frames = 0
        while (!focused && frames < 60) {
            withFrameNanos { }
            runCatching { fr.requestFocus() }
            frames++
        }
    }
    // 聚焦 = 填主题色、字取对比色(同聚焦的胶囊);落焦前那一两帧是未聚焦的胶囊底。
    val fill = if (focused) accent else GtvTokens.SurfaceIdle   // R144
    val ink = if (focused) contrastingTextColor(accent) else Ink.Primary
    Box(
        // 底先铺满整屏,再 imePadding():输入法弹出时内容在剩下的上半截里居中,不被键盘盖住(P1)。
        // 只改位置,焦点账本不动。依赖 MainActivity 的 setDecorFitsSystemWindows(false)
        // + 清单 adjustResize,否则 WindowInsets.ime 恒为 0、这里等于没加。
        Modifier.fillMaxSize().focusGroup().pageBackdrop().imePadding(),   // R142
    ) {
        ShellScaffold(
            left = { ShellTitle(path = subtitle, title = heading, extra = { ShellBody(hint) }) },
            right = {
                BasicTextField(
                    value = text,
                    // 截断与 sanitizeTitle 同一个 helper:光在这里 take(40) 会把第 40/41 个单元的 emoji 劈成半个代理项。
                    onValueChange = { if (!ghost) text = truncateTitle(it) },
                    singleLine = true,
                    textStyle = Type.label.copy(color = ink, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!ghost) onSave(text) }),
                    modifier = Modifier
                        .width(TITLE_FIELD_WIDTH.dp)
                        // **确定键 = 保存**(spec §3「IME 动作 Done / 确定键」,终审 Important #2)。BasicTextField 只把
                        // Key.Enter 映射到 IME 动作;第一次返回键把输入法收起、对话框还在时,DPAD_CENTER 会原样到达
                        // Compose 却没人接(keyboard.show() 只在 focused 翻转时跑,输入法也不会回来),唯一出口只剩
                        // 返回 = 取消,刚打的字全丢。抬起时保存,按下与抬起都吞掉(不吞的话按下会落进文本框自己的
                        // 按键处理)。输入法显示着时 DPAD_CENTER 被输入法窗口先吃掉、走它的 Done → onDone,不会双重
                        // 保存;Enter 仍走原来的 onDone 路径。这里**不放「已提交」布尔闩**(铁律 7):极端时序下的
                        // 重复触发由 MainActivity.onRenameSave 的幂等守卫(renameTarget 已清则返回)吸收。
                        .onPreviewKeyEvent {
                            if (it.key == Key.DirectionCenter) {
                                if (it.type == KeyEventType.KeyUp && !ghost) onSave(text)
                                true
                            } else false
                        }
                        .focusRequester(fr)
                        // 这一页只有这一个可聚焦节点,但边界仍要自己锁死(铁律 4 推论,T5 review
                        // Important #3):不锁的话 D-pad 上下会让焦点搜索冒泡出去、落到底下那一层的卡片上。
                        // 左右在有文本时先被 BasicTextField 自己吃掉去移动光标,Cancel 只在光标已经到头
                        // (行首/行尾)时才会真正起作用——够用。
                        .focusProperties {
                            if (ghost) canFocus = false
                            up = FocusRequester.Cancel; down = FocusRequester.Cancel
                            left = FocusRequester.Cancel; right = FocusRequester.Cancel
                        }
                        .onFocusChanged { focused = it.isFocused },
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = GtvLayout.MENU_ITEM_HEIGHT.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(fill)
                                .padding(horizontal = GtvLayout.MENU_ITEM_PADDING_H.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (text.isEmpty() && placeholder != null) {
                                BasicText(
                                    text = placeholder,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = Type.label.copy(color = ink.copy(alpha = 0.45f)),
                                )
                            }
                            inner()
                        }
                    },
                )
            },
        )
    }
}
