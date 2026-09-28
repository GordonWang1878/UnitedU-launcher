package com.uniteduone.launcher

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 内置图的 Android 侧(Ruling R115):按目录自动发现 + 从 assets 读字节。规则与纯函数在 BuiltinCatalog.kt。
 *
 * - **列目录**:`AssetManager.list("builtin/<分类>")` → [builtinCatalog](扩展名过滤、按文件名排序、ID 去重、
 *   命名警告)。**必须在 IO 线程调用**(第一次会读 APK 的目录表);结果进程内缓存,之后同一分类直接返回,
 *   主线程也可以读 [cached]。APK 里的 assets 在进程生命周期内不会变(换 APK = 换进程),缓存不需要失效。
 * - **读字节**:内置图以伪路径文件出现在各处(见 [BUILTIN_PSEUDO_ROOT]);解码一律走 [decodeImagePath],
 *   复制(换卡片图)走 [open]。两者认出伪路径就改读 assets,否则照旧读文件。
 * - **AssetManager 从哪来**:[init] 记下 applicationContext 的那一份(MainActivity / UnitedUDream 的 onCreate 调,
 *   [list] 也顺手记)。伪路径文件只由 [list] 的结果产生,所以任何一个伪路径被解码之前 AssetManager 一定已经记下了。
 */
object BuiltinImages {
    private const val TAG = "UnitedU"

    @Volatile private var assets: AssetManager? = null
    private val catalogs = ConcurrentHashMap<BuiltinKind, List<BuiltinImage>>()

    fun init(ctx: Context) {
        if (assets == null) assets = ctx.applicationContext.assets
    }

    /** 这一分类的内置清单(可能为空)。IO 线程;进程内缓存。列目录失败按空清单处理、记日志、下次再试。 */
    fun list(ctx: Context, kind: BuiltinKind): List<BuiltinImage> {
        catalogs[kind]?.let { return it }
        init(ctx)
        val names = runCatching { ctx.applicationContext.assets.list("$BUILTIN_ASSET_DIR/${kind.dir}")?.toList() }
            .onFailure { Log.w(TAG, "内置图目录读不了 ${kind.dir}", it) }
            .getOrNull() ?: return emptyList()
        val list = builtinCatalog(kind, names) { Log.w(TAG, it) }
        catalogs[kind] = list
        if (list.isNotEmpty()) Log.i(TAG, "内置图 ${kind.dir}: ${list.joinToString { it.fileName }}")
        return list
    }

    /** 已经列过的清单(主线程可调,不碰 AssetManager);还没列过 → null(调用方到 IO 线程调 [list])。 */
    fun cached(kind: BuiltinKind): List<BuiltinImage>? = catalogs[kind]

    /** 三个分类一起列一遍(IO 线程,启动时预热:打开选图页时清单已在缓存里,网格不用等)。 */
    fun prewarm(ctx: Context) {
        for (k in BuiltinKind.entries) list(ctx, k)
    }

    /**
     * 读这个文件的字节:内置图的伪路径从 assets 读,其余照旧读文件。调用方负责关流。
     * 用在「换卡片图」把选中的图复制成 icons/<包名>.png(内置那张复制的是它的内容,不是往图库里铺一份)。
     */
    fun open(file: File): InputStream {
        val asset = builtinAssetPathOf(file.path) ?: return file.inputStream()
        val am = assets ?: error("AssetManager 还没记下,读不了内置图 $asset")
        return am.open(asset)
    }

    /**
     * 内置图的「修改时间」:APK 装上 / 更新的时刻。壁纸处理缓存的键要用它——伪路径的 `lastModified()` 恒为 0,
     * Gordon 在新版里换了同名的图,旧缓存就会被错配。读不到 → 0。
     */
    fun apkStamp(ctx: Context): Long =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L)
}

/**
 * `BitmapFactory.decodeFile` 的替身:[path] 是内置图的伪路径时从 assets 解([BitmapFactory.decodeStream],
 * `inJustDecodeBounds` 照样只读尺寸),否则就是 `decodeFile`。任何失败 → null,不抛。
 * 各处解码(缩略图、壁纸、屏保、卡片图、预览)都走它,内置图因此能出现在任何一个原本只认文件的地方。
 */
internal fun decodeImagePath(path: String, opts: BitmapFactory.Options? = null): Bitmap? {
    val asset = builtinAssetPathOf(path) ?: return BitmapFactory.decodeFile(path, opts)
    return runCatching { BuiltinImages.open(File(path)).use { BitmapFactory.decodeStream(it, null, opts) } }
        .onFailure { Log.w("UnitedU", "内置图解码失败 $asset: ${it.message}") }
        .getOrNull()
}
