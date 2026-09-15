package com.uniteduone.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "UnitedU"
private val WALLPAPER_IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")

/** APK 内置 6 张(assets/wallpapers/<name>.jpg);铺进 library 时加前缀。00 中性底是默认。 */
private val BUILTIN_WALLPAPERS = listOf("00-neutral", "01-gold", "02-champagne", "03-blue", "04-purple", "05-green")
private const val BUILTIN_PREFIX = "unitedu-"
const val DEFAULT_WALLPAPER_ASSET = "wallpapers/00-neutral.jpg"
/** 铺过一次就留个标记:M6 上传页删掉内置图后不复活(恢复默认是 M7 的事)。 */
private const val SEED_MARKER = ".seeded"

/**
 * 壁纸的文件侧:当前壁纸不再是复制出来的 wallpaper.jpg,而是 settings.json 里的一个文件名,
 * 指向 library/wallpapers/。选图 / 轮播只改字段,不复制、不 recreate。
 */
object Wallpapers {

    fun libraryImages(ctx: Context): List<File> =
        Paths.wallpaperLibrary(ctx).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in WALLPAPER_IMAGE_EXTS }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** 当前壁纸源文件:设置指定的 → library 按名排序第一张 → null(调用方回落 APK 内置)。 */
    fun resolveSource(ctx: Context, fileName: String): File? {
        if (fileName.isNotEmpty()) {
            val f = File(Paths.wallpaperLibrary(ctx), fileName)
            if (f.isFile) return f
        }
        return libraryImages(ctx).firstOrNull()
    }

    /** 一次性准备:迁移旧根目录壁纸 + 铺入内置 6 张。IO 线程;外置没挂整段跳过。 */
    fun prepare(ctx: Context) {
        if (Paths.baseOrNull(ctx) == null) return
        migrateLegacy(ctx)
        seedBuiltins(ctx)
    }

    /**
     * M1/M2 的壁纸是根目录 files/wallpaper.jpg|png(选择器复制过去的)。搬进 library,
     * 若设置里还没指定壁纸就指向它——升级后用户看到的仍是升级前那张。
     * 此后 adb 后门 = push 进 library/wallpapers/ 再在选择器里选(与文档一致)。
     */
    private fun migrateLegacy(ctx: Context) {
        val old = listOf(Paths.wallpaper(ctx), Paths.wallpaperPng(ctx)).firstOrNull { it.exists() } ?: return
        val dest = File(Paths.wallpaperLibrary(ctx), "legacy-wallpaper.${old.extension.lowercase()}")
        if (!old.renameTo(dest)) { Log.w(TAG, "旧壁纸迁移失败: ${old.name}"); return }
        listOf(Paths.wallpaper(ctx), Paths.wallpaperPng(ctx)).forEach { it.delete() }
        val s = SettingsStore.read(ctx)
        if (s.wallpaperFile.isEmpty()) SettingsStore.write(ctx, s.copy(wallpaperFile = dest.name))
    }

    private fun seedBuiltins(ctx: Context) {
        val dir = Paths.wallpaperLibrary(ctx)
        val marker = File(dir, SEED_MARKER)
        if (marker.exists()) return
        for (name in BUILTIN_WALLPAPERS) {
            val dst = File(dir, "$BUILTIN_PREFIX$name.jpg")
            if (dst.exists()) continue
            // tmp → 校验可解码 → rename:复制到一半被杀不能留下半截文件(与 M1 ensureDefaultWallpaper 同理)
            val tmp = File(dir, "$BUILTIN_PREFIX$name.tmp")
            runCatching {
                ctx.assets.open("wallpapers/$name.jpg").use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out); out.flush(); out.fd.sync() }
                }
                check(Apps.isDecodableImage(tmp.absolutePath))
                if (!tmp.renameTo(dst)) { dst.delete(); check(tmp.renameTo(dst)) }
            }.onFailure { Log.w(TAG, "内置壁纸铺入失败 $name: ${it.message}") }
            tmp.delete()
        }
        // 标记只在全部 6 张确实落地后才写:被杀/失败留下的半成品库不能被当成"已铺完"——
        // 标记一旦存在,下次 prepare 直接 return,没有人会再补铺,library 就永久缺图。
        val seeded = BUILTIN_WALLPAPERS.all { File(dir, "$BUILTIN_PREFIX$it.jpg").isFile }
        if (seeded) runCatching { marker.createNewFile() }
        val defaultFile = File(dir, "$BUILTIN_PREFIX${BUILTIN_WALLPAPERS[0]}.jpg")
        val s = SettingsStore.read(ctx)
        if (s.wallpaperFile.isEmpty() && defaultFile.isFile) {
            SettingsStore.write(ctx, s.copy(wallpaperFile = defaultFile.name))
        }
    }

    /** 选择器选中:只记文件名 + 重置轮播计时。 */
    fun select(ctx: Context, file: File): Boolean {
        val name = sanitizeWallpaperFileName(file.name)
        if (name.isEmpty() || !Apps.isDecodableImage(file.absolutePath)) return false
        val s = SettingsStore.read(ctx)
        return SettingsStore.write(ctx, s.copy(wallpaperFile = name, wallpaperRotatedAt = System.currentTimeMillis()))
    }

    /**
     * 轮播一步:重扫目录(推完/删完图下次轮换即生效,与屏保同理)→ 当前的下一张 → 写盘。
     * **返回「是否写盘成功」,不是「是否换了图」**:单张图库也要刷新 rotatedAt,MainActivity 据此
     * settingsRevision++ → effect 以新 key 重启。若按「换了图才通知」写,单张时 key 不变、
     * effect 结束,轮播从此停转(铁律 6 的变体:effect 的续命信号必须由它自己的 key 承载)。
     */
    fun rotate(ctx: Context): Boolean {
        val s = SettingsStore.read(ctx)
        val names = libraryImages(ctx).map { it.name }
        val next = nextWallpaper(names, s.wallpaperFile) ?: s.wallpaperFile
        val ok = SettingsStore.write(ctx, s.copy(wallpaperFile = next, wallpaperRotatedAt = System.currentTimeMillis()))
        if (ok && next != s.wallpaperFile) Log.i(TAG, "壁纸轮播 → $next")
        return ok
    }

    /** APK 内置默认底:任何路径都失败时的最后一张,保证永远不黑屏。失败必须留痕:
     *  这是黑屏前最后一道回落,静默失败会让"为什么黑屏"排查不出来。 */
    fun builtinDefault(ctx: Context): Bitmap? = runCatching {
        ctx.assets.open(DEFAULT_WALLPAPER_ASSET).use { BitmapFactory.decodeStream(it) }
    }.onFailure { Log.w(TAG, "内置默认壁纸解不出来: ${it.message}") }.getOrNull()

    private const val OUT_W = 1920
    private const val OUT_H = 1080
    // 库里最多 6 张内置 + 1 张迁移的 legacy,轮播会挨个访问到:留够 12 个才能让 LRU 真正生效,
    // 不然任何非 identity 的轮播每一轮都把上一轮的缓存挤掉,退化成"从不命中"。
    private const val CACHE_KEEP = 12

    /** 处理后的位图:先查缓存,没有就渲染并写缓存。任何一步失败返回 null,调用方退回原图。IO 线程。 */
    fun processed(ctx: Context, src: File, spec: WallpaperSpec): Bitmap? {
        val key = wallpaperCacheKey(
            src.absolutePath, src.lastModified(), src.length(),
            spec.themed, spec.accentRgb, spec.blur, spec.dim,
        )
        val dir = Paths.wallpaperCacheDir(ctx)
        val cached = File(dir, "$key.jpg")
        if (cached.isFile) {
            runCatching { Apps.decodeScaled(cached.absolutePath, OUT_W, OUT_H) }.getOrNull()?.let {
                // 命中即续命:prune 按 mtime 淘汰最旧的,命中不刷新 mtime 的话这就是 FIFO 不是 LRU——
                // 常读的那张反而会被一串不相关的新渲染挤掉。
                cached.setLastModified(System.currentTimeMillis())
                return it
            }
            cached.delete()   // 缓存文件坏了:删掉重做
        }
        val t0 = System.currentTimeMillis()
        val bmp = runCatching { render(src, spec) }
            .onFailure { Log.w(TAG, "壁纸处理失败 ${src.name}: ${it.message}") }
            .getOrNull() ?: return null
        // 日志放在 writeCache 之后:压缩 + fsync + 清理也在这条阻塞路径上,漏掉就低估了真实耗时。
        writeCache(dir, cached, bmp)
        Log.i(TAG, "壁纸处理 ${src.name} blur=${spec.blur} dim=${spec.dim} themed=${spec.themed} 用时 ${System.currentTimeMillis() - t0}ms")
        return bmp
    }

    /**
     * 缩小 → 套 ColorMatrix → 放大。颜色运算与模糊都是线性算子、顺序可交换,
     * 所以矩阵作用在缩小后的小图上,几乎免费;blur=0 时矩阵直接作用于 1920×1080
     * (且与裁剪缩放合并成一次 draw,见 [cropScale])。
     */
    private fun render(src: File, spec: WallpaperSpec): Bitmap? {
        val decoded = Apps.decodeScaled(src.absolutePath, OUT_W, OUT_H)
        if (decoded == null) { Log.w(TAG, "壁纸源图解不出来 ${src.name}"); return null }
        val targetW = blurTargetWidth(spec.blur, OUT_W)
        val matrix = wallpaperColorMatrix(spec.themed, spec.accentRgb, spec.dim)
        // blur=0:裁剪 + 缩放 + 上色一次 draw 完事,decoded 之外只多分配这一张 1920×1080。
        if (targetW >= OUT_W) return cropScale(decoded, OUT_W, OUT_H, matrix)
        val full = cropScale(decoded, OUT_W, OUT_H, null)
        val small = downscale(full, targetW)
        val colored = applyMatrix(small, matrix)
        return if (colored.width == OUT_W && colored.height == OUT_H) colored else upscale(colored, OUT_W, OUT_H)
    }

    /**
     * 一次 drawBitmap 完成中心裁剪 + 缩放(+ blur=0 时的 ColorMatrix):只分配一张输出,不留全分辨率中间图。
     * 换成 Canvas 画而不是先前 `Bitmap.createBitmap(src,x,y,w,h)` 取子图再 `createScaledBitmap`:
     * 这两个 API 在「裁剪/缩放是无操作」时可能直接返回入参本身(别名同一个对象)——
     * pipeline 里任何位图都不能 recycle(),否则一旦命中别名会把上游(甚至刚解出来的源图)一起废掉。
     */
    private fun cropScale(src: Bitmap, w: Int, h: Int, matrix: FloatArray?): Bitmap {
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = (w / scale).toInt().coerceIn(1, src.width)
        val sh = (h / scale).toInt().coerceIn(1, src.height)
        val sx = (src.width - sw) / 2
        val sy = (src.height - sh) / 2
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            if (matrix != null) colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(matrix))
        }
        android.graphics.Canvas(out).drawBitmap(
            src,
            android.graphics.Rect(sx, sy, sx + sw, sy + sh),
            android.graphics.Rect(0, 0, w, h),
            paint,
        )
        return out
    }

    /** 反复减半到 ≤ 2× 目标,再一步缩到目标宽;每次减半都是一次 2×2 均值,叠起来就是一块便宜的低通滤波。 */
    private fun downscale(b: Bitmap, targetW: Int): Bitmap {
        var cur = b
        while (cur.width / 2 >= targetW * 2) {
            cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        }
        val targetH = maxOf(1, Math.round(targetW * OUT_H.toFloat() / OUT_W))
        cur = Bitmap.createScaledBitmap(cur, targetW, targetH, true)
        return boxBlur3(cur)
    }

    /** 3×3 均值:小图上的最后一道低通,消掉减半链留下的锯齿。小图最多 1920 宽,像素循环可接受。 */
    private fun boxBlur3(b: Bitmap): Bitmap {
        val w = b.width
        val h = b.height
        val src = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var bl = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy
                val xx = x + dx
                if (yy < 0 || yy >= h || xx < 0 || xx >= w) continue
                val p = src[yy * w + xx]
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; bl += p and 0xFF; n++
            }
            out[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (bl / n)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun applyMatrix(b: Bitmap, m: FloatArray): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(m))
        }
        android.graphics.Canvas(out).drawBitmap(b, 0f, 0f, paint)
        return out
    }

    /** 分级放大,每级 ≤ 4×:一次 16× 的 bilinear 会留下菱形纹。 */
    private fun upscale(b: Bitmap, w: Int, h: Int): Bitmap {
        var cur = b
        while (cur.width * 4 < w) {
            cur = Bitmap.createScaledBitmap(cur, cur.width * 4, cur.height * 4, true)
        }
        return Bitmap.createScaledBitmap(cur, w, h, true)
    }

    /**
     * tmp → rename 写缓存,然后只留最新(按 mtime)[CACHE_KEEP] 个——缓存命中会顺带刷新 mtime
     * ([processed]),所以这是真 LRU,不是写入顺序的 FIFO。顺带扫掉遗留超过 60s 的 .tmp
     * (compress 中途被杀留下的半成品;60s 内的可能是另一个还在写的调用,不能碰)。
     * 失败只记日志:这次仍用内存里的位图显示。
     */
    private fun writeCache(dir: File, dst: File, bmp: Bitmap) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "${dst.nameWithoutExtension}.tmp")
            tmp.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush(); out.fd.sync()
            }
            if (!tmp.renameTo(dst)) { dst.delete(); check(tmp.renameTo(dst)) }
            dir.listFiles { f -> f.isFile && f.extension == "jpg" }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(CACHE_KEEP)
                ?.forEach { it.delete() }
            val staleCutoff = System.currentTimeMillis() - 60_000
            dir.listFiles { f -> f.isFile && f.extension == "tmp" && f.lastModified() < staleCutoff }
                ?.forEach { it.delete() }
        }.onFailure { Log.w(TAG, "壁纸缓存写入失败: ${it.message}") }
    }

    /** 显示用位图。IO 线程。解不出来回落内置;调用方只在非 null 时换图。 */
    fun load(ctx: Context, spec: WallpaperSpec): Bitmap? {
        prepare(ctx)
        // prepare 可能刚把 wallpaperFile 写进 settings,而 spec 是拿旧 settings 组装的;补读一次。
        val name = spec.file.ifEmpty { SettingsStore.read(ctx).wallpaperFile }
        val src = resolveSource(ctx, name) ?: return builtinDefault(ctx)
        // 参数全零完全绕开管线:不解码两次、不写缓存、保留 F16(零回归路径)。
        // 处理失败退回原图而不是黑屏。
        if (!spec.isIdentity) processed(ctx, src, spec)?.let { return it }
        // RGBA_F16 保留 Ultra HDR gain map(与 M1 同);decodeScaled 在 F16 失败时自动回落 8888。
        return runCatching { Apps.decodeScaled(src.absolutePath, OUT_W, OUT_H, Bitmap.Config.RGBA_F16) }
            .onFailure { Log.w(TAG, "壁纸解码失败 ${src.name}: ${it.message}") }
            .getOrNull()
            ?: builtinDefault(ctx)
    }
}

/**
 * 壁纸层。**住在 MainActivity 的 setContent 顶层,不在 HomeScreen 里**:进出编辑页/设置页会把
 * 那一层整棵拆掉重建,壁纸若跟着走就要每次重解一张 1920×1080,期间纯黑——退出时黑闪一下。
 * key 只有 spec:换图 / 改参数 / 轮播都只换位图;新图就绪前旧图原样留着,再交叉淡入过去。
 */
@Composable
fun Wallpaper(ctx: Context, spec: WallpaperSpec) {
    // produceState 的 remember 不带 key:spec 变时只重启生产者,旧值留着 → 不闪黑
    val bmp by produceState<Bitmap?>(initialValue = null, spec) {
        val next = withContext(Dispatchers.IO) { Wallpapers.load(ctx, spec) }
        if (next != null) value = next
    }
    val b = bmp ?: return
    Crossfade(
        targetState = b,
        animationSpec = tween(Theme.WallpaperCrossfadeMs),
        label = "wallpaperCrossfade",
    ) { bitmap ->
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
