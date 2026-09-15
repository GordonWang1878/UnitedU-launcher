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

    /** APK 内置默认底:任何路径都失败时的最后一张,保证永远不黑屏。失败必须留痕:
     *  这是黑屏前最后一道回落,静默失败会让"为什么黑屏"排查不出来。 */
    fun builtinDefault(ctx: Context): Bitmap? = runCatching {
        ctx.assets.open(DEFAULT_WALLPAPER_ASSET).use { BitmapFactory.decodeStream(it) }
    }.onFailure { Log.w(TAG, "内置默认壁纸解不出来: ${it.message}") }.getOrNull()

    /** 显示用位图。IO 线程。解不出来回落内置;调用方只在非 null 时换图。 */
    fun load(ctx: Context, spec: WallpaperSpec): Bitmap? {
        prepare(ctx)
        // prepare 可能刚把 wallpaperFile 写进 settings,而 spec 是拿旧 settings 组装的;补读一次。
        val name = spec.file.ifEmpty { SettingsStore.read(ctx).wallpaperFile }
        val src = resolveSource(ctx, name) ?: return builtinDefault(ctx)
        // RGBA_F16 保留 Ultra HDR gain map(与 M1 同);decodeScaled 在 F16 失败时自动回落 8888。
        return runCatching { Apps.decodeScaled(src.absolutePath, 1920, 1080, Bitmap.Config.RGBA_F16) }
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
