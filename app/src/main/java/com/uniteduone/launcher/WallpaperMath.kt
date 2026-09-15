package com.uniteduone.launcher

import java.security.MessageDigest

/**
 * 壁纸处理与轮播里**不碰 Android 类**的部分,单独一个文件,好在纯 JVM 单测里直接断言。
 * Android 侧(解码 / Canvas / 文件 / Compose)在 Wallpapers.kt。
 */

/**
 * 一次壁纸渲染的全部输入。[accentRgb] 只在 [themed] 时有意义,不主题化时**恒为 0**
 * (见 [wallpaperSpecOf]):否则换个预设就会让 spec 变化、触发一次无谓的重处理。
 */
data class WallpaperSpec(
    val file: String,
    val themed: Boolean,
    val accentRgb: Int,
    val blur: Int,
    val dim: Int,
) {
    /** 参数全零:完全绕开管线,走原图 + F16 解码(零回归路径)。 */
    val isIdentity: Boolean get() = !themed && blur == 0 && dim == 0
}

fun wallpaperSpecOf(s: Settings, accentRgb: Int): WallpaperSpec = WallpaperSpec(
    file = s.wallpaperFile,
    themed = s.wallpaperThemed,
    accentRgb = if (s.wallpaperThemed) accentRgb and 0xFFFFFF else 0,
    blur = s.wallpaperBlur,
    dim = s.wallpaperDim,
)

/** library 里当前壁纸的下一张(按名排序、循环)。当前不在列表 → 第一张;空表 → null;单张 → 它自己。 */
fun nextWallpaper(names: List<String>, current: String): String? {
    if (names.isEmpty()) return null
    val sorted = names.sorted()
    val i = sorted.indexOf(current)
    return if (i < 0) sorted[0] else sorted[(i + 1) % sorted.size]
}

/** 距下一次轮换还要等多久:已过期 → 0;rotatedAt 在未来(时钟回拨)→ 最多等一个间隔。 */
fun rotationDelayMs(rotatedAt: Long, intervalMs: Long, nowMs: Long): Long =
    (rotatedAt + intervalMs - nowMs).coerceIn(0L, intervalMs)

/**
 * 模糊档位 → 缩小到的工作宽度。模糊 = 缩小再放大(spec §3.2),这里定「缩到多宽」:
 * 0 → 1920(不缩),10 → 768,50 → 226,100 → 120;11 档单调递减、无重复。
 */
fun blurTargetWidth(blur: Int, fullWidth: Int = 1920): Int {
    val b = blur.coerceIn(0, 100) / 100f
    return Math.round(fullWidth / (1f + 15f * b))
}

/**
 * 去色 → 染主题色 → 压暗 三步合成一个 4×5 ColorMatrix(android.graphics.ColorMatrix 行主序)。
 * 去色用 Rec.709 亮度权重 (0.213, 0.715, 0.072),与 `ColorMatrix.setSaturation(0)` 同值;
 * 染色 = 各通道乘主题色分量(黑→主题色的渐变映射,与金雾底同一手法);压暗 = 整体乘 (1 - dim)。
 * 不主题化时只剩压暗(对角阵)。
 */
fun wallpaperColorMatrix(themed: Boolean, accentRgb: Int, dim: Int): FloatArray {
    val k = 1f - dim.coerceIn(0, 100) / 100f
    if (!themed) {
        return floatArrayOf(
            k, 0f, 0f, 0f, 0f,
            0f, k, 0f, 0f, 0f,
            0f, 0f, k, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }
    val ar = ((accentRgb shr 16) and 0xFF) / 255f
    val ag = ((accentRgb shr 8) and 0xFF) / 255f
    val ab = (accentRgb and 0xFF) / 255f
    val lr = 0.213f
    val lg = 0.715f
    val lb = 0.072f
    return floatArrayOf(
        k * ar * lr, k * ar * lg, k * ar * lb, 0f, 0f,
        k * ag * lr, k * ag * lg, k * ag * lb, 0f, 0f,
        k * ab * lr, k * ab * lg, k * ab * lb, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

/** 缓存文件名:源文件身份(路径 + mtime + 大小)+ 全部参数 + 算法版本号,任一变则键变。 */
fun wallpaperCacheKey(
    path: String, mtime: Long, size: Long,
    themed: Boolean, accentRgb: Int, blur: Int, dim: Int,
): String {
    val raw = "$path|$mtime|$size|$themed|${Integer.toHexString(accentRgb)}|$blur|$dim|v1"
    val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
    return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
