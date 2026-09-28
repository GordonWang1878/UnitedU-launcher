package com.uniteduone.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "UnitedU"
private val WALLPAPER_IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")

/**
 * R61 之前的播种标记文件(library/wallpapers/.seeded)。**R116 起不再播种**——内置壁纸直接从 assets 读、不复制进
 * 用户图库(见 [BuiltinImages]);升级清理时顺手删掉这个标记(点开头,网格与上传页本来就看不见它)。
 */
private const val LEGACY_SEED_MARKER = ".seeded"

/**
 * 壁纸的文件侧:当前壁纸不再是复制出来的 wallpaper.jpg,而是 settings.json 里的一个选中值——
 * library/wallpapers/ 里的文件名,或 `builtin:<ID>` = 内置那张(R116)。选图只改字段,不复制、不 recreate。
 *
 * **内置壁纸(R116,2026-09-28 Gordon 定)**:Gordon 放进 `app/src/main/assets/builtin/wallpapers/` 的图,
 * 按目录自动发现([BuiltinImages.list],按文件名排序),直接从 assets 读、不复制进用户图库(永远在、删不掉)。
 * **默认壁纸固定用清单第一张**(不随机):没选过 / 选中的那张没了 → 它([resolveWallpaperChoice])。
 * 清单为空(正式包现在就是)时回落用户图库第一张;都没有 → 纯深色([GtvTokens.MenuBg])。
 * R61 的「复制进库 + 首次随机选一张当默认」播种机制整条删掉;R61 清理 6 张旧染色图副本的规则照旧。
 */
object Wallpapers {

