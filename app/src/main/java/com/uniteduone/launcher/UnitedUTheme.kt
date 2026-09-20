package com.uniteduone.launcher

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/**
 * M8:tv-material 的主题壳。中性色阶 = 库的 dark 默认(surface #1C1B1F、surfaceVariant #49454F、
 * border #938F99、onSurface #E6E1E5……),primary = 当前预设 / 壁纸取色的 accent;
 * 字阶数值 = 库默认(15 档),字族全部换成 Theme.Sans(spec §0「字体」决策;
 * 具体字体见 Theme.kt 的 Sans 注释,2026-09-20 起为 Google Sans Flex)。
 * 只在 MainActivity 顶层用一次,包在 LocalThemeColors 外面。
 */
@Composable
fun UnitedUTheme(colors: ThemeColors, content: @Composable () -> Unit) {
    val base = Typography()
    // 曾经叫 dm()(DM Sans 的缩写);B7 换成 Google Sans Flex 后名不副实,改叫 withSans()——
    // 单纯套用 Theme.Sans(当前是哪个字体见 Theme.kt 的 Sans 注释),不再暗示具体字体。
    fun TextStyle.withSans(): TextStyle = copy(fontFamily = Theme.Sans)
    val typography = Typography(
        displayLarge = base.displayLarge.withSans(),
        displayMedium = base.displayMedium.withSans(),
        displaySmall = base.displaySmall.withSans(),
        headlineLarge = base.headlineLarge.withSans(),
        headlineMedium = base.headlineMedium.withSans(),
        headlineSmall = base.headlineSmall.withSans(),
        titleLarge = base.titleLarge.withSans(),
        titleMedium = base.titleMedium.withSans(),
        titleSmall = base.titleSmall.withSans(),
        bodyLarge = base.bodyLarge.withSans(),
        bodyMedium = base.bodyMedium.withSans(),
        bodySmall = base.bodySmall.withSans(),
        labelLarge = base.labelLarge.withSans(),
        labelMedium = base.labelMedium.withSans(),
        labelSmall = base.labelSmall.withSans(),
    )
    MaterialTheme(
        colorScheme = darkColorScheme(primary = colors.accent),
        typography = typography,
        content = content,
    )
}
