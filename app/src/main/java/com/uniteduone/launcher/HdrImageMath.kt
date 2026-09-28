package com.uniteduone.launcher

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * **Ruling R122 / R123(2026-09-28)**:壁纸 / 屏保显示用图的尺寸与增益图(gain map)几何里**不碰 Android 类**的部分,
 * 单独一个文件,好在纯 JVM 单测里断言。Android 侧(解码、Canvas、`android.graphics.Gainmap`)在 Wallpapers.kt /
 * Screensaver.kt / HdrGainmaps.kt。
 */

/** 整数像素矩形(左上 + 宽高)。不用 `android.graphics.Rect`:它在 JVM 单测里是桩。 */
data class PixelRect(val x: Int, val y: Int, val w: Int, val h: Int)

/** 浮点矩形(左、上、右、下),增益图坐标系里的裁剪区——增益图可以比底图小,换算后一般不是整数。 */
data class FloatRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * R122:显示用图长边的上限。4K 界面(3840×2160)用满;更大的窗口(8K 界面)按比例缩到长边 3840——
 * 32 位 MTK 电视上一张 8K 的 ARGB_8888 就是 133 MB,屏保同时两张。
 */
const val DECODE_LONG_EDGE_CAP = 3840

/** 读不到窗口尺寸时的回落 = R122 之前写死的值。 */
const val FALLBACK_DECODE_W = 1920
const val FALLBACK_DECODE_H = 1080

/**
 * **R122**:壁纸处理输出 / 屏保解码 / 图库全屏预览的目标像素尺寸 = **当前窗口像素尺寸**(调用方传
 * `resources.displayMetrics`),长边封顶 [DECODE_LONG_EDGE_CAP](等比缩)。取代写死的 1920×1080 / 长边 1920:
 * 1080p 界面的电视(A95L:面板 4K,界面 `wm size` 1920×1080)结果与原来逐像素相同;4K 界面的电视用满 4K。
 * 读不到(≤ 0)→ [FALLBACK_DECODE_W]×[FALLBACK_DECODE_H]。
 */
fun screenDecodeSize(widthPx: Int, heightPx: Int): Pair<Int, Int> {
    if (widthPx <= 0 || heightPx <= 0) return FALLBACK_DECODE_W to FALLBACK_DECODE_H
    val long = max(widthPx, heightPx)
    if (long <= DECODE_LONG_EDGE_CAP) return widthPx to heightPx
    val k = DECODE_LONG_EDGE_CAP.toFloat() / long
    return max(1, (widthPx * k).roundToInt()) to max(1, (heightPx * k).roundToInt())
}

/**
 * 中心裁剪(= `ContentScale.Crop`):在 [srcW]×[srcH] 里取与 [outW]×[outH] 同比例的最大居中区域。
 * 算术与 R122 之前 `Wallpapers.cropScale` 里的逐位相同(截断取整、夹到 [1, 源尺寸]),底图结果不变。
 */
fun centerCropRect(srcW: Int, srcH: Int, outW: Int, outH: Int): PixelRect {
    val scale = maxOf(outW.toFloat() / srcW, outH.toFloat() / srcH)
    val sw = (outW / scale).toInt().coerceIn(1, srcW)
    val sh = (outH / scale).toInt().coerceIn(1, srcH)
    return PixelRect((srcW - sw) / 2, (srcH - sh) / 2, sw, sh)
}

/**
 * **R123**:底图上的裁剪区 [crop] → 增益图坐标。增益图覆盖整张图、分辨率可以与底图不同(Ultra HDR 常见 1/4;
 * 我们的内置图是全分辨率),按两者尺寸比例换算,**不取整**——Android 14 自带的 `Bitmap.createBitmap(…, matrix)`
 * 换算增益图时截断取整,1/4 分辨率的增益图会错开最多一个增益图像素;我们用矩阵画,亚像素对齐。
 */
fun gainmapRectFor(crop: PixelRect, baseW: Int, baseH: Int, gainW: Int, gainH: Int): FloatRect {
    val sx = gainW.toFloat() / baseW
    val sy = gainH.toFloat() / baseH
    return FloatRect(crop.x * sx, crop.y * sy, (crop.x + crop.w) * sx, (crop.y + crop.h) * sy)
}

/**
 * **R123**:输出增益图的尺寸——保持源里「增益图 / 底图」的比例:全分辨率的增益图输出与输出底图同大,
 * 1/4 的输出 1/4。至少 1 像素、至多与输出底图同大(比底图还大的增益图没有意义,着色器按底图坐标采样)。
 */
fun gainmapSizeFor(outW: Int, outH: Int, baseW: Int, baseH: Int, gainW: Int, gainH: Int): Pair<Int, Int> {
    val w = (outW * gainW.toFloat() / baseW).roundToInt().coerceIn(1, outW)
    val h = (outH * gainH.toFloat() / baseH).roundToInt().coerceIn(1, outH)
    return w to h
}

/**
 * **R123**:模糊的「缩到多宽」换到增益图上:底图缩到 [baseTargetW](输出宽 [outW] 的一个比例),
 * 增益图(输出宽 [gainOutW])缩到同一个比例——两者模糊掉的是画面上同样大小的细节。至少 1。
 */
fun gainmapBlurWidth(baseTargetW: Int, outW: Int, gainOutW: Int): Int =
    max(1, (baseTargetW.toFloat() * gainOutW / outW).roundToInt())

/**
 * **R124**:显示器 HDR/SDR 比例 → 一个整数档位(每档 1/8 档光圈 ≈ 9%),给首页壁纸缓存图层当重建的 key。
 * ≤ 1.01(没有 HDR 余量)或读不到(NaN)→ 0。档位变了才重建图层:HWUI 的离屏图层只在创建时定下色彩空间、
 * 只在内容变了时重画,比例变了不会自己跟上(推导见 HomeBackdrop.kt 的 R124 一节)。
 */
fun hdrRatioBucket(ratio: Float): Int {
    if (!(ratio > 1.01f)) return 0
    return max(1, (ln(ratio.toDouble()) / ln(2.0) * 8).roundToInt())
}