    fun libraryImages(ctx: Context): List<File> =
        Paths.wallpaperLibrary(ctx).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in WALLPAPER_IMAGE_EXTS }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * 当前壁纸源文件(R116,规则见 [resolveWallpaperChoice]):选中的还在 → 它;否则默认内置清单第一张;
     * 清单为空 → library 按名排序第一张;都没有 → null(调用方画纯深色底,R61)。
     * 内置那张返回伪路径文件(见 [BUILTIN_PSEUDO_ROOT]),解码经 [decodeImagePath] 读 assets。IO 线程。
     */
    fun resolveSource(ctx: Context, value: String): File? {
        val builtins = BuiltinImages.list(ctx, BuiltinKind.WALLPAPERS)
        val lib = Paths.wallpaperLibrary(ctx)
        val choice = resolveWallpaperChoice(
            value = value,
            builtinIds = builtins.map { it.id },
            mineExists = { name -> sanitizeWallpaperFileName(name).isNotEmpty() && File(lib, name).isFile },
            firstMine = { libraryImages(ctx).firstOrNull()?.name },
        )
        return when (choice) {
            is ImageChoice.Builtin -> builtins.firstOrNull { it.id == choice.id }?.file
            is ImageChoice.Mine -> File(lib, choice.fileName)
            null -> null
        }
    }

    /**
     * 一次性准备:迁移旧根目录壁纸 + 清掉 R61 删掉的 6 张旧内置图(连同 R61 之前的播种标记)。
     * **R116 起不再播种**:内置壁纸直接从 assets 读,默认那张靠 [resolveSource] 解析,不写 settings.json。
     * IO 线程;外置没挂整段跳过。每次 [load] 都会跑,两步都幂等、没事可做时只是几次 `exists` 检查。
     * @return **是否真的往 settings.json 写了 wallpaperFile**。从 M2 升级(迁移旧壁纸)这一步会写,
     *   而 `MainActivity.homeSettings` 是在此之前读的、已经过期——跟随壁纸主色的用户整个首次会话都会看到预设色。
     *   调用方据此 `settingsRevision++` 让它重读。R61 清理旧内置图时若把 `wallpaperFile` 置空,同样返回 true。
     */
    fun prepare(ctx: Context): Boolean {
        if (Paths.baseOrNull(ctx) == null) return false
        val migrated = migrateLegacy(ctx)
        val cleaned = removeLegacySeeds(ctx)
        return migrated || cleaned
    }

    /**
     * R61:删掉 M3 起播种进图库的 6 张旧内置染色图(按完整文件名认,见 [LEGACY_SEEDED_WALLPAPERS]——那几个名字
     * 只可能是我们铺的),`wallpaperFile` 指向其中之一就置空。先读后写:没有要改的就不碰 settings.json
     * (这一步每次 [load] 都跑,不能每次都写盘)。
     * @return 是否真的把 `wallpaperFile` 改了。
     */
    private fun removeLegacySeeds(ctx: Context): Boolean {
        val dir = Paths.wallpaperLibrary(ctx)
        for (name in LEGACY_SEEDED_WALLPAPERS) {
            val f = File(dir, name)
            if (f.exists() && f.delete()) Log.i(TAG, "清掉旧内置壁纸 $name(R61)")
        }
        // R116:播种整条删了,标记文件没用了(R61 之后清单一直为空,不会有别的 unitedu- 副本)
        File(dir, LEGACY_SEED_MARKER).takeIf { it.exists() }?.delete()
        val current = SettingsStore.read(ctx).wallpaperFile
        if (wallpaperFileAfterLegacyCleanup(current) == current) return false
        var changed = false
        val res = SettingsStore.update(ctx) { s ->
            val next = wallpaperFileAfterLegacyCleanup(s.wallpaperFile)
            if (next != s.wallpaperFile) { changed = true; s.copy(wallpaperFile = next) } else s
        }
        return res != null && changed
    }

    /**
     * M1/M2 的壁纸是根目录 files/wallpaper.jpg|png(选择器复制过去的)。搬进 library,
     * 若设置里还没指定壁纸就指向它——升级后用户看到的仍是升级前那张。
     * 此后 adb 后门 = push 进 library/wallpapers/ 再在选择器里选(与文档一致)。
     */
    private fun migrateLegacy(ctx: Context): Boolean {
        val old = listOf(Paths.wallpaper(ctx), Paths.wallpaperPng(ctx)).firstOrNull { it.exists() } ?: return false
        val dest = File(Paths.wallpaperLibrary(ctx), "legacy-wallpaper.${old.extension.lowercase()}")
        if (!old.renameTo(dest)) { Log.w(TAG, "旧壁纸迁移失败: ${old.name}"); return false }
        listOf(Paths.wallpaper(ctx), Paths.wallpaperPng(ctx)).forEach { it.delete() }
        return pointAtIfUnset(ctx, dest)
    }

    /**
     * 「设置里还没指定壁纸就指向 [file]」——持锁的读-改-写(F1),不再「先读再整对象回写」。
     * @return 是否真的把 `wallpaperFile` 从空改成了它(写失败、或本来就有值 → false)。
     */
    private fun pointAtIfUnset(ctx: Context, file: File): Boolean {
        var changed = false
        val res = SettingsStore.update(ctx) { s ->
            if (s.wallpaperFile.isEmpty()) { changed = true; s.copy(wallpaperFile = file.name) } else s
        }
        return res != null && changed
    }

    /**
     * 选择器选中:只记选中值——用户图库的文件名,或内置那张的 `builtin:<ID>`(R116,[file] 是内置图的伪路径时)。
     */
    fun select(ctx: Context, file: File): Boolean {
        val builtin = builtinImageOf(file)?.takeIf { it.kind == BuiltinKind.WALLPAPERS }
        val value = if (builtin != null) encodeBuiltinChoice(builtin.id) else sanitizeWallpaperFileName(file.name)
        if (value.isEmpty() || !Apps.isDecodableImage(file.absolutePath)) return false
        return SettingsStore.update(ctx) { it.copy(wallpaperFile = value) } != null
    }

    /**
     * 「恢复默认」表(spec §4):清空壁纸处理缓存(cache/wallpapers/),不动 library 原图。
     * IO 线程,与 [processed] 同一目录。缓存文件按内容 hash 命名,清空后下次显示自动按
     * 当前 spec(恢复后是 blur=0/brightness=0)重新渲染,不会读到过期的模糊/加亮版本,
     * 也不会黑屏——`Wallpaper` 组合的 `bmp` 在新结果就绪前仍留着上一帧的位图。
     */
    fun clearCache(ctx: Context) {
        Paths.wallpaperCacheDir(ctx).listFiles()?.forEach { it.delete() }
    }

    /**
     * 任何路径都失败时的最后一道回落。~~APK 内置默认底 `wallpapers/00-neutral.jpg`~~——**R61 起恒为 null**:
     * 调用方([load] → [Wallpaper])把「没有壁纸」画成纯深色底 [GtvTokens.MenuBg]。R116 的内置壁纸是 [resolveSource]
     * 正常解析出来的一张(默认 = 清单第一张),不走这里;保留这个函数只是让回落点只有一处。
     */
    @Suppress("UNUSED_PARAMETER")
    fun builtinDefault(ctx: Context): Bitmap? = null

    /**
     * 从一张壁纸**原图**提主色(RGB,不带 alpha);抽不到返回 null。**必须在 IO 线程调用**:
     * 解一张 320×180 的缩略图(够 Palette 取色又不占内存)再跑 [androidx.palette.graphics.Palette]。
     *
     * 取色优先级:vibrant(有活力的主色)→ 落空再用 dominant(占面积最大的色)。
     * 读原图不读处理后的缓存——模糊 / 亮度处理过的图取出的主色会跟着滑块漂。
     * 用默认 ARGB_8888 而非 RGBA_F16:Palette 不吃 F16。任何一步落空都只返回 null,绝不抛。
     */
    fun paletteAccent(ctx: Context, src: File): Int? = runCatching {
        val bmp = Apps.decodeScaled(src.absolutePath, 320, 180) ?: return@runCatching null
        val palette = androidx.palette.graphics.Palette.from(bmp).generate()
        val rgb = palette.getVibrantColor(0).takeIf { it != 0 }
            ?: palette.getDominantColor(0).takeIf { it != 0 }
            ?: return@runCatching null
        rgb and 0xFFFFFF
    }.getOrNull()

    // 留 12 个:当年是为了「6 张内置 + 1 张迁移的 legacy 被轮播挨个访问」时 LRU 仍能命中;轮播(R61)删了,
    // 数值不变——来回换几张壁纸、调几次滑块仍能命中缓存。
    private const val CACHE_KEEP = 12

    /**
     * **R122**:壁纸输出尺寸 = 当前窗口像素尺寸(长边封顶 3840,见 [screenDecodeSize]),取代写死的 1920×1080。
     * [ctx] 用 Activity(它的 displayMetrics 跟着窗口走;`wm size` 改了之后随配置变化更新)。
     */
    fun outputSize(ctx: Context): Pair<Int, Int> =
        ctx.resources.displayMetrics.let { screenDecodeSize(it.widthPixels, it.heightPixels) }

    /**
     * 处理后的位图:先查缓存,没有就渲染并写缓存。任何一步失败返回 null,调用方退回原图。IO 线程。
     * [outW]×[outH] = [outputSize]。**R123**:API 34+ 源图带增益图时输出也带(见 [render]),缓存文件是
     * Ultra HDR JPEG(`Bitmap.compress(JPEG)` 遇到带增益图的位图自动写 XMP `hdrgm` + 第二帧),命中时解出来同样带增益图。
     */
    fun processed(ctx: Context, src: File, spec: WallpaperSpec, outW: Int, outH: Int): Bitmap? {
        // 内置壁纸(R116)是伪路径,lastModified / length 恒为 0:用 APK 更新时刻当「修改时间」,新版换了同名图不会错配旧缓存
        val builtin = builtinAssetPathOf(src.path) != null
        val mtime = if (builtin) BuiltinImages.apkStamp(ctx) else src.lastModified()
        val key = wallpaperCacheKey(
            src.absolutePath, mtime, src.length(), spec.blur, spec.brightness,
            outW = outW, outH = outH, gainmaps = HdrGainmaps.supported,
        )
        val dir = Paths.wallpaperCacheDir(ctx)
        val cached = File(dir, "$key.jpg")
        if (cached.isFile) {
            runCatching { Apps.decodeScaled(cached.absolutePath, outW, outH) }.getOrNull()?.let {
                // 命中即续命:prune 按 mtime 淘汰最旧的,命中不刷新 mtime 的话这就是 FIFO 不是 LRU——
                // 常读的那张反而会被一串不相关的新渲染挤掉。
                cached.setLastModified(System.currentTimeMillis())
                Log.i(TAG, "壁纸缓存命中 ${src.name} blur=${spec.blur} brightness=${spec.brightness} 目标 ${outW}x$outH → ${HdrGainmaps.describe(it)}")
                return it
            }
            cached.delete()   // 缓存文件坏了:删掉重做
        }
        val t0 = System.currentTimeMillis()
        val bmp = runCatching { render(src, spec, outW, outH) }
            .onFailure { Log.w(TAG, "壁纸处理失败 ${src.name}: ${it.message}") }
            .getOrNull() ?: return null
        // 日志放在 writeCache 之后:压缩 + fsync + 清理也在这条阻塞路径上,漏掉就低估了真实耗时。
        writeCache(dir, cached, bmp)
        Log.i(TAG, "壁纸处理 ${src.name} blur=${spec.blur} brightness=${spec.brightness} 目标 ${outW}x$outH → ${HdrGainmaps.describe(bmp)} 用时 ${System.currentTimeMillis() - t0}ms")
        return bmp
    }

    /**
     * 缩小 → 套 ColorMatrix → 放大。颜色运算与模糊都是线性算子、顺序可交换,
     * 所以矩阵作用在缩小后的小图上,几乎免费;blur=0 时矩阵直接作用于输出尺寸的图
     * (且与裁剪缩放合并成一次 draw,见 [cropScale])。
     *
     * **R123(API 34+,源图带增益图时)**:增益图先从解出来的位图上摘下([HdrGainmaps.detach],为什么必须摘见那里),
     * 底图照旧走上面这一套(与 R123 之前逐像素相同);增益图单独走**同一套几何**:同一个中心裁剪区(换算到增益图坐标,
     * [gainmapRectFor])、同比例缩放、模糊时缩到同一比例的宽度([gainmapBlurWidth])再放大,最后带着源图的全部
     * 参数挂回输出。**亮度只作用于底图**:增益图记的是「HDR 比 SDR 亮多少倍」,不动它,HDR 画面就随底图按同一个
     * 倍数变暗 / 变亮(压暗时高光与画面等比压暗;提亮时底图里被截到 255 的区域失去纹理,HDR 高光在那里只剩增益图
     * 自己那一层起伏——提亮本来就会丢高光层次,HDR 下更明显)。增益图处理失败只丢增益图、不丢壁纸。
     */
    private fun render(src: File, spec: WallpaperSpec, outW: Int, outH: Int): Bitmap? {
        val decoded = Apps.decodeScaled(src.absolutePath, outW, outH)
        if (decoded == null) { Log.w(TAG, "壁纸源图解不出来 ${src.name}"); return null }
        val gain = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HdrGainmaps.detach(decoded) else null
        val crop = centerCropRect(decoded.width, decoded.height, outW, outH)
        val targetW = blurTargetWidth(spec.blur, outW)
        val matrix = wallpaperColorMatrix(spec.brightness)
        // blur=0:裁剪 + 缩放 + 上色一次 draw 完事,decoded 之外只多分配这一张输出尺寸的图。
        val out = if (targetW >= outW) {
            cropScale(decoded, crop, outW, outH, matrix)
        } else {
            val full = cropScale(decoded, crop, outW, outH, null)
            val small = downscale(full, targetW)
            val colored = applyMatrix(small, matrix)
            if (colored.width == outW && colored.height == outH) colored else upscale(colored, outW, outH)
        }
        if (gain != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { transformGainmap(gain, decoded.width, decoded.height, crop, outW, outH, targetW) }
                .onFailure { Log.w(TAG, "增益图处理失败 ${src.name}(只显示 SDR): ${it.message}") }
                .getOrNull()
                ?.let { out.gainmap = it }
        }
        return out
    }

    /**
     * R123:增益图走与底图同一套几何(见 [render])。[baseW]×[baseH] = 解出来的底图尺寸(增益图坐标按它换算),
     * [targetW] = 底图的模糊工作宽度(≥ [outW] 表示不模糊)。
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun transformGainmap(
        gm: Gainmap, baseW: Int, baseH: Int, crop: PixelRect, outW: Int, outH: Int, targetW: Int,
    ): Gainmap {
        val contents = gm.gainmapContents
        val (gw, gh) = gainmapSizeFor(outW, outH, baseW, baseH, contents.width, contents.height)
        val region = gainmapRectFor(crop, baseW, baseH, contents.width, contents.height)
        var g = HdrGainmaps.drawRegion(contents, region, gw, gh)
        if (targetW < outW) {
            val small = downscale(g, gainmapBlurWidth(targetW, outW, gw).coerceAtMost(gw))
            g = if (small.width == gw && small.height == gh) small else upscale(small, gw, gh)
        }
        return HdrGainmaps.copyWithContents(gm, g)
    }

    /**
     * 一次 drawBitmap 完成中心裁剪 + 缩放(+ blur=0 时的 ColorMatrix):只分配一张输出,不留全分辨率中间图。
     * 换成 Canvas 画而不是先前 `Bitmap.createBitmap(src,x,y,w,h)` 取子图再 `createScaledBitmap`:
     * 这两个 API 在「裁剪/缩放是无操作」时可能直接返回入参本身(别名同一个对象)——
     * pipeline 里任何位图都不能 recycle(),否则一旦命中别名会把上游(甚至刚解出来的源图)一起废掉。
     * [crop] = [centerCropRect](R123 抽成纯函数,算术不变)。[src] 此时已不带增益图(R123)。
     */
    private fun cropScale(src: Bitmap, crop: PixelRect, w: Int, h: Int, matrix: FloatArray?): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            if (matrix != null) colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(matrix))
        }
        android.graphics.Canvas(out).drawBitmap(
            src,
            android.graphics.Rect(crop.x, crop.y, crop.x + crop.w, crop.y + crop.h),
            android.graphics.Rect(0, 0, w, h),
            paint,
        )
        return out
    }

    /**
     * 反复减半到 ≤ 2× 目标,再一步缩到目标宽;每次减半都是一次 2×2 均值,叠起来就是一块便宜的低通滤波。
     * 高按 [b] 自己的宽高比(底图就是输出尺寸,1080p 时与 R123 之前按 1920×1080 算的逐位相同;增益图按它自己的尺寸)。
     * `ALPHA_8` 的增益图缩放后仍是 `ALPHA_8`(`createScaledBitmap` 保留该格式)。
     */
    private fun downscale(b: Bitmap, targetW: Int): Bitmap {
        var cur = b
        while (cur.width / 2 >= targetW * 2) {
            cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        }
        val targetH = maxOf(1, Math.round(targetW * b.height.toFloat() / b.width))
        cur = Bitmap.createScaledBitmap(cur, targetW, targetH, true)
        return boxBlur3(cur)
    }

    /**
     * 3×3 均值:小图上的最后一道低通,消掉减半链留下的锯齿。小图最多是输出宽,像素循环可接受。
     * 四个通道都平均(R123:`ALPHA_8` 增益图的值在 alpha 里;底图 alpha 恒 255,平均完仍是 255,与之前逐位相同),
     * 输出格式跟输入走。
     */
    private fun boxBlur3(b: Bitmap): Bitmap {
        val w = b.width
        val h = b.height
        val src = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0; var r = 0; var g = 0; var bl = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy
                val xx = x + dx
                if (yy < 0 || yy >= h || xx < 0 || xx >= w) continue
                val p = src[yy * w + xx]
                a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; bl += p and 0xFF; n++
            }
            out[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (bl / n)
        }
        val config = if (b.config == Bitmap.Config.ALPHA_8) Bitmap.Config.ALPHA_8 else Bitmap.Config.ARGB_8888
        return Bitmap.createBitmap(out, w, h, config)
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
     * 独立临时文件 → rename 写缓存,然后只留最新(按 mtime)[CACHE_KEEP] 个——缓存命中会顺带刷新 mtime
     * ([processed]),所以这是真 LRU,不是写入顺序的 FIFO。顺带扫掉遗留超过 60s 的 .tmp
     * (compress 中途被杀留下的半成品;60s 内的可能是另一个还在写的调用,不能碰)。
     * 失败只记日志:这次仍用内存里的位图显示。
     */
    private fun writeCache(dir: File, dst: File, bmp: Bitmap) {
        runCatching {
            // 独立临时文件(writeFileAtomically):两次同 key 的渲染不会往同一个 .tmp 里交错写出半张 JPEG
            check(writeFileAtomically(dst) { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, out) })
            dir.listFiles { f -> f.isFile && f.extension == "jpg" }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(CACHE_KEEP)
                ?.forEach { it.delete() }
            val staleCutoff = System.currentTimeMillis() - 60_000
            dir.listFiles { f -> f.isFile && f.extension == "tmp" && f.lastModified() < staleCutoff }
                ?.forEach { it.delete() }
        }.onFailure { Log.w(TAG, "壁纸缓存写入失败: ${it.message}") }
    }

    /**
     * [load] 的结果。[settingsChanged] = 这一趟 [prepare] 往 settings.json 写了东西,
     * 调用方必须重读(否则跟随壁纸主色的用户整个首次会话都停在预设色)。
     * [empty] = 根本没有壁纸(设置没指定、图库也空,R61 起没有内置图兜底):调用方要把旧位图**清掉**、画纯深色,
     * 而不是像「解码失败」那样留着上一张——否则删光图库之后首页还挂着已删的那张。
     */
    data class Loaded(val bitmap: Bitmap?, val settingsChanged: Boolean, val empty: Boolean = false)

    /**
     * 显示用位图。IO 线程。解不出来回落 [builtinDefault](R61 起为 null);调用方只在非 null 时换图,[Loaded.empty] 时清空。
     * [outW]×[outH] = [outputSize](R122)。
     */
    fun load(ctx: Context, spec: WallpaperSpec, outW: Int, outH: Int): Loaded {
        val settingsChanged = prepare(ctx)
        // prepare 可能刚把 wallpaperFile 写进 settings(或 R61 清理时置空),而 spec 是拿旧 settings 组装的;补读一次。
        val name = if (settingsChanged) SettingsStore.read(ctx).wallpaperFile else spec.file.ifEmpty { SettingsStore.read(ctx).wallpaperFile }
        val src = resolveSource(ctx, name)
            ?: return Loaded(builtinDefault(ctx), settingsChanged, empty = builtinDefault(ctx) == null)
        // 参数全零完全绕开管线:不解码两次、不写缓存(零回归路径)。
        // 处理失败退回原图而不是黑屏。
        if (!spec.isIdentity) {
            processed(ctx, src, spec, outW, outH)?.let { return Loaded(it, settingsChanged) }
        }
        // **R125**:ARGB_8888(R125 之前是 RGBA_F16)。HDR 只靠增益图:BitmapFactory 不论底图格式都把增益图挂上,
        // 底图里存的永远是 8 位 JPEG 的 SDR 画面,F16 只是把它加宽,内存翻倍、多不出一点 HDR(A95L 消融:GPU 量不出差别)。
        val bmp = runCatching { Apps.decodeScaled(src.absolutePath, outW, outH) }
            .onFailure { Log.w(TAG, "壁纸解码失败 ${src.name}: ${it.message}") }
            .getOrNull()
        if (bmp != null) Log.i(TAG, "壁纸原图 ${src.name} 目标 ${outW}x$outH → ${HdrGainmaps.describe(bmp)}")
        return Loaded(bmp ?: builtinDefault(ctx), settingsChanged)
    }
}

