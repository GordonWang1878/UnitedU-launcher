package com.uniteduone.launcher

import androidx.compose.runtime.Composable

/**
 * 两按钮确认页(删除一行、删除屏保图库里的一项)。
 *
 * **R135(2026-09-30 外观轮)起它就是一页 [GearMenu]**:左边是问题([title])与后果([body]),上方一行小字写这页属于谁
 * ([eyebrow]);右边两颗胶囊,**「取消」在上、默认焦点在它**(spec §4 终审:防止误触),破坏性动作在下面、要多按一次下键才够到。
 * 与设置里「恢复默认」的确认层(R74)同一个样子。此前是屏幕中间 400 dp 的小面板、两颗带描边的方按钮左右排,
 * 自己另有一份焦点账本;现在焦点全交给 [GearMenu] 那一套(nonce 初始循环 + `holder == null` 看门狗,铁律 2 / 3 / 6 / 7),
 * 返回键走 [onCancel],与「取消」同一效果。
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    okLabel: String,
    cancelLabel: String,
    nonce: Int,
    onOk: () -> Unit,
    onCancel: () -> Unit,
    eyebrow: String? = null,
) {
    GearMenu(
        items = listOf(MenuItem(cancelLabel, "", onCancel), MenuItem(okLabel, "", onOk)),
        onDismiss = onCancel,
        nonce = nonce,
        title = title,
        eyebrow = eyebrow,
        body = body,
    )
}
