package com.uniteduone.launcher

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface GalleryFetch {
    data object NotModified : GalleryFetch
    data class Text(val json: String, val etag: String?) : GalleryFetch
}
interface GalleryTransport {
    suspend fun catalog(etag: String?): GalleryFetch
    suspend fun download(blob: GalleryBlob, part: File, onProgress: (Long) -> Unit)
}
class GalleryHttpException(message: String) : IOException(message)

/** One deadline covers headers, every redirect and every read. File publication belongs to GalleryStore. */
class GalleryHttp(
    private val baseUrl: String = GALLERY_BASE_URL,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val allowLoopbackHttp: Boolean = false,
    private val beforeCompletion: () -> Unit = {},
    private val scheduleDeadline: (() -> Unit) -> (() -> Unit) = { expire ->
        val timer = deadlines.schedule(expire,120,TimeUnit.SECONDS)
        val cancel: () -> Unit = { timer.cancel(false); Unit }
        cancel
    },
) : GalleryTransport {
    private val root = URI(baseUrl).also {
        // Loopback is an explicit developer-test option; production catalog validation remains HTTPS-only.
        galleryUrl(if (allowLoopbackHttp && it.scheme == "http" && it.host == "127.0.0.1")
            "https://${it.rawAuthority}${it.rawPath}" else baseUrl, "index.json").getOrThrow()
    }
    private fun url(path: String): URL {
        val validatedBase = if (root.scheme == "http") "https://${root.rawAuthority}${root.rawPath}" else baseUrl
        galleryUrl(validatedBase, path).getOrThrow()
        return root.resolve(path).toURL()
    }
    override suspend fun catalog(etag: String?): GalleryFetch = request("index.json", etag) { connection, check ->
        if (connection.responseCode == 304) GalleryFetch.NotModified else {
            val out = ByteArrayOutputStream()
            read(connection, GALLERY_MAX_CATALOG_BYTES.toLong(), check) { bytes, count, _ -> out.write(bytes,0,count) }
            GalleryFetch.Text(out.toString("UTF-8"), connection.getHeaderField("ETag"))
        }
    }
    override suspend fun download(blob: GalleryBlob, part: File, onProgress: (Long) -> Unit) {
        require(blob.bytes in 1..GALLERY_MAX_ORIGINAL_BYTES)
        require(blob.sha256.matches(Regex("[a-fA-F0-9]{64}")))
        val owned = AtomicBoolean(false)
        fun discard() { if (owned.compareAndSet(true,false)) part.delete() }
        var received = false
        try {
            request(blob.path, null, ::discard) { connection, check ->
                Files.newOutputStream(part.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                    owned.set(true)
                    check()
                    val hash = MessageDigest.getInstance("SHA-256")
                    val total = read(connection, blob.bytes, check) { bytes, count, total ->
                        output.write(bytes,0,count); hash.update(bytes,0,count); onProgress(total)
                    }
                    check()
                    if (total != blob.bytes) throw GalleryHttpException("Resource length mismatch")
                    val actual = hash.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(blob.sha256,ignoreCase=true)) throw GalleryHttpException("Resource hash mismatch")
                }
            }
            received = true
        } finally { if (!received) discard() }
    }

    private fun read(connection: HttpURLConnection, limit: Long, check: () -> Unit, consume: (ByteArray,Int,Long) -> Unit): Long {
        var total = 0L
        connection.inputStream.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                check()
                val count = input.read(buffer)
                check()
                if (count < 0) break
                total += count
                if (total > limit) throw GalleryHttpException("Response exceeds byte limit")
                consume(buffer,count,total)
            }
        }
        return total
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun <T> request(path: String, etag: String?, onDiscard: () -> Unit = {}, consume: (HttpURLConnection, () -> Unit) -> T): T = suspendCancellableCoroutine { continuation ->
        val start = monotonicMillis()
        val active = AtomicReference<HttpURLConnection?>()
        val stopped = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        fun stop() { stopped.set(true); active.get()?.disconnect() }
        continuation.invokeOnCancellation { completed.set(true); stop() }
        val cancelDeadline = scheduleDeadline {
            stop()
            if (completed.compareAndSet(false,true)) continuation.resumeWithException(GalleryHttpException("Gallery request timed out"))
        }
        workers.execute {
            var handedOff = false
            try {
                fun check() {
                    if (stopped.get() || !continuation.isActive) throw GalleryHttpException("Gallery request cancelled")
                    val remaining = 120_000L - (monotonicMillis() - start)
                    if (remaining <= 0) throw GalleryHttpException("Gallery request timed out")
                    active.get()?.let {
                        it.connectTimeout = minOf(10_000L,remaining).toInt()
                        it.readTimeout = minOf(15_000L,remaining).toInt()
                    }
                }
                var current = url(path)
                var redirects = 0
                while (true) {
                    check()
                    val connection = connectionFactory(current)
                    active.set(connection)
                    try {
                        check()
                        connection.instanceFollowRedirects = false
                        etag?.let { connection.setRequestProperty("If-None-Match",it) }
                        val code = connection.responseCode
                        check()
                        if (code in setOf(301,302,303,307,308)) {
                            if (redirects++ >= 5) throw GalleryHttpException("Too many gallery redirects")
                            val location = connection.getHeaderField("Location") ?: throw GalleryHttpException("Redirect has no location")
                            // Validate raw reference before resolving: normalization must not hide traversal.
                            val reference = URI(location)
                            if (!reference.isAbsolute && reference.rawAuthority == null && !location.startsWith('/')) url(location)
                            val next = current.toURI().resolve(reference)
                            if (next.scheme != root.scheme || next.rawAuthority != root.rawAuthority || !next.rawPath.startsWith(root.rawPath))
                                throw GalleryHttpException("Redirect outside gallery root")
                            current = url(next.rawPath.removePrefix(root.rawPath) + (next.rawQuery?.let { "?$it" } ?: "") + (next.rawFragment?.let { "#$it" } ?: ""))
                            continue
                        }
                        if (code != 200 && !(code == 304 && etag != null)) throw GalleryHttpException("Gallery HTTP $code")
                        val result = consume(connection,::check)
                        beforeCompletion()
                        check()
                        if (completed.compareAndSet(false,true)) {
                            continuation.resume(result) { onDiscard() }
                            handedOff = true
                        }
                        break
                    } finally { connection.disconnect(); active.compareAndSet(connection,null) }
                }
            } catch (failure: Throwable) { if (completed.compareAndSet(false,true)) continuation.resumeWithException(failure) }
            finally {
                if (!handedOff) onDiscard()
                cancelDeadline()
            }
        }
    }
    private companion object {
        val workers = Executors.newCachedThreadPool { Thread(it,"gallery-http").apply { isDaemon=true } }
        val deadlines = Executors.newSingleThreadScheduledExecutor { Thread(it,"gallery-deadline").apply { isDaemon=true } }
    }
}