/**
 * 壁纸层。**住在 MainActivity 的 setContent 顶层,不在 HomeScreen 里**:进出编辑页/设置页会把
 * 那一层整棵拆掉重建,壁纸若跟着走就要每次重解一张全屏图,期间纯黑——退出时黑闪一下。
 * key 只有 spec(+ R122 的窗口尺寸):换图 / 改参数 / 轮播都只换位图;新图就绪前旧图原样留着,再交叉淡入过去。
 *
 * **Ruling R45(2026-09-22,取代 R35 的「壁纸随整页上移」与 R36 的两层方案)**:壁纸**单层、原地不动**,
 * 只随首页整页位移变暗。owner 真机:「右边的壁纸有双重的残影,这很恐怖:我移上去的时候,龙猫会向上移,
 * 但它原来位置上留了一个残影。」R36 是两层同一位图(上层随页面 1:1 上移并淡出、底层原地常驻 20%),
 * R42 最小位移后常常只移几十 dp,上层淡不完,两只错位的龙猫同时可见——只要两份错位副本同时可见就必然
 * 残影,所以撤掉的是方案本身。Google 实测(`docs/screenshots/gtv/22-google-backdrop-static-while-scrolling.jpg`,
 * launcherx 录像 #12/#13/#14、#26/#28/#30):上下滚动时 backdrop 图原地一动不动,只变暗 / 换图。
 *
 * 位移量仍由 HomeScreen 每帧上报、MainActivity 持有,但只用来算 [alpha]:
 * `GtvLayout.wallpaperAlpha(pageShift)`,1 → `GtvLayout.WALLPAPER_BROWSE_ALPHA`(0.2)。以 lambda 的形式
 * 在绘制阶段读取(`graphicsLayer {}`),动画每一帧只改图层透明度,**不重组**这个 composable、更不重解位图。
 * 静止态(位移 0)alpha 1、无位移、取景不变,与 R35 之前逐像素一致。
 */
