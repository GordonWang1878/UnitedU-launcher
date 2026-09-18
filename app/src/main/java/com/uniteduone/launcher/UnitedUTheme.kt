package com.uniteduone.launcher

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/**
 * M8:tv-material 的主题壳。中性色阶 = 库的 dark 默认(surface #1C1B1F、surfaceVariant #49454F、
 * border #938F99、onSurface #E6E1E5……),primary = 当前预设 / 壁纸取色的 accent;
 * 字阶数值 = 库默认(15 档),字族全部换成 DM Sans(spec §0「字体」决策)。
 * 只在 MainActivity 顶层用一次,包在 LocalThemeColors 外面。
 */
@Composable
fun UnitedUTheme(colors: ThemeColors, content: @Composable () -> Unit) {
    val base = Typography()
    fun TextStyle.dm(): TextStyle = copy(fontFamily = Theme.Sans)
    val typography = Typography(
        displayLarge = base.displayLarge.dm(),
        displayMedium = base.displayMedium.dm(),
        displaySmall = base.displaySmall.dm(),
        headlineLarge = base.headlineLarge.dm(),
        headlineMedium = base.headlineMedium.dm(),
        headlineSmall = base.headlineSmall.dm(),
        titleLarge = base.titleLarge.dm(),
        titleMedium = base.titleMedium.dm(),
        titleSmall = base.titleSmall.dm(),
        bodyLarge = base.bodyLarge.dm(),
        bodyMedium = base.bodyMedium.dm(),
        bodySmall = base.bodySmall.dm(),
        labelLarge = base.labelLarge.dm(),
        labelMedium = base.labelMedium.dm(),
        labelSmall = base.labelSmall.dm(),
    )
    MaterialTheme(
        colorScheme = darkColorScheme(primary = colors.accent),
        typography = typography,
        content = content,
    )
}
