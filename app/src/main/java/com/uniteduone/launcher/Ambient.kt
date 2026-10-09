package com.uniteduone.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.WorkerThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * **整屏页的氛围底**(R142,2026-09-30 Gordon:「提升视觉高级感」)。设置外壳、长按菜单、确认页、改名页、行图标、添加应用、
 * 三个选图页、扫码页、编辑桌面、所有应用、输入源、关于、引导原来一律是纯色 [GtvTokens.MenuBg](#0E0E0F)——干净,但像一块
 * 没有光的黑板,从首页进来的那一下,颜色与氛围断得很突兀。现在这些页面的底是**当前壁纸的一个影子**:缩到 96 × 54、
 * 连做三遍盒式模糊(只剩大块的色调)、降一点饱和、亮部压住,再以 18% 叠在 MenuBg 上。结果仍是深色底(胶囊、文字的对比不变),
 * 但带着首页那张图的冷暖——夏日数码门是左上一团冷蓝、右下一抹暮色——页面像是浮在自己的桌面上,而不是换到了另一个地方。
 *
 * - **防色带**:暗部的平滑渐变在 8 位面板上会一格一格地断开(OLED 近黑尤其明显)。输出图按像素加 ±0.5 级的随机抖动再取整,
 *   断层被打散成肉眼看不出的细颗粒;960 × 540 输出、绘制时放大 2×、双线性插值。
 * - **亮壁纸不把底抬灰**:每个通道先压到 [CAP] 以内再混,最亮处的底也只到约 #232323,最弱一档文字色在上面仍有 4:1 以上(见 [ambientPixels] 与单测)。
 * - **代价**:壁纸变了才重算一次(IO 线程,960 × 540 的纯 Kotlin 计算,A95L 上约几十毫秒);常驻 2 MB 位图;
 *   每一页多画一张放大的位图(与原来铺一整屏纯色的填充量相同)。没有壁纸 / 还没算好时就是原来的纯色 MenuBg。
 * - 纯计算在 [ambientPixels](JVM 单测 AmbientTest);解码与 Bitmap 在 [buildAmbient];页面用 [pageBackdrop]。
 */
object Ambient {
    /** 取样尺寸:壁纸先缩到这么小,只留大块色调。 */
    const val SRC_W = 96
    const val SRC_H = 54
    /** 输出尺寸(绘制时放大到整屏)。 */
    const val OUT_W = 960
    const val OUT_H = 540
    /** 壁纸在底色里占的比例。 */
    const val MIX = 0.18f
    /** 混之前每个通道的上限(0–255):亮壁纸(雪景、白天的天空)也不会把底抬成灰。 */
    const val CAP = 128
    /** 饱和度保留多少(1 = 原色)。 */
    const val SATURATION = 0.8f
    const val BLUR_RADIUS = 3
    const val BLUR_PASSES = 3
}

/** 当前壁纸的氛围底(R142);null = 还没算好或没有壁纸,页面画纯色 MenuBg。MainActivity 在根上提供。 */
val LocalAmbient = compositionLocalOf<ImageBitmap?> { null }

/**
 * 外层已经铺了一整屏氛围底(`OverlayStack` 的垫底)时为真:里面的页面不再各画一份(复审:原来每一层都画一遍整屏位图,
 * 长按菜单打开时是 5 次整屏填充)。换层交叉淡化时,新旧两页的内容在同一块不透明的底上淡入淡出,正是 R138 要的样子。
 */
val LocalBackdropProvided = compositionLocalOf { false }

/**
 * 整屏页的底(R142):先铺 MenuBg,再把氛围底放大铺满。代替原来各页的 `.background(GtvTokens.MenuBg)`;
 * 不透明,画法与原来一样只在绘制阶段,不改布局、不进焦点。
 */
@Composable
fun Modifier.pageBackdrop(always: Boolean = false): Modifier {
    if (!always && LocalBackdropProvided.current) return this
    val amb = LocalAmbient.current
    return drawBehind {
        // 氛围底本身不透明:有它就只画它(少一遍整屏填充),没有才铺纯色
        if (amb != null) {
            drawImage(
                amb,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Low,
            )
        } else drawRect(GtvTokens.MenuBg)
    }
}

/**
 * 纯计算(无 Android):[src] 是 [sw] × [sh] 的 ARGB 像素(取样后的壁纸),返回 [outW] × [outH] 的不透明 ARGB 像素。
 * 步骤:盒式模糊 → 降饱和 → 压亮部 → 与 [base] 按 [Ambient.MIX] 混 → ±0.5 级抖动取整。同一输入结果恒定(抖动按坐标取哈希)。
 */
internal fun ambientPixels(
    src: IntArray, sw: Int, sh: Int, outW: Int, outH: Int,
    base: Int = 0xFF0E0E0F.toInt(),
    mix: Float = Ambient.MIX, cap: Int = Ambient.CAP, saturation: Float = Ambient.SATURATION,
    radius: Int = Ambient.BLUR_RADIUS, passes: Int = Ambient.BLUR_PASSES,
): IntArray {
    require(src.size == sw * sh && sw > 0 && sh > 0)
    // 三个通道拆成浮点平面
    val ch = Array(3) { c -> FloatArray(sw * sh) { i -> ((src[i] shr (16 - 8 * c)) and 0xFF).toFloat() } }
    repeat(passes) { for (p in ch) boxBlur(p, sw, sh, radius) }
    // 降饱和 + 压亮部
    for (i in 0 until sw * sh) {
        val g = (ch[0][i] + ch[1][i] + ch[2][i]) / 3f
        for (c in 0..2) ch[c][i] = minOf(g + (ch[c][i] - g) * saturation, cap.toFloat())
    }
    val b = FloatArray(3) { c -> ((base shr (16 - 8 * c)) and 0xFF).toFloat() }
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
                val o = (b[c] + mix * (v - b[c]) + d).roundToInt().coerceIn(0, 255)
                argb = argb or (o shl (16 - 8 * c))
            }
            out[y * outW + x] = argb
        }
    }
    return out
}