@Composable
fun Wallpaper(
    ctx: Context,
    spec: WallpaperSpec,
    onSettingsChanged: () -> Unit = {},
    alpha: () -> Float = { 1f },
    /**
     * R110:非 null 时走两张缓存图层的画法(见 [HomeBackdropBridge]),并把「此刻走没走」写回它。
     * null(没有别的调用点,留给将来)= 原来的单层画法。
     */
    bridge: HomeBackdropBridge? = null,
    /** R110:这一帧 R85 渐变画进背景图层(true)还是由首页自己画(false)。绘制阶段读。 */
    gradientBaked: () -> Boolean = { false },
) {
    // R122:输出尺寸跟着窗口走。读 LocalConfiguration 是为了订阅配置变化(清单里 screenSize 由应用自己处理、
    // 不重建 Activity):`wm size` / 换分辨率之后按新尺寸重解。
    val configuration = LocalConfiguration.current
    val outSize = remember(configuration) { Wallpapers.outputSize(ctx) }
    // produceState 的 remember 不带 key:spec / 尺寸变时只重启生产者,旧值留着 → 不闪黑
    val bmp by produceState<Bitmap?>(initialValue = null, spec, outSize) {
        val loaded = withContext(Dispatchers.IO) { Wallpapers.load(ctx, spec, outSize.first, outSize.second) }
        // withContext 回到主线程之后再通知:调用方要改的是 Compose 状态。
        // prepare 只有首启/升级那一趟会写,写完 wallpaperFile 非空(或 R61 清理后已不再指向旧图),不会自激。
        if (loaded.settingsChanged) onSettingsChanged()
        // R61:没有任何壁纸 → 清掉旧位图(画纯深色);解码失败 → 旧位图留着(不闪黑)。
        if (loaded.empty) value = null else loaded.bitmap?.let { value = it }
    }
    // R61:没有壁纸(或首张还在解码)时画纯深色底——用 Google「黑」的那个 surface 色 [GtvTokens.MenuBg](R24 的
    // 衰减终值也是它),不新造颜色。不乘 [alpha]:没有图就没有「浏览态变暗」这回事,整页始终同一个深色。
    val b = bmp ?: run {
        if (bridge != null) SideEffect { bridge.wallpaperLayered = false }
        Box(Modifier.fillMaxSize().background(GtvTokens.MenuBg))
        return
    }
    if (bridge != null) {
        SideEffect { bridge.wallpaperLayered = true }
        // R124:带增益图的壁纸,显示器 HDR/SDR 比例跨档时整组缓存图层重建(推导见 HomeBackdrop.kt 的 R124 一节)
        val hdrBucket = rememberHdrRatioBucket(active = HdrGainmaps.has(b))
        key(hdrBucket) { LayeredWallpaper(b, alpha, gradientBaked) }
        return
    }
    Crossfade(
        targetState = b,
        animationSpec = tween(Theme.WallpaperCrossfadeMs),
        label = "wallpaperCrossfade",
    ) { bitmap ->
        // R45:**单层、不位移**。R36 的「底层常驻 20% + 上层随页面上移淡出」两层会在小位移时露出两份
        // 错位的图(owner:「双重的残影」),已删除。**仍然不许**为了浏览态改动静止态的取景
        // (放大、画高、裁边都算;R36 修正时被否的做法是把壁纸画高 192 dp,静止时整张图被放大约 18%)。
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha() },
        )
    }
}

