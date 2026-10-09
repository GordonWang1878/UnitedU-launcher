package com.uniteduone.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.WorkerThread
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * **编辑桌面的模糊底**(R165 §2.5,效果图 `.bg{filter:blur(36px) saturate(1.2)}` + `.dim{rgba(10,10,12,.72)}`):
 * 当前壁纸缩到 1/8(1920 → 240)、三遍盒式模糊(半径 8 ≈ 效果图 36 px 的高斯,按 1/8 换算)、饱和度 × 1.2、
 * 再按 72% 混进深色,输出 480 × 270(±0.5 级抖动防色带),绘制时双线性放大铺满。**算一次、缓存成位图**,之后每帧只画它——
 * A95L 上不实时算模糊(不用 RenderEffect)。没有壁纸 / 解不出来 → null,页面画纯 MenuBg。
 * 与 [Ambient](整屏页的氛围底,18%、压亮部)是两种东西:那个只要一点冷暖,这个要看得出是哪张壁纸。
 */
internal object EditBackdrop {
    const val DOWNSCALE = 8
    const val SRC_W = 240
    const val SRC_H = 135
    const val OUT_W = 480
    const val OUT_H = 270
    const val BLUR_RADIUS = 8
    const val BLUR_PASSES = 3
    const val SATURATION = 1.2f
    const val DIM = 0.72f
    const val DIM_COLOR = 0xFF0A0A0C.toInt()
}

/**
 * 纯计算:[src] 是 [sw] × [sh] 的 ARGB(缩小后的壁纸),返回 [outW] × [outH] 的不透明 ARGB。
 * 步骤:盒式模糊 [passes] 遍 → 饱和度 × [saturation] → `v × (1 − dim) + dimColor × dim` → ±0.5 级抖动取整。
 * 同一输入结果恒定(抖动按坐标取哈希,[ditherAt])。尺寸对不上 → IllegalArgumentException。
 */
internal fun editBackdropPixels(
    src: IntArray, sw: Int, sh: Int, outW: Int, outH: Int,
    dim: Float = EditBackdrop.DIM, dimColor: Int = EditBackdrop.DIM_COLOR,
    saturation: Float = EditBackdrop.SATURATION,
    radius: Int = EditBackdrop.BLUR_RADIUS, passes: Int = EditBackdrop.BLUR_PASSES,
): IntArray {
    require(sw > 0 && sh > 0 && outW > 0 && outH > 0 && src.size == sw * sh)
    val ch = Array(3) { c -> FloatArray(sw * sh) { i -> ((src[i] shr (16 - 8 * c)) and 0xFF).toFloat() } }
    repeat(passes) { for (p in ch) boxBlur(p, sw, sh, radius) }
    for (i in 0 until sw * sh) {
        val g = (ch[0][i] + ch[1][i] + ch[2][i]) / 3f
        for (c in 0..2) ch[c][i] = (g + (ch[c][i] - g) * saturation).coerceIn(0f, 255f)
    }
    val add = FloatArray(3) { c -> ((dimColor shr (16 - 8 * c)) and 0xFF).toFloat() * dim }
    val keep = 1f - dim
    val out = IntArray(outW * outH)
    for (y in 0 until outH) {
        val fy = ((y + 0.5f) * sh / outH - 0.5f).coerceIn(0f, (sh - 1).toFloat())
        val y0 = fy.toInt(); val y1 = minOf(y0 + 1, sh - 1); val ty = fy - y0
        for (x in 0 until outW) {
            val fx = ((x + 0.5f) * sw / outW - 0.5f).coerceIn(0f, (sw - 1).toFloat())
            val x0 = fx.toInt(); val x1 = minOf(x0 + 1, sw - 1); val tx = fx - x0
            val d = ditherAt(x, y)
            var argb = 0xFF shl 24
            for (c in 0..2) {
                val p = ch[c]
                val top = p[y0 * sw + x0] + (p[y0 * sw + x1] - p[y0 * sw + x0]) * tx
                val bot = p[y1 * sw + x0] + (p[y1 * sw + x1] - p[y1 * sw + x0]) * tx
                val v = top + (bot - top) * ty
                val o = (v * keep + add[c] + d).roundToInt().coerceIn(0, 255)
                argb = argb or (o shl (16 - 8 * c))
            }
            out[y * outW + x] = argb
        }
    }
    return out
}

/** 按壁纸选中值算模糊底。IO 线程;同一张图(路径 + 修改时间 + 大小)不重算、给同一个对象(不让读它的树白白重组)。 */
@WorkerThread
fun buildEditBackdrop(ctx: Context, wallpaperValue: String): ImageBitmap? {
    val file = Wallpapers.resolveSource(ctx, wallpaperValue) ?: return null
    val key = "${file.path}|${file.lastModified()}|${file.length()}"
    lastEditBackdrop?.let { (k, img) -> if (k == key) return img }
    return computeEditBackdrop(file)?.asImageBitmap()?.also { lastEditBackdrop = key to it }
}

/** 上一次算出的模糊底:再进编辑页时当初值,第一帧就有底,不先闪一下纯色。 */
fun cachedEditBackdrop(): ImageBitmap? = lastEditBackdrop?.second

@Volatile private var lastEditBackdrop: Pair<String, ImageBitmap>? = null

@WorkerThread
private fun computeEditBackdrop(file: java.io.File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decodeImagePath(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= EditBackdrop.SRC_W && bounds.outHeight / (sample * 2) >= EditBackdrop.SRC_H) sample *= 2
    val decoded = decodeImagePath(file.path, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }) ?: return null
    val small = Bitmap.createScaledBitmap(decoded, EditBackdrop.SRC_W, EditBackdrop.SRC_H, true)
    if (small !== decoded) decoded.recycle()
    val px = IntArray(EditBackdrop.SRC_W * EditBackdrop.SRC_H)
    small.getPixels(px, 0, EditBackdrop.SRC_W, 0, 0, EditBackdrop.SRC_W, EditBackdrop.SRC_H)
    small.recycle()
    val out = editBackdropPixels(px, EditBackdrop.SRC_W, EditBackdrop.SRC_H, EditBackdrop.OUT_W, EditBackdrop.OUT_H)
    return Bitmap.createBitmap(out, EditBackdrop.OUT_W, EditBackdrop.OUT_H, Bitmap.Config.ARGB_8888)
}

/** 编辑页的底:有模糊底就放大铺满(它本身不透明),没有就纯 MenuBg。只在绘制阶段,不改布局、不进焦点。 */
internal fun Modifier.editBackdrop(img: ImageBitmap?): Modifier = drawBehind {
    if (img != null) {
        drawImage(img, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
    } else drawRect(GtvTokens.MenuBg)
}
