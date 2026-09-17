package com.uniteduone.launcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 通用两按钮确认框(spec §4「恢复默认」是第一个用它的场景,以后别的危险动作可以复用)。
 * 与 [TitleDialog] / `AboutPlaceholder` 同一份焦点账本手法,但焦点原子是**两个**按钮而不是一个:
 * - 铁律 2/4:焦点落没落下只信按钮自报 [onFocusChanged],不信 `requestFocus()` 的返回值;
 * - 铁律 3:两个按钮**逐个**挂 `FocusRequester`,初始焦点循环只信自报、`nonce` 变化重来一轮;
 * - **默认焦点在「取消」**(spec §4 终审,防止误触恢复);
 * - 左右键靠 `focusProperties` 显式接到对方的 requester 上(与 SettingsScreen 的 GroupItem/SettingRow
 *   同一手法),上下键锁 `FocusRequester.Cancel`(不是"取消"按钮,是框架的"别再找了"哨兵)——
 *   两个按钮都在同一行,没有任何方向可以纵向移出去;
 * - BACK 走 [onCancel],与「取消」按钮同一效果。
 *
 * [focusedBtn] 用「哪个按钮持有焦点」(0=取消、1=确定,null=都没有)当唯一的焦点状态,
 * 得失顺序保护同 `SettingsScreen.report`:左右键切换时新按钮先报 got、旧按钮后报 lost,
 * 不能让旧按钮的 lost 把新按钮的记录抹掉。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    okLabel: String,
    cancelLabel: String,
    nonce: Int,
    onOk: () -> Unit,
    onCancel: () -> Unit,
) {
    val cancelReq = remember { FocusRequester() }
    val okReq = remember { FocusRequester() }
    var focusedBtn by remember { mutableStateOf<Int?>(null) }
    fun report(idx: Int, got: Boolean) {
        if (got) focusedBtn = idx else if (focusedBtn == idx) focusedBtn = null
    }
    val highlight = LocalThemeColors.current.highlight

    androidx.activity.compose.BackHandler { onCancel() }

    // 初始焦点循环:落在「取消」上。守卫 `focusedBtn != null` 同时是 key(铁律 6)——
    // 焦点若在别处(理论上不会,只有这两个节点)丢失变回 null,同一份逻辑会自己再送一轮。
    LaunchedEffect(nonce, focusedBtn) {
        if (focusedBtn != null) return@LaunchedEffect
        var frames = 0
        while (focusedBtn == null && frames < 60) {
            withFrameNanos { }
            runCatching { cancelReq.requestFocus() }
            frames++
        }
    }

    Box(
        Modifier.fillMaxSize().focusGroup().background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(14.dp)).background(Theme.DialogSurface).width(400.dp).padding(24.dp),
        ) {
            BasicText(
                text = title,
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    fontWeight = FontWeight.Medium,
                    color = Theme.EmphasisText,
                    fontSize = 17.sp,
                ),
            )
            Spacer(Modifier.height(10.dp))
            BasicText(
                text = body,
                style = TextStyle(fontFamily = Theme.Sans, color = Theme.FootnoteText, fontSize = 12.sp, lineHeight = 17.sp),
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 「取消」在左、默认焦点;「恢复」在右——把破坏性动作放在需要多按一次右键才够到的位置。
                DialogButton(
                    label = cancelLabel,
                    focusRequester = cancelReq,
                    leftReq = null,
                    rightReq = okReq,
                    highlight = highlight,
                    onFocusChange = { report(0, it) },
                    onClick = onCancel,
                )
                DialogButton(
                    label = okLabel,
                    focusRequester = okReq,
                    leftReq = cancelReq,
                    rightReq = null,
                    highlight = highlight,
                    onFocusChange = { report(1, it) },
                    onClick = onOk,
                )
            }
        }
    }
}

/** 确认框里的一个按钮:左右显式接到邻居的 requester,上下锁 `Cancel`(铁律 3/4)。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DialogButton(
    label: String,
    focusRequester: FocusRequester,
    leftReq: FocusRequester?,
    rightReq: FocusRequester?,
    highlight: Color,
    onFocusChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) highlight.copy(alpha = 0.16f) else Theme.UnfocusedSurface)
            .then(
                if (focused) Modifier.border(
                    BorderStroke(1.dp, highlight.copy(alpha = 0.7f)),
                    RoundedCornerShape(10.dp),
                ) else Modifier,
            )
            .focusRequester(focusRequester)
            .focusProperties {
                up = FocusRequester.Cancel; down = FocusRequester.Cancel
                left = leftReq ?: FocusRequester.Cancel
                right = rightReq ?: FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                fontFamily = Theme.Sans,
                fontWeight = FontWeight.Medium,
                color = if (focused) highlight else Theme.ButtonText,
                fontSize = 14.sp,
            ),
        )
    }
}
