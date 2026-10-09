package com.uniteduone.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
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

/** 海报目标:高 = 卡高(220 px),宽最多 2 × 高。 */
internal const val POSTER_MAX_PIXELS = 4096L * 4096

/** 宽高比在 [0.5, 2.5] 内、总像素不超过 4096×4096 才接受;否则当作没图(防 120000×220 这类解出巨大位图)。 */
internal fun posterAcceptable(srcW: Int, srcH: Int): Boolean {
    if (srcW <= 0 || srcH <= 0) return false
    val aspect = srcW.toDouble() / srcH
    return aspect in 0.5..2.5 && srcW.toLong() * srcH <= POSTER_MAX_PIXELS
}

/** 粗缩的 `inSampleSize`:取 2 的幂,同时约束高与宽——再翻倍时只要高仍 ≥ [targetH] 或宽仍 ≥ [maxW] 就翻。 */
internal fun posterSampleSize(srcW: Int, srcH: Int, targetH: Int, maxW: Int = 2 * targetH): Int {
    if (srcW <= 0 || srcH <= 0 || targetH <= 0 || maxW <= 0) return 1
    var s = 1
    while (srcH / (s * 2) >= targetH || srcW / (s * 2) >= maxW) {
        s *= 2
        if (s >= 1 shl 20) break
    }
    return s
}

/** 采样后的位图再精确缩到的尺寸:高 ≤ [targetH]、宽 ≤ [maxW],保持比例,只缩不放。 */
internal fun posterScaledSize(w: Int, h: Int, targetH: Int, maxW: Int = 2 * targetH): Pair<Int, Int> {
    if (w <= 0 || h <= 0) return 1 to 1
    val f = minOf(1.0, targetH.toDouble() / h, maxW.toDouble() / w)
    if (f >= 1.0) return w to h
    return Math.round(w * f).toInt().coerceIn(1, maxW) to Math.round(h * f).toInt().coerceIn(1, targetH)
}

internal const val POSTER_TIMEOUT_MS = 5_000
internal const val POSTER_MAX_BYTES = 2 * 1024 * 1024
internal const val POSTER_RETRY_MS = 60_000L
internal const val POSTER_CACHE_BYTES = 16 * 1024 * 1024

/** 失败过的海报多久后才再试(没失败过 = 现在就试;时钟倒退 = 试)。 */
internal fun posterRetryDue(failedAt: Long?, now: Long): Boolean =
    failedAt == null || now < failedAt || now - failedAt >= POSTER_RETRY_MS

/**
 * 读完整个流;超过 [maxBytes] → null(不让一张海报吃掉几十 MB)。[sizeHint] 已知(1..maxBytes)时按它一次性开好缓冲、
 * 不再拷贝;[deadlineNanos](System.nanoTime 口径,0 = 不限)到了 → null,是整体期限而不只是单次读取。
 */
internal fun readCapped(input: InputStream, maxBytes: Int, sizeHint: Long = -1, deadlineNanos: Long = 0): ByteArray? {
    fun late() = deadlineNanos != 0L && System.nanoTime() - deadlineNanos > 0
    if (sizeHint in 1..maxBytes.toLong()) {
        val arr = ByteArray(sizeHint.toInt())
        var total = 0
        while (total < arr.size) {
            if (late()) return null
            val n = input.read(arr, total, arr.size - total)
            if (n < 0) return if (total == arr.size) arr else arr.copyOf(total)
            total += n
        }
        return if (input.read() >= 0) null else arr   // 比声明的长 → 不信
    }
    val out = ByteArrayOutputStream(16 * 1024)
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        if (late()) return null
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** 下载 [url]:连接 / 每次读取各 [timeoutMs],整体也以 [timeoutMs] 为期限;非 2xx、声明或实际超过 [maxBytes]、任何异常 → null。只用 java.net,JVM 可测。 */
internal fun fetchBytes(url: URL, timeoutMs: Int, maxBytes: Int): ByteArray? {
    val conn = (runCatching { url.openConnection() }.getOrNull() as? HttpURLConnection) ?: return null
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    // 硬性总期限:到点从看门狗线程断开,卡在 connect / 读响应头 / 读正文的调用随之抛异常返回(DNS 仍是尽力而为)
    val kill = watchdog.schedule({ runCatching { conn.disconnect() } }, timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
    return try {
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.useCaches = false
        if (conn.responseCode !in 200..299) null
        else if (conn.contentLengthLong > maxBytes) null
        else conn.inputStream.use { readCapped(it, maxBytes, conn.contentLengthLong, deadline) }
    } catch (e: Exception) {
        null
    } finally {
        kill.cancel(false)
        conn.disconnect()
    }
}

private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "poster-watchdog").apply { isDaemon = true }
}

