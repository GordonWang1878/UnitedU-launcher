package com.uniteduone.launcher

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * 主题色预设。**Ruling R62(2026-09-23 傍晚,Gordon 定)**:只剩 5 个,都是黑底上**很浅、低饱和**的颜色——
 * 白 #F2F2F2、香槟 #E4D1AB、雾蓝 #B2C7DC、淡紫 #C5B6DF(**默认**)、鼠尾草 #BBD5B3。取代此前的 9 个
 * (Material 紫 #D0BCFF 默认 + 白 / 黑 + 金 / 香槟 / 蓝 / 紫 / 石墨 / 绿)。旧 id 读盘时映射到最接近的新预设
 * ([LEGACY_IDS]:material → purple、gold → champagne、graphite → white、black → white),不留死值。
 *
 * 只在这里定义一次:设置页用它画 swatch + 持久化 [Settings.themePresetId];
 * 选中的颜色经 [LocalThemeColors] 驱动**每个界面**的强调色(2026-09-16 全面接线,原先只接首页四处)。
 *
 * 每个预设带**两种角色色**:
 * - [color](= accent):首页时钟 / 顶栏图标 / 行图标、聚焦描边与柔光、菜单药丸的聚焦实填、设置页分组标题。
 *   就是 swatch 上那个色点。
 * - [highlight]:**首页不再落点**(时钟 / 光晕 / 行标题已改走 accent,M8);只驱动二级界面的标题 /
 *   焦点条 / 选中段 / 滑块填充 / 光标 / 选择器标签,以及搬运中的卡的描边(要与聚焦描边的 accent 区分)。
 *
 * R62 起 5 个 highlight 一律 = accent 经 [highlightFrom] 混白 55% 的结果(Compose 的 `lerp` 在 Oklab 里插值,
 * 数值是在 JVM 上实跑 `highlightFrom` 取的),写成显式 hex 以便单独微调(`ThemePresetsTest` 钉住「写的 = 算的」)。
 * ~~金预设的 highlight 沿用 [Theme.Champagne] #FFF5DC,金预设下二级界面逐位复现引入多预设之前的观感~~——
 * 金预设 R62 删掉,这份「逐位复现」的保证随之作废;[Theme.Champagne] / [Theme.ChampagneGold] 只剩标定记录。
 *
 * **颜色字面量只允许出现在这一个文件**:它就是预设的定义处。别处一律引这里的角色色,
 * 不自己写 Color(0x..)(见任务约束「Colors」)。
 */
data class ThemePreset(
    val id: String,
    val nameRes: Int,
    /** accent:与 swatch 色点同一个值。 */
    val color: Color,
    /** highlight:二级界面的标题 / 焦点条 / 选中段 / 滑块等(首页已不读,M8 改用 accent)。 */
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
 * 默认值 = 默认预设(R62 起淡紫 #C5B6DF / #E5DEF1),与 [Settings] 的默认 `themePresetId` 同一个 id——
 * 没被 Provider 包住的预览 / 测试与真实默认观感一致。~~默认 = 金预设,逐位复现接线前的观感~~(金预设 R62 删掉)。
 * 选 static 版:主题色只在换预设 / 换壁纸取色时变,变一次整棵树重组一次可以接受;换来每处读取零订阅开销。
 */
val LocalThemeColors = staticCompositionLocalOf { ThemePresets.byId(ThemePresets.DEFAULT_ID).colors() }

/** 预设的两种角色色打包成 [ThemeColors]。 */
fun ThemePreset.colors(): ThemeColors = ThemeColors(color, highlight)

/**
 * 由 accent 推导 highlight:混向白 55%。这是 5 个预设 highlight 的来历(R62),也是
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
    /** 默认预设 id,与 [Settings] 的默认值一致。R62:material → purple(淡紫);R152(2026-10-01 Gordon)起鼠尾草 green。 */
    const val DEFAULT_ID = "green"

    /**
     * R62 删掉的旧 id → 新 id。读盘([parseSettings])与 [indexOf] / [byId] 都走 [migrateId]:
     * 旧 settings.json 里的 id 下次写盘时就换成新的,swatch 选中项也不会落到「找不到 → 默认」上去。
     * 同名保留的 champagne / blue / purple / green / white 只是换了颜色,不在表里。
     */
    val LEGACY_IDS: Map<String, String> = mapOf(
        "material" to "purple",
        "gold" to "champagne",
        "graphite" to "white",
        "black" to "white",
    )

    /** 旧 id 换成新 id;不在迁移表里的原样返回(未知 id 由 [indexOf] 回落默认)。 */
    fun migrateId(id: String): String = LEGACY_IDS[id] ?: id

    /** 顺序即 swatch 的从左到右排列顺序;换顺序会改变左右键的移动方向,别随手动。 */
    val all: List<ThemePreset> = listOf(
        // R62:黑底上很浅、低饱和的五色,从中性到冷再到暖绿;highlight = highlightFrom(accent)(Oklab 混白 55%)。
        ThemePreset("white", R.string.preset_white, Color(0xFFF2F2F2), Color(0xFFF9F9F9)),
        ThemePreset("champagne", R.string.preset_champagne, Color(0xFFE4D1AB), Color(0xFFF3EAD9)),
        ThemePreset("blue", R.string.preset_blue, Color(0xFFB2C7DC), Color(0xFFDCE5EF)),
        ThemePreset("purple", R.string.preset_purple, Color(0xFFC5B6DF), Color(0xFFE5DEF1)),
        ThemePreset("green", R.string.preset_green, Color(0xFFBBD5B3), Color(0xFFE0ECDD)),
    )

    /** 按 id 取预设,找不到回落到默认(与 [indexOf] 同口径);旧 id 先迁移。 */
    fun byId(id: String): ThemePreset = all[indexOf(id)]

    /** 旧 id 先迁移([migrateId]);找不到就回落到默认预设的下标(而不是 -1),保证 swatch 永远有一个选中项。 */
    fun indexOf(id: String): Int {
        val i = all.indexOfFirst { it.id == migrateId(id) }
        if (i >= 0) return i
        val d = all.indexOfFirst { it.id == DEFAULT_ID }
        return if (d >= 0) d else 0
    }
}
