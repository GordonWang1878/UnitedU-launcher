package com.uniteduone.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * 6 个主题色预设(门 2 Gordon 定「沉稳」一套,4 处强调低饱和;金保持 #C0A73A)。
 * 只在这里定义一次:设置页(Task C)用它画 swatch + 持久化 [Settings.themePresetId],
 * Task F 让选中的颜色真正驱动光晕/时钟/行标题/齿轮四处。
 *
 * 每个预设带**两种角色色**:
 * - [color](= accent):齿轮。就是 swatch 上那个色点(SettingsScreen 引的也是它)。
 * - [highlight]:时钟(叠 0.55 alpha)、光晕、行标题。黑底上要清透可读,所以是浅色。
 *
 * 金的 highlight 就是今日的 [Theme.Champagne] #FFF5DC 原值 —— 保证金预设下齿轮/时钟/光晕
 * 逐位复现今日观感(行标题从纯白 #FFFFFF 变成 #FFF5DC,是唯一的可见改动,见任务报告)。
 * 其余 5 个 highlight 都是各自 accent 混白 ~55% 的浅色调(见 [highlightFrom]),这里写成
 * 显式 hex 以便单独微调。
 *
 * **颜色字面量只允许出现在这一个文件**:它就是预设的定义处。别处一律引这里的角色色,
 * 不自己写 Color(0x..)(见任务约束「Colors」)。
 */
data class ThemePreset(
    val id: String,
    val nameRes: Int,
    /** accent:齿轮。与 swatch 色点同一个值。 */
    val color: Color,
    /** highlight:时钟 / 光晕 / 行标题。 */
    val highlight: Color,
)

/**
 * 首页四处强调色的解析结果。[accent] 给齿轮;[highlight] 给时钟(叠 0.55 alpha)、光晕、行标题。
 * MainActivity 解析一次后往下穿。
 */
data class ThemeColors(val accent: Color, val highlight: Color)

/** 预设的两种角色色打包成 [ThemeColors]。 */
fun ThemePreset.colors(): ThemeColors = ThemeColors(color, highlight)

/**
 * 由 accent 推导 highlight:混向白 55%。这是 5 个非金预设 highlight 的来历,也是
 * followWallpaperColor 打开时从壁纸主色现推 highlight 的唯一算法 —— 两条路同一手法,
 * 保证跟壁纸和选预设时的观感一致。
 */
fun highlightFrom(accent: Color): Color = lerp(accent, Color.White, 0.55f)

object ThemePresets {
    /** 默认预设 id,与 [Settings] 的默认值一致。 */
    const val DEFAULT_ID = "gold"

    /** 顺序即 swatch 的从左到右排列顺序;换顺序会改变左右键的移动方向,别随手动。 */
    val all: List<ThemePreset> = listOf(
        // 金:highlight = 今日 Theme.Champagne #FFF5DC 原值,金预设逐位复现今日观感。
        ThemePreset("gold", R.string.preset_gold, Color(0xFFC0A73A), Color(0xFFFFF5DC)),
        // 以下 5 个 highlight = accent 混白 55%(highlightFrom 的结果),写成显式 hex 以便微调。
        ThemePreset("champagne", R.string.preset_champagne, Color(0xFFD9C7A0), Color(0xFFEEE6D4)),
        ThemePreset("blue", R.string.preset_blue, Color(0xFF6E8FB0), Color(0xFFBECDDB)),
        ThemePreset("purple", R.string.preset_purple, Color(0xFF9280AA), Color(0xFFCEC6D9)),
        ThemePreset("graphite", R.string.preset_graphite, Color(0xFF9AA0A6), Color(0xFFD2D4D7)),
        ThemePreset("green", R.string.preset_green, Color(0xFF7FA07A), Color(0xFFC5D4C3)),
    )

    /** 按 id 取预设,找不到回落到默认(与 [indexOf] 同口径)。 */
    fun byId(id: String): ThemePreset = all[indexOf(id)]

    /** 找不到就回落到默认预设的下标(而不是 -1),保证 swatch 永远有一个选中项。 */
    fun indexOf(id: String): Int {
        val i = all.indexOfFirst { it.id == id }
        if (i >= 0) return i
        val d = all.indexOfFirst { it.id == DEFAULT_ID }
        return if (d >= 0) d else 0
    }
}
