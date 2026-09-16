package com.uniteduone.launcher

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * 6 个主题色预设(门 2 Gordon 定「沉稳」一套,强调色低饱和;金保持 #C0A73A)。
 * 只在这里定义一次:设置页用它画 swatch + 持久化 [Settings.themePresetId];
 * 选中的颜色经 [LocalThemeColors] 驱动**每个界面**的强调色(2026-09-16 全面接线,原先只接首页四处)。
 *
 * 每个预设带**两种角色色**:
 * - [color](= accent):齿轮、设置页分组标题——原 Theme.ChampagneGold 的位置。就是 swatch 上那个色点。
 * - [highlight]:时钟(叠 0.55 alpha)、光晕、行标题,以及各界面的标题 / 焦点条 / 选中段 / 滑块填充 /
 *   光标 / 选择器标签——原 Theme.Champagne 的位置。黑底上要清透可读,所以是浅色。
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
 * 当前生效的两种角色色。MainActivity 解析一次(预设,或「跟随壁纸主色」时的壁纸取色),
 * 经 [LocalThemeColors] 提供给整棵树;界面代码一律读 `LocalThemeColors.current`,不再逐层传参。
 */
data class ThemeColors(val accent: Color, val highlight: Color)

/**
 * 全局主题色的 CompositionLocal——**主题色只此一条线**:
 * [MainActivity] 在 setContent 顶层 `CompositionLocalProvider(LocalThemeColors provides themeColors)` 提供一次,
 * 之后所有界面(首页、设置页、编辑页、齿轮 / 长按菜单、修改标题对话框、图片选择器、默认桌面卡、导入页)
 * 的强调色都从这里读:`accent` 接原 `Theme.ChampagneGold` 的位置,`highlight` 接原 `Theme.Champagne` 的位置,
 * `.copy(alpha = …)` 一类的调制原样保留。
 *
 * 默认值 = 金预设。它的 accent / highlight 正是 `Theme.ChampagneGold` #C0A73A / `Theme.Champagne` #FFF5DC 的原值,
 * 所以金预设(也是 Settings 的默认)下每个界面逐位复现接线前的观感——这是零回归的依据。
 * 选 static 版:主题色只在换预设 / 换壁纸取色时变,变一次整棵树重组一次可以接受;换来每处读取零订阅开销。
 */
val LocalThemeColors = staticCompositionLocalOf { ThemePresets.byId(ThemePresets.DEFAULT_ID).colors() }

/** 预设的两种角色色打包成 [ThemeColors]。 */
fun ThemePreset.colors(): ThemeColors = ThemeColors(color, highlight)

/**
 * 由 accent 推导 highlight:混向白 55%。这是 5 个非金预设 highlight 的来历,也是
 * followWallpaperColor 打开时从壁纸主色现推 highlight 的唯一算法 —— 两条路同一手法,
 * 保证跟壁纸和选预设时的观感一致。
 */
fun highlightFrom(accent: Color): Color = lerp(accent, Color.White, 0.55f)

/**
 * 壁纸取色当强调色前先「提亮到可读」。内置壁纸都是深色沉稳底,`paletteAccent` 取出的主色往往很暗、很灰
 * (M3 实测蓝底 ≈ (14,22,29));主题色现在流到每个界面,深色文字压在 #0A0A0A 上直接看不见(分组标题成一片黑)。
 * 做法:HSL 下把**亮度**抬到 ≥ 0.62 的地板,色相始终不动;**饱和度**只在原本就有色相时(s ≥ 0.08)抬到 ≥ 0.45——
 * 纯灰壁纸保持中性浅灰,不凭噪声硬造出一个红色。纯函数,JVM 单测在 [ThemeColorTest]。
 */
fun usableAccent(rgb: Int): Int {
    val r = ((rgb shr 16) and 0xFF) / 255f
    val g = ((rgb shr 8) and 0xFF) / 255f
    val b = (rgb and 0xFF) / 255f
    val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
    val l = (mx + mn) / 2f
    val s = if (d == 0f) 0f else d / (1f - kotlin.math.abs(2f * l - 1f))
    var h = when {
        d == 0f -> 0f
        mx == r -> 60f * ((((g - b) / d) % 6f + 6f) % 6f)
        mx == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    if (h < 0f) h += 360f
    val nl = l.coerceAtLeast(0.62f)
    val ns = if (s < 0.08f) s else s.coerceAtLeast(0.45f)
    val c = (1f - kotlin.math.abs(2f * nl - 1f)) * ns
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = nl - c / 2f
    val (r2, g2, b2) = when {
        h < 60f -> Triple(c, x, 0f); h < 120f -> Triple(x, c, 0f); h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c); h < 300f -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
    }
    fun ch(v: Float) = Math.round((v + m).coerceIn(0f, 1f) * 255f)
    return (ch(r2) shl 16) or (ch(g2) shl 8) or ch(b2)
}

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
