package com.uniteduone.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * 右上角齿轮。用 Material 的 Settings 图标:手画版本被复审指出齿数不对
 * (画了 8 个齿,参考图是 6 个)、中心孔偏大、整体小 16%。
 */
@Composable
fun GearButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** accent 主题色。默认今日的 [Theme.ChampagneGold],未接线的调用点保持原样。 */
    accent: Color = Theme.ChampagneGold,
    onFocusChange: (Boolean) -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.18f else 1f, label = "gearScale")
    val angle by animateFloatAsState(if (focused) 30f else 0f, label = "gearAngle")

    Image(
        imageVector = Icons.Filled.Settings,
        contentDescription = stringResource(R.string.menu_settings_title),
        colorFilter = ColorFilter.tint(if (focused) accent else accent.copy(alpha = 0.55f)),
        modifier = modifier
            .size(30.dp)   // 同理:60px 框 × 0.79 填充 ≈ 参考的 47.5px
            .scale(scale)
            .rotate(angle)
            .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
            .clickable(onClick = onClick),
    )
}
