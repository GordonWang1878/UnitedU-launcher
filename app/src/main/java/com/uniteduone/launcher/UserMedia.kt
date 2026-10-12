package com.uniteduone.launcher

import android.content.Context
import android.util.Log
import java.io.File

/**
 * **资料库里的「我的」图片与视频**(R174,2026-10-12 Gordon:上传页分「卡片图 / 图片 / 视频 / 应用」四种)。
 *
 * - **图片**只有一份,放在 library/wallpapers/(目录名是历史,回退包只认它):换壁纸页与屏保图库的「我的」都列它;
 *   参不参与屏保轮播看 settings.json `screensaverPhotos`(默认不参与,同在线图库)。
 * - **视频**放在 library/screensavers/,只在屏保图库里出现,照旧全部参与轮播。
 * - **旧屏保照片**(R174 之前传进 library/screensavers/ 的照片)一次性**复制**进图片库并记为参与轮播(它们原来就在轮播里),
 *   原文件留在原处:回退包(旧代码)只从那里读屏保照片(数据格式向下兼容铁律:不改目录结构)。新代码不再从那里读照片。
 *
 * 全部 IO 线程调用。
 */
internal object UserMedia {
    private val migrateLock = Any()

    /** 图片库里的图片(按文件名排序)。 */
    fun images(ctx: Context): List<File> = listIn(Paths.wallpaperLibrary(ctx), ScreensaverMedia.PHOTO_EXTS)

    /** 屏保视频(按文件名排序)。旧屏保照片不在这里(已迁进图片库)。 */
    fun videos(ctx: Context): List<File> = listIn(Paths.screensaverLibrary(ctx), ScreensaverMedia.VIDEO_EXTS)

    private fun listIn(dir: File, exts: Set<String>): List<File> =
        dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in exts }?.sortedBy { it.name } ?: emptyList()

    /** 这张「我的」文件是不是图片库里的图(而不是屏保视频)。 */
    fun isLibraryImage(ctx: Context, file: File): Boolean =
        file.parentFile?.absolutePath == Paths.wallpaperLibrary(ctx).absolutePath && file.extension.lowercase() in ScreensaverMedia.PHOTO_EXTS

    /**
     * 把还没迁过的旧屏保照片复制进图片库,并把复制出来的名字记进 `screensaverPhotos`(原来就在轮播里)。
     * 幂等:迁过的源文件名记在 `migratedScreensaverPhotos`,用户之后删了复制品也不会再复制回来。
     * 先复制后记账:中途被杀最多下次多复制一份,不丢图。
     */
    fun migrateLegacyScreensaverPhotos(ctx: Context) = synchronized(migrateLock) {
        if (Paths.baseOrNull(ctx) == null) return@synchronized
        val legacy = listIn(Paths.screensaverLibrary(ctx), ScreensaverMedia.PHOTO_EXTS)
        if (legacy.isEmpty()) return@synchronized
        val done = SettingsStore.read(ctx).migratedScreensaverPhotos
        val todo = legacy.filter { it.name !in done }
        if (todo.isEmpty()) return@synchronized
        val dir = Paths.wallpaperLibrary(ctx)
        val copied = HashMap<String, String>()
        for (src in todo) {
            val name = uniqueName(dir.list()?.toSet() ?: emptySet(), src.name)
            val ok = runCatching { writeFileAtomically(File(dir, name)) { out -> src.inputStream().use { it.copyTo(out) } } }.getOrDefault(false)
            if (ok) copied[src.name] = name else Log.w("UnitedU", "旧屏保照片迁移失败: ${src.name}")
        }
        if (copied.isEmpty()) return@synchronized
        SettingsStore.update(ctx) {
            it.copy(
                screensaverPhotos = it.screensaverPhotos + copied.values,
                migratedScreensaverPhotos = it.migratedScreensaverPhotos + copied.keys,
            )
        }
        Log.i("UnitedU", "旧屏保照片迁进资料库 ${copied.size} 张")
    }

    /** 屏保图库「我的」:图片库全部图片 + 屏保视频(先迁旧照片)。 */
    fun poolItems(ctx: Context): List<File> {
        migrateLegacyScreensaverPhotos(ctx)
        return (images(ctx) + videos(ctx)).sortedBy { it.name }
    }

    /** 参与轮播的「我的」:`screensaverPhotos` 里的图片 + 全部视频。 */
    fun rotationItems(ctx: Context): List<File> {
        migrateLegacyScreensaverPhotos(ctx)
        val joined = SettingsStore.read(ctx).screensaverPhotos
        return (images(ctx).filter { it.name in joined } + videos(ctx)).sortedBy { it.name }
    }
}

/** 加入 / 移出轮播(纯函数,单测):在集合里就去掉,不在就加上。 */
internal fun togglePhoto(current: Set<String>, name: String): Set<String> =
    if (name in current) current - name else current + name
