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
    var focusedIdx by remember { mutableStateOf(0) }
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(nonce) {
        landed = false
        val i = focusedIdx.coerceIn(0, rowFocus.lastIndex)
        var frames = 0
        while (!landed && frames < 60) {
            withFrameNanos { }
            runCatching { rowFocus[i].requestFocus() }
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
                    color = Theme.Champagne,
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
                    onFocused = { landed = true; focusedIdx = i },
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
    onFocused: () -> Unit = {},
    isFirst: Boolean = false,
    isLast: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
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
                        Theme.Champagne.copy(alpha = 0.12f),
                        Color.Transparent,
                    )
                )
                else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
            )
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
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
                    .background(Theme.Champagne)
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
