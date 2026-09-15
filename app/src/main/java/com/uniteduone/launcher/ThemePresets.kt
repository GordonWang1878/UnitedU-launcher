package com.uniteduone.launcher

import androidx.compose.ui.graphics.Color

/**
 * 6 个主题色预设(门 2 Gordon 定「沉稳」一套,4 处强调低饱和;金保持 #C0A73A)。
 * 只在这里定义一次:设置页(Task C)用它画 swatch + 持久化 [Settings.themePresetId],
 * Task F 再让选中的颜色真正驱动光晕/时钟/行标题/齿轮四处。
 *
 * **颜色字面量只允许出现在这一个文件**:它就是预设的定义处。SettingsScreen 一律引 [ThemePreset.color],
 * 不自己写 Color(0x..)(见任务约束「Colors」)。
 */
data class ThemePreset(val id: String, val nameRes: Int, val color: Color)

object ThemePresets {
    /** 默认预设 id,与 [Settings] 的默认值一致。 */
    const val DEFAULT_ID = "gold"

    /** 顺序即 swatch 的从左到右排列顺序;换顺序会改变左右键的移动方向,别随手动。 */
    val all: List<ThemePreset> = listOf(
        ThemePreset("gold", R.string.preset_gold, Color(0xFFC0A73A)),
        ThemePreset("champagne", R.string.preset_champagne, Color(0xFFD9C7A0)),
        ThemePreset("blue", R.string.preset_blue, Color(0xFF6E8FB0)),
        ThemePreset("purple", R.string.preset_purple, Color(0xFF9280AA)),
        ThemePreset("graphite", R.string.preset_graphite, Color(0xFF9AA0A6)),
        ThemePreset("green", R.string.preset_green, Color(0xFF7FA07A)),
    )

    /** 找不到就回落到默认预设的下标(而不是 -1),保证 swatch 永远有一个选中项。 */
    fun indexOf(id: String): Int {
        val i = all.indexOfFirst { it.id == id }
        if (i >= 0) return i
        val d = all.indexOfFirst { it.id == DEFAULT_ID }
        return if (d >= 0) d else 0
    }
}
