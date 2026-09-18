package com.uniteduone.launcher

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 主题色的**唯一**解析路径(M5 spec §4:桌面与系统屏保同一条路)。选中预设的两种角色色同步给出;
 * followWallpaperColor 打开时在 IO 线程从壁纸主色取 accent,取到之前、或取不到时回落预设。
 * [refresh] 变了就重取(MainActivity 传 revision:重扫后「当前壁纸」可能换了文件)。
 * 从 MainActivity 的 setContent 里原样搬出(M5 Task 6),key 与回落规则一字未改。
 */
@Composable
fun rememberThemeColors(ctx: Context, s: Settings, refresh: Int = 0): ThemeColors {
    val preset = remember(s.themePresetId) { ThemePresets.byId(s.themePresetId).colors() }
    val fromWallpaper by produceState<ThemeColors?>(null, s.followWallpaperColor, s.wallpaperFile, refresh) {
        value = if (!s.followWallpaperColor) null
        else withContext(Dispatchers.IO) { wallpaperThemeColors(ctx, s.wallpaperFile) }
    }
    return if (s.followWallpaperColor) fromWallpaper ?: preset else preset
}

/**
 * followWallpaperColor 打开时,从当前壁纸主色推导界面强调色(经 LocalThemeColors 供给每个界面)。
 * **必须在 IO 线程调用**:取色会解一张缩略图并跑 Palette(实现见 [Wallpapers.paletteAccent])。
 *
 * accent 用取到的色,highlight 由 [highlightFrom] 混白 55% 推得 —— 与非金预设 highlight 同一手法。
 * 任何一步落空(没壁纸、解不出、Palette 抽不到色)返回 null,调用方回落到选中预设,绝不崩、绝不留黑。
 *
 * 取到的色只喂界面强调色,壁纸本身**不染色**(「主题化壁纸」2026-09-16 删掉,壁纸管线不再认识主题色)。
 * (M5 从 MainActivity.kt 的 private 顶层函数搬来:系统屏保也要用。)
 */
internal fun wallpaperThemeColors(ctx: Context, wallpaperFile: String): ThemeColors? =
    Wallpapers.resolveSource(ctx, wallpaperFile)
        ?.let { Wallpapers.paletteAccent(ctx, it) }
        ?.let { rgb ->
            // 壁纸主色可能很暗 / 很灰,先提亮到可读地板再当强调色(见 usableAccent);
            // 否则深色主题色压在 #0A0A0A 的设置页上,分组标题等文字直接消失。
            val accent = Color(usableAccent(rgb) or 0xFF000000.toInt())
            ThemeColors(accent, highlightFrom(accent))
        }