/** 打开 + 读取都在 [timeoutMs] 内:到点从看门狗线程关掉流,让卡在 provider 管道上的 read 抛异常返回,IO 槽位随之释放。 */
internal fun readWithWatchdog(timeoutMs: Int, open: () -> InputStream?, maxBytes: Int): ByteArray? {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    var stream: InputStream? = null
    var expired = false
    val lock = Any()
    val task = watchdog.schedule({
        synchronized(lock) { expired = true; runCatching { stream?.close() } }
    }, timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
    return try {
        val st = open() ?: return null
        synchronized(lock) { stream = st; if (expired) { runCatching { st.close() }; return null } }
        st.use { readCapped(it, maxBytes, -1, deadline) }
    } catch (e: Exception) {
        null
    } finally {
        task.cancel(false)
    }
}

private val resolverPool = java.util.concurrent.Executors.newCachedThreadPool { r ->
    Thread(r, "poster-resolver").apply { isDaemon = true }
}.asCoroutineDispatcher()
private val resolverScope = CoroutineScope(SupervisorJob() + resolverPool)

/**
 * resolver 读取放在有界 IO 池**之外**的守护线程上,协程只等 [timeoutMs]:卡在 openInputStream()(binder 调用不返回)
 * 的 provider 不占 IO 槽位;迟到的工作线程在 open 返回后由 [readWithWatchdog] 的过期标记自己关流。
 *
 * 在途计数在 job 的 `invokeOnCompletion` 里减(恰好一次):job 若在开跑前就被取消(调用方刚 async 完即被取消),
 * 体内的 finally 根本不会执行,写在 finally 里会永久漏掉一个名额。
 *
 * 取舍(有界线程 vs 可用性):open() 永远不返回的 provider 会一直占着它的名额,直到 open() 返回为止——线程拿不回来,
 * 名额也就不还。同时有 [RESOLVER_MAX_WORKERS](16)个这样卡死的 provider,resolver 来源的海报就整体失效(一律当没图),
 * 直到它们返回或进程重启;https 海报不受影响。宁可失效也不让卡死的 binder 调用把线程无限堆下去。
 */
internal suspend fun readResolverBounded(
    timeoutMs: Int, open: () -> InputStream?, maxBytes: Int, scope: CoroutineScope = resolverScope,
): ByteArray? {
    if (resolverWorkers.incrementAndGet() > RESOLVER_MAX_WORKERS) { resolverWorkers.decrementAndGet(); return null }
    val job = scope.async { readWithWatchdog(timeoutMs, open, maxBytes) }
    job.invokeOnCompletion { resolverWorkers.decrementAndGet() }
    try {
        return withTimeoutOrNull(timeoutMs.toLong() + 200) { job.await() }
    } finally {
        job.cancel()   // 超时或调用方被取消:不再等;已完成的 job 上是空操作
    }
}

/** 同时在途的 resolver 工作线程上限(卡死的 provider 不能让线程无限增长);满了直接当没图,不派发。 */
internal const val RESOLVER_MAX_WORKERS = 16
private val resolverWorkers = java.util.concurrent.atomic.AtomicInteger()

/** 测试用:当前在途的 resolver 工作数。 */
internal fun resolverWorkersInFlight(): Int = resolverWorkers.get()

/**
 * 取一张海报并记账:[produce] 返回 null → 记入 [failed] 并回调 [onFail];非 null → 清除失败记录。
 * 取消(CancellationException)原样抛出,**不**记失败(调用方只是滑出了范围)。
 */
internal suspend fun <T : Any> loadTracked(
    failed: MutableMap<String, Long>, uri: String, now: () -> Long, onFail: () -> Unit, produce: suspend () -> T?,
): T? {
    val r = produce()
    if (r != null) failed.remove(uri) else { failed[uri] = now(); onFail() }
    return r
}

/**
 * 海报内存缓存:按 uri,`LruCache` 16 MB(按 `allocationByteCount`);失败的 uri 记时间,[POSTER_RETRY_MS] 内不再试。
 * 并发:https 下载与解码共用最多 4 路 IO;resolver 读取在独立守护线程上,同时在途 ≤ [RESOLVER_MAX_WORKERS](16),超出当没图。首页只对焦点行 ± 1 调 [load](见 HomeChannels.kt 的 `loadsPosters`)。
 */
object PosterCache {
    private val mem = object : LruCache<String, Bitmap>(POSTER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }
    private val failed = ConcurrentHashMap<String, Long>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(4)

    private fun pruneFailed(now: Long) { failed.entries.removeAll { posterRetryDue(it.value, now) } }

    /** 日志只写 scheme + host,不带路径 / 查询串(可能含令牌)。 */
    private fun logId(uri: String): String = runCatching {
        val u = Uri.parse(uri); "${u.scheme}://${u.host ?: u.authority.orEmpty()}"
    }.getOrDefault("?")

    fun peek(uri: String): Bitmap? = mem.get(uri)

    /** 读 + 解码到 [heightPx] 高;失败返回 null(调用方画深色底 + 标题)。 */
    suspend fun load(ctx: Context, uri: String, heightPx: Int): Bitmap? {
        mem.get(uri)?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (!posterRetryDue(failed[uri], now)) return null
        pruneFailed(now)
        val bmp = loadTracked(failed, uri, { SystemClock.elapsedRealtime() },
            { Log.i("UnitedU", "海报读不到(${POSTER_RETRY_MS / 1000} s 内不再试): ${logId(uri)}") }   // e2e j_channels 认这一行
        ) {
            val bytes = if (posterSourceOf(uri) == PosterSource.RESOLVER)
                readResolverBounded(POSTER_TIMEOUT_MS, { ctx.contentResolver.openInputStream(Uri.parse(uri)) }, POSTER_MAX_BYTES)
            else withContext(io) { runCatching { bytesOf(ctx, uri) }.getOrNull() }
            if (bytes == null) null else withContext(io) { runCatching { decode(bytes, heightPx) }.getOrNull() }
        }
        if (bmp != null) mem.put(uri, bmp)
        return bmp
    }

    private fun bytesOf(ctx: Context, uri: String): ByteArray? = when (posterSourceOf(uri)) {
        PosterSource.RESOLVER -> null   // 走 readResolverBounded
        PosterSource.HTTPS -> fetchBytes(URL(uri), POSTER_TIMEOUT_MS, POSTER_MAX_BYTES)
        PosterSource.NONE -> null
    }

    private fun decode(bytes: ByteArray?, heightPx: Int): Bitmap? {
        if (bytes == null) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!posterAcceptable(bounds.outWidth, bounds.outHeight)) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = posterSampleSize(bounds.outWidth, bounds.outHeight, heightPx) }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val (w, h) = posterScaledSize(raw.width, raw.height, heightPx)
        if (w == raw.width && h == raw.height) return raw
        val scaled = Bitmap.createScaledBitmap(raw, w, h, true)
        if (scaled !== raw) raw.recycle()
        return scaled
    }
}
