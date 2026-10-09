package com.uniteduone.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * R164 频道海报(spec `2026-10-08-channel-rows-design.md` §3.3)。纯函数在上半(JVM 单测 PosterLoaderTest),
 * Android 侧的缓存与解码是 [PosterCache]。**不做磁盘缓存**;这是 UnitedU 除「检查更新」「上传资料」之外唯一会联网的地方
 * (只在频道行的海报是 https 地址时,README 同步写明)。
 */

internal enum class PosterSource { RESOLVER, HTTPS, NONE }

/** `content://` / `android.resource://` 走 ContentResolver;`https://` 走网络;其它(含明文 `http://`、`file://`)当作没图。 */
internal fun posterSourceOf(uri: String): PosterSource = when (uri.substringBefore(':', "").lowercase()) {
    "content", "android.resource" -> PosterSource.RESOLVER
    "https" -> PosterSource.HTTPS
    else -> PosterSource.NONE
}

/** 粗缩的 `inSampleSize`:取 2 的幂,采样后高度仍 ≥ [targetH](之后再精确缩到 [targetH])。 */
internal fun posterSampleSize(srcW: Int, srcH: Int, targetH: Int): Int {
    if (srcW <= 0 || srcH <= 0 || targetH <= 0) return 1
    var s = 1
    while (srcH / (s * 2) >= targetH) s *= 2
    return s
}

internal const val POSTER_TIMEOUT_MS = 5_000
internal const val POSTER_MAX_BYTES = 8 * 1024 * 1024
internal const val POSTER_RETRY_MS = 60_000L
internal const val POSTER_CACHE_BYTES = 16 * 1024 * 1024

/** 失败过的海报多久后才再试(没失败过 = 现在就试;时钟倒退 = 试)。 */
internal fun posterRetryDue(failedAt: Long?, now: Long): Boolean =
    failedAt == null || now < failedAt || now - failedAt >= POSTER_RETRY_MS

/** 读完整个流;超过 [maxBytes] → null(不让一张海报吃掉几十 MB)。 */
internal fun readCapped(input: InputStream, maxBytes: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** 下载 [url]:连接 / 每次读取各 [timeoutMs];非 2xx、声明或实际超过 [maxBytes]、任何异常 → null。只用 java.net,JVM 可测。 */
internal fun fetchBytes(url: URL, timeoutMs: Int, maxBytes: Int): ByteArray? {
    val conn = (runCatching { url.openConnection() }.getOrNull() as? HttpURLConnection) ?: return null
    return try {
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.useCaches = false
        if (conn.responseCode !in 200..299) null
        else if (conn.contentLengthLong > maxBytes) null
        else conn.inputStream.use { readCapped(it, maxBytes) }
    } catch (e: Exception) {
        null
    } finally {
        conn.disconnect()
    }
}

/**
 * 海报内存缓存:按 uri,`LruCache` 16 MB(按 `allocationByteCount`);失败的 uri 记时间,[POSTER_RETRY_MS] 内不再试。
 * 并发最多 4 路(一行 12 张同时进范围时不把 IO 池占满)。首页只对焦点行 ± 1 调 [load](见 HomeChannels.kt 的 `loadsPosters`)。
 */
object PosterCache {
    private val mem = object : LruCache<String, Bitmap>(POSTER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }
    private val failed = ConcurrentHashMap<String, Long>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(4)

    fun peek(uri: String): Bitmap? = mem.get(uri)

    /** 读 + 解码到 [heightPx] 高;失败返回 null(调用方画深色底 + 标题)。 */
    suspend fun load(ctx: Context, uri: String, heightPx: Int): Bitmap? {
        mem.get(uri)?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (!posterRetryDue(failed[uri], now)) return null
        val bmp = withContext(io) { runCatching { decode(bytesOf(ctx, uri), heightPx) }.getOrNull() }
        if (bmp != null) {
            mem.put(uri, bmp)
            failed.remove(uri)
        } else {
            failed[uri] = SystemClock.elapsedRealtime()
            Log.i("UnitedU", "海报读不到(${POSTER_RETRY_MS / 1000} s 内不再试): $uri")   // e2e j_channels 认这一行
        }
        return bmp
    }

    private fun bytesOf(ctx: Context, uri: String): ByteArray? = when (posterSourceOf(uri)) {
        PosterSource.RESOLVER -> ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { readCapped(it, POSTER_MAX_BYTES) }
        PosterSource.HTTPS -> fetchBytes(URL(uri), POSTER_TIMEOUT_MS, POSTER_MAX_BYTES)
        PosterSource.NONE -> null
    }

    private fun decode(bytes: ByteArray?, heightPx: Int): Bitmap? {
        if (bytes == null) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = posterSampleSize(bounds.outWidth, bounds.outHeight, heightPx) }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (raw.height <= heightPx) return raw
        val w = (raw.width.toLong() * heightPx / raw.height).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(raw, w, heightPx, true)
        if (scaled !== raw) raw.recycle()
        return scaled
    }
}