/**
 * **R110**:壁纸(+ 进图层时的 R85 渐变)画成两张 [CompositingStrategy.Offscreen] 缓存图层,按 [backdropLerp] 合成。
 * 推导与取舍见 [HomeBackdropBridge]。每层里是**与原来同一套**画法——Crossfade 换图、`ContentScale.Crop`、
 * 下层给每张图套 alpha [GtvLayout.WALLPAPER_BROWSE_ALPHA] 的 `graphicsLayer`(原来套的是动画中的 alpha),
 * 所以换图的交叉淡入在两层里同步进行,与单层时一样。
 * **浏览态 alpha 只在图层的合成参数里读**(`graphicsLayer {}` 块):位移动画每帧只改两张纹理的合成 alpha,
 * 图层内容不失效、不重画——这正是省下来的那两道全屏着色。
 */
@Composable
private fun LayeredWallpaper(b: Bitmap, alpha: () -> Float, gradientBaked: () -> Boolean) {
    val brush = remember { homeFadeBrush() }
    // 下层 P_B:t 到 1(静止在首行)时整层被上层完全盖住,alpha 置 0 让 HWUI 直接跳过它。
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                this.alpha = if (backdropLerp(alpha()) >= 1f) 0f else 1f
            },
    ) {
        WallpaperImages(b, GtvLayout.WALLPAPER_BROWSE_ALPHA)
        if (GtvTokens.HOME_FADE_ENABLED) HomeFadeGradient(brush, gradientBaked)
    }
    // 上层 P_1:以 t 合成(t = 0 时 alpha 0,同样被跳过)。
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                this.alpha = backdropLerp(alpha())
            },
    ) {
        WallpaperImages(b, 1f)
        if (GtvTokens.HOME_FADE_ENABLED) HomeFadeGradient(brush, gradientBaked)
    }
}

/** 图层里的壁纸本体:与 [Wallpaper] 单层画法同一个 Crossfade,每张图套一个固定 alpha [imageAlpha] 的图层。 */
@Composable
private fun WallpaperImages(b: Bitmap, imageAlpha: Float) {
    Crossfade(
        targetState = b,
        animationSpec = tween(Theme.WallpaperCrossfadeMs),
        label = "wallpaperCrossfade",
    ) { bitmap ->
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = imageAlpha },
        )
    }
}
