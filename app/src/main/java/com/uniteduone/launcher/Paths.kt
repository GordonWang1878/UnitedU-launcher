package com.uniteduone.launcher

import android.content.Context
import java.io.File

/**
 * 全部可替换素材都放应用的**外部**文件目录:
 *   /sdcard/Android/data/com.uniteduone.launcher/files/
 * 这样 `adb push` 能直接写(不需要任何运行时权限),应用读也不需要权限——
 * 这就是设计文档 Q4 / Q9 里说的「固定路径后门」。
 */
object Paths {
    /**
     * 只用外部文件目录,**不回落到 internal**:两者是完全不同的路径,
     * 外部存储偶尔在开机时还没挂载,这时若回落就会在 internal 另写一份默认配置,
     * 等挂载后又读回 external——两份并存、谁生效取决于开机时序,是最难查的那类问题。
     * 拿不到时返回 null,调用方一律降级为「用内存里的默认值、不写盘」。
     */
    fun baseOrNull(ctx: Context): File? = ctx.getExternalFilesDir(null)?.also { it.mkdirs() }

    /**
     * **只给读路径用。**外置没挂时它指向 internal,那里必然没有这些文件,
     * 读到的结果是「没有」,这是对的降级。
     * 写路径一律先自己判 [baseOrNull] 是否为 null 再动手——否则会在 internal 另写一份,
     * 等外置挂上后又读回 external,两份并存、谁生效取决于开机时序。
     */
    fun base(ctx: Context): File = baseOrNull(ctx) ?: ctx.filesDir

    fun layoutJson(ctx: Context) = File(base(ctx), "layout.json")
    fun layoutBad(ctx: Context) = File(base(ctx), "layout.json.bad")
    fun settingsJson(ctx: Context) = File(base(ctx), "settings.json")
    fun settingsBad(ctx: Context) = File(base(ctx), "settings.json.bad")
    fun wallpaper(ctx: Context) = File(base(ctx), "wallpaper.jpg")
    fun wallpaperPng(ctx: Context) = File(base(ctx), "wallpaper.png")
    fun screensaver(ctx: Context) = File(base(ctx), "screensaver.jpg")
    fun screensaverPng(ctx: Context) = File(base(ctx), "screensaver.png")
    fun iconsDir(ctx: Context) = File(base(ctx), "icons").also { it.mkdirs() }
    fun iconFor(ctx: Context, pkg: String) = File(iconsDir(ctx), "$pkg.png")
    fun wallpaperLibrary(ctx: Context) = File(base(ctx), "library/wallpapers").also { it.mkdirs() }
    fun screensaverLibrary(ctx: Context) = File(base(ctx), "library/screensavers").also { it.mkdirs() }
    fun cardLibrary(ctx: Context) = File(base(ctx), "library/cards").also { it.mkdirs() }

    /** 处理后的壁纸缓存。外置 cache 优先(adb 能看、卸载即清),没挂用内置 cache。 */
    fun wallpaperCacheDir(ctx: Context) = File(ctx.externalCacheDir ?: ctx.cacheDir, "wallpapers").also { it.mkdirs() }
}
