package com.uniteduone.launcher

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 行标题前的小图标。用真的 Material 图标,不再手画:
 * 复审逐项对比后指出手画版本的填充、朝向、笔画粗细都和参考图不同
 * (胶片画成了横向描边矩形、电视画成了开口盒子加天线、音符的旗是细线)。
 */
@Composable
fun RowIcon(name: String) {
    val icon = when (name.uppercase()) {
        "VIDEO" -> Icons.Filled.Theaters      // 竖向实心胶片,两侧方孔
        "LIVE" -> Icons.Outlined.Tv           // 带底座的显示器
        "MUSIC" -> Icons.Filled.MusicNote     // 实心旗的八分音符
        else -> Icons.Outlined.Tv
    }
    // 用 Image + ColorFilter 着色,免得为一个 Icon 引入整套 material3
    Image(
        imageVector = icon,
        contentDescription = name,
        colorFilter = ColorFilter.tint(Theme.RowTitle),
        // Material 的矢量图标只填满外框的 24/32,所以框要给到 24dp 才等于参考里的 36px 字形
        modifier = Modifier.size(24.dp),
    )
}