/** 每像素的抖动量,[-0.5, 0.5)。按坐标取整数哈希:没有规律的纹理,结果可复现。 */
internal fun ditherAt(x: Int, y: Int): Float {
    var h = x * 374761393 + y * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFF) / 65536f - 0.5f
}

/** 原地做一遍可分离的盒式模糊(边缘按边界值延伸)。编辑页的模糊底(EditBackdrop.kt)也用它。 */
internal fun boxBlur(p: FloatArray, w: Int, h: Int, r: Int) {
    if (r <= 0) return
    val tmp = FloatArray(p.size)
    val n = (2 * r + 1).toFloat()
    for (y in 0 until h) for (x in 0 until w) {
        var s = 0f
        for (k in -r..r) s += p[y * w + (x + k).coerceIn(0, w - 1)]
        tmp[y * w + x] = s / n
    }
    for (y in 0 until h) for (x in 0 until w) {
        var s = 0f
        for (k in -r..r) s += tmp[(y + k).coerceIn(0, h - 1) * w + x]
        p[y * w + x] = s / n
    }
}

/**
 * 按壁纸选中值([Settings.wallpaperFile])算氛围底(R142)。IO 线程;没有壁纸 / 解码失败 → null。
 * 解码时直接按 inSampleSize 缩小(4K 图 1/8 读),不把整张原图读进内存。
 */
@WorkerThread
fun buildAmbient(ctx: Context, wallpaperValue: String): ImageBitmap? {
    val file = Wallpapers.resolveSource(ctx, wallpaperValue) ?: return null
    // 同一张图(路径 + 修改时间 + 大小)不重算,**连包装对象也给同一个**:MainActivity 在重扫(装卸应用、关设置)时也会问一次,
    // 新包装的 ImageBitmap 不相等,会让读它的整棵树白白重组一次(复审发现)。
    val key = "${file.path}|${file.lastModified()}|${file.length()}"
    lastAmbient?.let { (k, img) -> if (k == key) return img }
    return computeAmbient(file)?.asImageBitmap()?.also { lastAmbient = key to it }
}

/** 上一次算出的氛围底:冷启动之外的重建(切语言 recreate)拿它当初值,不先画一两帧纯色再跳成氛围底。 */
fun cachedAmbient(): ImageBitmap? = lastAmbient?.second

/** 上一次算出的氛围底与它的来源(见 [buildAmbient])。IO 线程写;被取消的计算可能晚写一次,内容相同,无害。 */
@Volatile private var lastAmbient: Pair<String, ImageBitmap>? = null

@WorkerThread
private fun computeAmbient(file: java.io.File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decodeImagePath(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= Ambient.SRC_W * 4 && bounds.outHeight / (sample * 2) >= Ambient.SRC_H * 4) sample *= 2
    val decoded = decodeImagePath(file.path, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }) ?: return null
    val small = Bitmap.createScaledBitmap(decoded, Ambient.SRC_W, Ambient.SRC_H, true)
    if (small !== decoded) decoded.recycle()
    val px = IntArray(Ambient.SRC_W * Ambient.SRC_H)
    small.getPixels(px, 0, Ambient.SRC_W, 0, 0, Ambient.SRC_W, Ambient.SRC_H)
    small.recycle()
    val out = ambientPixels(px, Ambient.SRC_W, Ambient.SRC_H, Ambient.OUT_W, Ambient.OUT_H)
    return Bitmap.createBitmap(out, Ambient.OUT_W, Ambient.OUT_H, Bitmap.Config.ARGB_8888)
}
