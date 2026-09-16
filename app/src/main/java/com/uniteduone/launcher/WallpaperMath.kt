package com.uniteduone.launcher

import java.security.MessageDigest

/**
 * 壁纸处理与轮播里**不碰 Android 类**的部分,单独一个文件,好在纯 JVM 单测里直接断言。
 * Android 侧(解码 / Canvas / 文件 / Compose)在 Wallpapers.kt。
 */

/**
 * 一次壁纸渲染的全部输入:文件 + 两个滑块,**不带任何主题色**。
 * 「主题化壁纸」(去色 → 染主题色)2026-09-16 整条删掉:用户要的是原图,壁纸不再染色。
 * 于是主题色(预设 swatch 或壁纸取色)只影响界面强调色,与这份 spec 无关——
 * 换预设、开关「跟随壁纸主色」、取色落地都不会让 spec 变化,不触发无谓的重处理
 * (`WallpaperMathTest.specKnowsIdentityAndIgnoresThemeFields` 钉住这一点)。
 */
data class WallpaperSpec(
    val file: String,
    val blur: Int,
    /** −50…+50:负压暗、正提亮、0 原片。 */
    val brightness: Int,
) {
    /** 参数全零:完全绕开管线,走原图 + F16 解码(零回归路径)。 */
    val isIdentity: Boolean get() = blur == 0 && brightness == 0
}

fun wallpaperSpecOf(s: Settings): WallpaperSpec = WallpaperSpec(
    file = s.wallpaperFile,
    blur = s.wallpaperBlur,
    brightness = s.wallpaperBrightness,
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
 * 亮度 → 一个 4×5 ColorMatrix(android.graphics.ColorMatrix 行主序):RGB 对角整体乘 (1 + brightness/100),
 * −50 → ×0.5 压暗,+50 → ×1.5 提亮(超过白的分量由 ColorMatrix 应用时截断到 255);alpha 不动。
 * 管线里的颜色运算只剩这一项——「去色 → 染主题色」那条分支随「主题化壁纸」一起删了(2026-09-16)。
 */
fun wallpaperColorMatrix(brightness: Int): FloatArray {
    val k = 1f + brightness.coerceIn(-50, 50) / 100f
    return floatArrayOf(
        k, 0f, 0f, 0f, 0f,
        0f, k, 0f, 0f, 0f,
        0f, 0f, k, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

/** 缓存文件名:源文件身份(路径 + mtime + 大小)+ 全部参数 + 算法版本号,任一变则键变。 */
fun wallpaperCacheKey(path: String, mtime: Long, size: Long, blur: Int, brightness: Int): String {
    // v2:第 7 段从「压暗 0–100」改成「亮度 −50…+50」,同一个数字含义相反,版本号必须变,否则旧缓存被错配。
    // v3:删掉 themed / accent 两段,键的形状变了,再升一版——旧的 v2 缓存文件从此永不命中,由 LRU 自然淘汰。
    val raw = "$path|$mtime|$size|$blur|$brightness|v3"
    val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
    return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
