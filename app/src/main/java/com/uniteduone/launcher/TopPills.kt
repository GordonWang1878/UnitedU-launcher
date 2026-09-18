package com.uniteduone.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme

/**
 * 右上 pill 组(spec §1.5):胶囊底 surface α0.65,内含两个 tv-material IconButton(Medium 40dp,图标 20dp,库默认),
 * 聚焦反白 + 1.1 倍由库出。未聚焦图标色 = accent(spec §0「accent 落点」)。
 * 焦点:两个按钮互为左右;上 / 外侧锁 Cancel;下 = 记住的那一格(rowsEmpty 时锁)。
 * 上报 `onFocusChange(col, got)`,col 0 = 设置(挂 gearFocus,还原目标),1 = 屏保。
 */
// FocusRequester.Cancel 是实验性 API(与 HomeScreen.kt 同理,那边同一个注解就是为它加的)。
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TopPills(
    gearFocus: FocusRequester,
    canFocus: Boolean,
    rowsEmpty: Boolean,
    downTarget: FocusRequester?,
    onSettings: () -> Unit,
    onScreensaver: () -> Unit,
    onFocusChange: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val down = if (rowsEmpty) FocusRequester.Cancel else (downTarget ?: FocusRequester.Default)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
            .padding(4.dp)
            .focusProperties { this.canFocus = canFocus },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PillButton(
            icon = Icons.Filled.Settings,
            descriptionRes = R.string.menu_settings_title,
            onClick = onSettings,
            onFocusChange = { onFocusChange(0, it) },
            modifier = Modifier
                .focusRequester(gearFocus)
                .focusProperties { up = FocusRequester.Cancel; left = FocusRequester.Cancel; this.down = down },
        )
        PillButton(
            icon = Icons.Filled.Slideshow,
            descriptionRes = R.string.home_screensaver_button,
            onClick = onScreensaver,
            onFocusChange = { onFocusChange(1, it) },
            modifier = Modifier
                .focusProperties { up = FocusRequester.Cancel; right = FocusRequester.Cancel; this.down = down },
        )
    }
}

@Composable
private fun PillButton(
    icon: ImageVector,
    descriptionRes: Int,
    onClick: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalThemeColors.current.accent
    IconButton(
        onClick = onClick,
        modifier = modifier.onFocusChanged { onFocusChange(it.isFocused) },
        colors = IconButtonDefaults.colors(containerColor = Color.Transparent, contentColor = accent),
        // focused* 用库默认:容器 onSurface 反白、图标 inverseOnSurface
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(descriptionRes),
            modifier = Modifier.size(IconButtonDefaults.MediumIconSize),
        )
    }
}
