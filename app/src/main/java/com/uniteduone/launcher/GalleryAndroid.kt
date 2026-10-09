package com.uniteduone.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File

/** Process singleton; Context construction performs no disk access on the calling thread. */
object GalleryAndroid {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var instance: GalleryRepository? = null
    private var storage: kotlinx.coroutines.Deferred<GalleryStore>? = null
    private fun storage(ctx: Context): kotlinx.coroutines.Deferred<GalleryStore> = synchronized(this) {
        storage ?: run {
            val app = ctx.applicationContext
            scope.async { GalleryStore(File(app.filesDir, "gallery"), File(app.cacheDir, "gallery")) }
                .also { storage = it }
        }
    }
    suspend fun store(ctx: Context): GalleryStore = storage(ctx).await()
    /** Blocking accessor for existing IO-only wallpaper and playlist pipelines. Never call on main. */
    fun storeOnIo(ctx: Context): GalleryStore {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Gallery disk access on main" }
        return kotlinx.coroutines.runBlocking { store(ctx) }
    }
    fun repository(ctx: Context): GalleryRepository = synchronized(this) {
        val app = ctx.applicationContext
        instance ?: SharedGalleryRepository({ storage(app).await() },
            GalleryHttp(BuildConfig.GALLERY_BASE_URL, allowLoopbackHttp = BuildConfig.DEBUG), scope,
            ::inspect, ::thumbnail).also { instance = it }
    }
    private fun bounds(file: File): BitmapFactory.Options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, this)
        require(outWidth in 1..GALLERY_MAX_DIMENSION && outHeight in 1..GALLERY_MAX_DIMENSION) { "Cannot decode gallery image bounds" }
    }
    private fun decoded(file: File, options: BitmapFactory.Options): Bitmap {
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > 512) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("Cannot decode gallery image")
    }
    private fun inspect(file: File): GalleryImageInfo {
        val bounds = bounds(file)
        decoded(file, bounds).recycle()
        return GalleryImageInfo(bounds.outWidth, bounds.outHeight, bounds.outMimeType ?: error("Unknown image type"))
    }
    private fun thumbnail(original: File, target: File) {
        val bitmap = decoded(original, bounds(original))
        // Detach gainmap before drawing: both Canvas and JPEG compression must produce SDR.
        if (Build.VERSION.SDK_INT >= 34) bitmap.setGainmap(null)
        val longest = maxOf(bitmap.width, bitmap.height)
        val width = maxOf(1, bitmap.width * 512 / longest)
        val height = maxOf(1, bitmap.height * 512 / longest)
        val sdr = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(sdr); canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
            target.outputStream().use { require(sdr.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
        } finally { bitmap.recycle(); sdr.recycle() }
    }
}
