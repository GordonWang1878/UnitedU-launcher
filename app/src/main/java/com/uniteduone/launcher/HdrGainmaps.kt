package com.uniteduone.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import java.util.function.Consumer

/**
 * **Ruling R123(2026-09-28)**:增益图(gain map,Ultra HDR 的「HDR 增强层」)的 Android 侧小工具。
 * `android.graphics.Gainmap` 是 API 34 才有的类;低于 34 的设备(minSdk 28)这里全部返回 false / null,
 * 调用方照旧走 SDR 路径。纯几何算术在 HdrImageMath.kt。
 *
 * 背景(AOSP android14-release 源码):
 * - `BitmapFactory` 解 Ultra HDR JPEG 时**不论底图格式**(ARGB_8888 / RGBA_F16)都把增益图挂在位图上,
 *   按同一个 inSampleSize / 缩放比例一起缩;单通道增益图解成 `ALPHA_8`。底图里存的永远是 SDR 画面。
 * - 显示时 HWUI 用增益图着色器现算 HDR:提亮权重 W 取决于**此刻窗口的 HDR/SDR 比例**,比例为 1 时直接画底图。
 * - `Bitmap.compress(JPEG)` 遇到带增益图的位图写 Ultra HDR(XMP `hdrgm` + MPF 第二帧),A8 增益图写成灰度。
 */
internal object HdrGainmaps {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun has(b: Bitmap): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && b.hasGainmap()

    /** 日志用:「1920x1080 ARGB_8888 增益图 1920x1080 ALPHA_8 ratioMax 4.94」/「… 无增益图」。 */
    fun describe(b: Bitmap): String {
        val base = "${b.width}x${b.height} ${b.config}"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return "$base 无增益图(API < 34)"
        val g = b.gainmap ?: return "$base 无增益图"
        val c = g.gainmapContents
        return "$base 增益图 ${c.width}x${c.height} ${c.config} ratioMax ${"%.2f".format(java.util.Locale.ROOT, g.ratioMax[0])}"
    }

    /**
     * 取下 [b] 的增益图并从 [b] 上摘掉。**为什么要摘**:软件 Canvas 画一张带增益图的位图时走的是增益图着色器,
     * 提亮权重按 `getTargetHdrSdrRatio(目标色彩空间)` 算——目标是普通 sRGB 位图时读的是渲染线程**此刻**的
     * `CanvasContext`(一个进程级静态指针,不分线程)。HDR 电视上 IO 线程处理壁纸的同时渲染线程在画帧,
     * 高光就会按那一帧的比例被烤进 8 位底图、截在 255(随机发生)。摘掉之后底图是纯 SDR 画法,增益图另走一路。
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun detach(b: Bitmap): Gainmap? {
        if (!b.hasGainmap()) return null
        val g = b.gainmap
        b.gainmap = null
        return g
    }

    /**
     * 新增益图 = [from] 的全部参数 + 新内容 [contents]。API 35 起有公开的复制构造(连同 35+ 新增的参数一起复制);
     * API 34 上它是 `@hide`,逐项复制 ratioMin / ratioMax / gamma / epsilonSdr / epsilonHdr /
     * displayRatioForFullHdr / minDisplayRatioForHdrTransition(34 的全部公开参数)。
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun copyWithContents(from: Gainmap, contents: Bitmap): Gainmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) return Gainmap(from, contents)
        return Gainmap(contents).apply {
            from.ratioMin.let { setRatioMin(it[0], it[1], it[2]) }
            from.ratioMax.let { setRatioMax(it[0], it[1], it[2]) }
            from.gamma.let { setGamma(it[0], it[1], it[2]) }
            from.epsilonSdr.let { setEpsilonSdr(it[0], it[1], it[2]) }
            from.epsilonHdr.let { setEpsilonHdr(it[0], it[1], it[2]) }
            displayRatioForFullHdr = from.displayRatioForFullHdr
            minDisplayRatioForHdrTransition = from.minDisplayRatioForHdrTransition
        }
    }

    /**
     * 把增益图内容 [src] 里的浮点区域 [r] 画满一张 [w]×[h] 的新图(双线性,Src 模式不混合),格式与源相同
     * (`ALPHA_8` 单通道 / `ARGB_8888` 三通道)。用矩阵而不是整数 Rect:1/4 分辨率的增益图裁剪区一般不在整像素上。
     * 新图与源同一个色彩空间,画的时候不做色彩转换——增益图的数值是编码后的对数增益,不是颜色。
     */
    fun drawRegion(src: Bitmap, r: FloatRect, w: Int, h: Int): Bitmap {
        val out = if (src.config == Bitmap.Config.ALPHA_8) {
            Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, true, src.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB))
        }
        val m = Matrix().apply {
            setRectToRect(RectF(r.left, r.top, r.right, r.bottom), RectF(0f, 0f, w.toFloat(), h.toFloat()), Matrix.ScaleToFit.FILL)
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        Canvas(out).drawBitmap(src, m, paint)
        return out
    }
}

/**
 * **Ruling R124**:当前窗口所在显示器的 HDR/SDR 比例档位([hdrRatioBucket]),比例变了(跨档)就变。
 * [active] = false(位图没有增益图 / API < 34 / 显示器不报比例)恒为 0、不注册监听。
 * 用处:首页壁纸的 R110 缓存图层以它为 key 重建,见 `LayeredWallpaper`。
 */
@Composable
internal fun rememberHdrRatioBucket(active: Boolean): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return 0
    val view = LocalView.current
    var bucket by remember { mutableIntStateOf(0) }
    DisposableEffect(view, active) {
        val display: Display? = view.display
        if (!active || display == null || !display.isHdrSdrRatioAvailable) {
            bucket = 0
            return@DisposableEffect onDispose { }
        }
        val read = { d: Display -> bucket = hdrRatioBucket(if (d.isHdrSdrRatioAvailable) d.hdrSdrRatio else 1f) }
        read(display)
        val listener = Consumer<Display> { read(it) }
        display.registerHdrSdrRatioChangedListener(view.context.mainExecutor, listener)
        onDispose { display.unregisterHdrSdrRatioChangedListener(listener) }
    }
    return bucket
}
