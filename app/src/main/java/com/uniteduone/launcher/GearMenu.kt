package com.uniteduone.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class MenuItem(val label: String, val hint: String, val action: () -> Unit)

/**
 * 菜单浮层。齿轮菜单、编辑页条目菜单、首页长按卡片菜单共用这一份。
 * @param title 标题;null = 沿用齿轮菜单的「设置」。长按菜单传该卡的显示名。
 */
@Composable
fun GearMenu(items: List<MenuItem>, onDismiss: () -> Unit, nonce: Int = 0, title: String? = null) {
    val rowFocus = remember(items.size) { List(items.size.coerceAtLeast(1)) { FocusRequester() } }
    // 下面两个循环都在协程里跑,读的必须是**当前**这一份 requester:items.size 一变 remember 就换新表,
    // 捕获启动时那一份的话,旧表挂不上任何节点,requestFocus 次次抛、被 runCatching 吞掉,循环空转。
    val requesters by rememberUpdatedState(rowFocus)
    var focusedIdx by remember { mutableStateOf(0) }
    /** 现在持有焦点的那一项(只信控件自报,铁律 4);null = 菜单里没有。与 focusedIdx(回来落哪)分开(铁律 5)。 */
    var holder by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(nonce) {
        val i = focusedIdx.coerceIn(0, requesters.lastIndex)
        var frames = 0
        while (holder == null && frames < 60) {
            withFrameNanos { }
            runCatching { requesters[i].requestFocus() }
            frames++
        }
    }
    // **看门狗**(铁律 3):初始循环只管「打开 / nonce 变」那一拍,落地之后焦点再被清掉时它早已退出,
    // 没有人会再请求。守卫与 key 都是 holder == null(铁律 6)。每轮最多 60 帧:落地后再丢
    // (holder 由非 null 变回 null,key 翻转)自然重新武装,不是闩(铁律 7);也不会在请求注定
    // 落空时每帧空转到菜单关掉为止 —— 下面这种情况就是注定落空:
    // **触摸模式下两个循环都落不下**(2026-09-19 模拟器实测,M7「装包后约 4 s 按 MENU,菜单开着
    // 但焦点数为 0,第一下 DOWN 才落到第 1 项」最可能的根因:复现脚本非触摸模式下 0/47 次复现,
    // 触摸模式下 13/13 次复现)。菜单项用的是 foundation 的 clickable,它自带
    // FocusableInNonTouchMode(canFocus = inputMode != Touch);之前的指针事件(触摸屏、鼠标都算)
    // 让窗口进了触摸模式,这个状态跨冷启动带进新窗口,而 MENU 既不是导航键也不是打字键,不会让窗口
    // 离开触摸模式。第一下方向键才让框架退出触摸模式、把默认焦点给最上面那项,并吃掉这一下。
    LaunchedEffect(holder == null) {
        if (holder != null) return@LaunchedEffect
        repeat(3) { withFrameNanos { } }   // 换项时 lost / got 可能分属相邻两帧,中间那一帧的 null 不算丢
        var frames = 0
        while (holder == null && frames < 60) {
            runCatching { requesters[focusedIdx.coerceIn(0, requesters.lastIndex)].requestFocus() }
            withFrameNanos { }
            frames++
        }
    }

    androidx.activity.compose.BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Theme.DialogSurface)
                .width(320.dp)
                .padding(vertical = 20.dp),
        ) {
            BasicText(
                text = title ?: stringResource(R.string.menu_settings_title),
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    fontWeight = FontWeight.Medium,
                    color = LocalThemeColors.current.highlight,
                    fontSize = 16.sp,
                    letterSpacing = 1.sp,
                ),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 0.dp),
            )

            Spacer(Modifier.height(16.dp))

            items.forEachIndexed { i, item ->
                MenuRow(
                    item = item,
                    modifier = Modifier.focusRequester(rowFocus[i]),
                    onFocusChange = { got ->
                        // 得失顺序保护(同 HomeScreen.report):只有「本项仍是持有者」时 lost 才作废
                        if (got) { holder = i; focusedIdx = i } else if (holder == i) holder = null
                    },
                    isFirst = i == 0,
                    isLast = i == items.lastIndex,
                )
            }

            Spacer(Modifier.height(12.dp))

            BasicText(
                text = stringResource(R.string.menu_back_to_close),
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    color = Theme.FooterHintText,
                    fontSize = 10.sp,
                ),
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MenuRow(
    item: MenuItem,
    modifier: Modifier = Modifier,
    /** 得到 / 失去都报(铁律 4):GearMenu 的看门狗靠「失去」知道菜单里已经没有焦点。 */
    onFocusChange: (Boolean) -> Unit = {},
    isFirst: Boolean = false,
    isLast: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val highlight = LocalThemeColors.current.highlight
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusProperties {
                if (isFirst) up = FocusRequester.Cancel
                if (isLast) down = FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (focused) Brush.horizontalGradient(
                    listOf(
                        highlight.copy(alpha = 0.12f),
                        Color.Transparent,
                    )
                )
                else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
            )
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .clickable(onClick = item.action)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 聚焦时左侧竖条指示器
        if (focused) {
            Box(
                Modifier
                    .width(2.5.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(highlight)
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            BasicText(
                text = item.label,
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
                    color = if (focused) Theme.EmphasisText else Theme.MenuItemText,
                    fontSize = 14.sp,
                ),
            )
            BasicText(
                text = item.hint,
                style = TextStyle(
                    fontFamily = Theme.Sans,
                    color = if (focused) Theme.MenuHintTextFocused else Theme.MenuHintText,
                    fontSize = 11.sp,
                ),
            )
        }
    }
}
