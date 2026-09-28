package com.uniteduone.launcher

import java.security.MessageDigest

/**
 * 壁纸处理与播种 / 清理里**不碰 Android 类**的部分,单独一个文件,好在纯 JVM 单测里直接断言。
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

// (壁纸轮播的 nextWallpaper / rotationDelayMs 随「壁纸自动切换」一起删掉,2026-09-23 gtv spec R61。)

/**
 * **R61(2026-09-23 傍晚)**:M3 起铺进用户图库的 6 张内置染色图的文件名。这些是**我们播种的**(`Wallpapers`
 * 的 seedBuiltins 用固定前缀 `unitedu-` + 内置名复制过去,用户上传 / adb 推的图不会叫这几个名字),R61 把它们从 APK
 * 里删了,升级时从图库里清掉。按**完整文件名**认,不按前缀(用户自己也可能传一张 `unitedu-` 开头的图)。
 * R116 起内置壁纸直接从 assets 读、不再播种进图库,这份清单只剩升级清理这一个用处。
 */
internal val LEGACY_SEEDED_WALLPAPERS: Set<String> = setOf(
    "unitedu-00-neutral.jpg", "unitedu-01-gold.jpg", "unitedu-02-champagne.jpg",
    "unitedu-03-blue.jpg", "unitedu-04-purple.jpg", "unitedu-05-green.jpg",
)

/** 升级清理后 `wallpaperFile` 该是什么:指向被清掉的旧内置图 → 置空(回落图库第一张 / 纯深色);其余原样。 */
internal fun wallpaperFileAfterLegacyCleanup(current: String): String =
    if (current in LEGACY_SEEDED_WALLPAPERS) "" else current

// (R61 的播种 seedPlan / 「首次随机选一张当默认」pickDefaultSeed 随 R116 删掉:内置壁纸直接从 assets 读,
//  默认固定用清单第一张,见 BuiltinCatalog.kt 的 resolveWallpaperChoice。)

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
