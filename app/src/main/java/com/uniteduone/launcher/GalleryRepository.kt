package com.uniteduone.launcher

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class GallerySnapshot(val catalog: GalleryCatalog?, val downloaded: List<GalleryLocal>, val refreshing: Boolean, val error: String?)
interface GalleryRepository {
    val state: StateFlow<GallerySnapshot>
    suspend fun refresh()
    suspend fun original(item: GalleryItem, onProgress: (Long) -> Unit): GalleryLocal
    suspend fun thumbnail(item: GalleryItem): File
    suspend fun removeDownloaded(sha256: String, protected: Set<String>): Boolean
    /** Explicit cleanup only; protected content and registered downloads are never swept. */
    suspend fun orphanedDownloads(): Set<String>
    /** Returns hashes successfully deleted, not remaining orphans. */
    suspend fun cleanupOrphans(protected: Set<String>): Set<String>
}
/** Inspector must prove a sampled decode succeeds as well as returning true encoded bounds/type. */
data class GalleryImageInfo(val width: Int, val height: Int, val mediaType: String)

/** All disk work runs on IO, including deferred store creation. Scope owns shared work, callers own waits. */
class SharedGalleryRepository(
    private val storeFactory: suspend () -> GalleryStore,
    private val transport: GalleryTransport,
    private val scope: CoroutineScope,
    private val inspectImage: (File) -> GalleryImageInfo,
    private val writeThumbnail: (File, File) -> Unit,
) : GalleryRepository {
    constructor(store: GalleryStore, transport: GalleryTransport, scope: CoroutineScope,
        inspectImage: (File) -> GalleryImageInfo, writeThumbnail: (File, File) -> Unit) :
        this({ store }, transport, scope, inspectImage, writeThumbnail)
    private val mutableState = MutableStateFlow(GallerySnapshot(null, emptyList(), false, null))
    override val state = mutableState.asStateFlow()
    private val stateLock = Mutex()
    private val tasksLock = Mutex()
    private class Shared(val task: Deferred<Any>, var waiters: Int = 0) {
        val progress = ConcurrentHashMap<Any, (Long) -> Unit>()
    }
    private val tasks = mutableMapOf<String, Shared>()
    private var cache: GalleryCatalogCache? = null
    private var refreshOwner: Any? = null
    private companion object { val activeParts = ConcurrentHashMap.newKeySet<File>() }
    private val initializationLock = Mutex()
    private fun initialize() = scope.async(Dispatchers.IO) {
        val store = storeFactory()
        stateLock.withLock {
            cache = store.readCatalogCache()
            mutableState.value = mutableState.value.copy(catalog = cache?.catalog, downloaded = store.downloaded())
        }
        store.sweepParts(activeParts.toSet())
        store.thumbs.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".part") && it.canonicalFile !in activeParts }.forEach { it.delete() }
        store
    }
    private var initialized: Deferred<GalleryStore>? = initialize()
    /** Only a failed shared initialization is evicted; cancellation of one waiter keeps its peers alive. */
    private suspend fun initializedStore(): GalleryStore {
        val pending = initializationLock.withLock { initialized ?: initialize().also { initialized = it } }
        try { return pending.await() }
        catch (failure: Exception) {
            if (pending.isCancelled) withContext(NonCancellable) { initializationLock.withLock {
                if (initialized === pending) initialized = null
            } }
            throw failure
        }
    }
    /** Registration and cancellation use one mutex; stale completions cannot remove a successor task. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> shared(key: String, progress: ((Long) -> Unit)? = null, work: suspend ((Long) -> Unit) -> T): T {
        val token = Any()
        val entry = tasksLock.withLock {
            val entry = tasks[key] ?: run {
                lateinit var created: Shared
                val task = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    work { count -> created.progress.values.toList().forEach { callback -> runCatching { callback(count) } }; Unit } as Any
                }
                created = Shared(task)
                tasks[key] = created
                created
            }
            entry.waiters++
            if (progress != null) entry.progress[token] = progress
            entry.task.start()
            entry
        }
        try { return entry.task.await() as T }
        finally {
            withContext(NonCancellable) {
                tasksLock.withLock {
                    entry.progress.remove(token)
                    entry.waiters--
                    if (entry.waiters == 0 && tasks[key] === entry) {
                        tasks.remove(key)
                        if (!entry.task.isCompleted) entry.task.cancel()
                    }
                }
            }
        }
    }
    override suspend fun refresh() {
        shared<Unit>("catalog") {
            val owner = Any()
            stateLock.withLock { refreshOwner = owner; mutableState.value = mutableState.value.copy(refreshing = true, error = null) }
            try {
                try { initializedStore() } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { throw java.io.IOException("Gallery storage initialization failed", failure) }
                val result = transport.catalog(cache?.etag)
                when (result) {
                    GalleryFetch.NotModified -> Unit
                    is GalleryFetch.Text -> {
                        val catalog = parseGalleryCatalog(result.json).getOrThrow()
                        stateLock.withLock {
                            currentCoroutineContext().ensureActive()
                            if (refreshOwner === owner && catalog.revision >= (cache?.catalog?.revision ?: -1)) {
                                initializedStore().writeCatalog(catalog, result.json, result.etag)
                                cache = GalleryCatalogCache(catalog, result.json, result.etag)
                                mutableState.value = mutableState.value.copy(catalog = catalog)
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { stateLock.withLock {
                if (refreshOwner === owner) mutableState.value = mutableState.value.copy(error = failure.message ?: "Gallery refresh failed")
            } }
            finally { withContext(NonCancellable) { stateLock.withLock {
                if (refreshOwner === owner) { refreshOwner = null; mutableState.value = mutableState.value.copy(refreshing = false) }
            } } }
        }
    }
    private fun validateItem(item: GalleryItem) {
        require(parseGalleryCatalog(serializeGalleryCatalog(GalleryCatalog(0, listOf(item)))).getOrThrow().items.single() == item)
    }
    private fun verifyBlob(file: File, blob: GalleryBlob, limit: Long) {
        require(blob.bytes in 1..limit && file.length() == blob.bytes) { "Gallery resource length mismatch" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        require(digest.digest().joinToString("") { "%02x".format(it) }.equals(blob.sha256, true)) { "Gallery resource hash mismatch" }
    }
    private fun image(file: File): GalleryImageInfo = inspectImage(file).also {
        require(it.width in 1..GALLERY_MAX_DIMENSION && it.height in 1..GALLERY_MAX_DIMENSION) { "Invalid gallery image dimensions" }
        require(it.mediaType in setOf("image/jpeg", "image/png", "image/webp")) { "Invalid gallery image media type" }
    }
    private fun verifyOriginal(file: File, item: GalleryItem) {
        verifyBlob(file, item.original.blob, GALLERY_MAX_ORIGINAL_BYTES)
        val info = image(file)
        require(info.width == item.original.width && info.height == item.original.height && info.mediaType == item.original.mediaType) { "Gallery original metadata mismatch" }
    }
    private fun part(dir: File): File {
        require(dir.mkdirs() || dir.isDirectory)
        return File(dir, "${UUID.randomUUID()}.part").also { activeParts.add(it.canonicalFile) }
    }
    private fun discard(file: File) { file.delete(); activeParts.remove(file.canonicalFile) }
    private suspend fun register(store: GalleryStore, item: GalleryItem, source: File): GalleryLocal = stateLock.withLock {
        verifyOriginal(source, item)
        currentCoroutineContext().ensureActive()
        val existing = store.resolve(GalleryAdoption(item.id, item.original.blob.sha256))
        val local = existing ?: run {
            val staging = part(store.root)
            try { source.copyTo(staging); currentCoroutineContext().ensureActive(); store.commitOriginal(item, staging) }
            finally { discard(staging) }
        }
        mutableState.value = mutableState.value.copy(downloaded = store.downloaded())
        local
    }
    override suspend fun original(item: GalleryItem, onProgress: (Long) -> Unit): GalleryLocal {
        validateItem(item)
        val store = initializedStore()
        return withContext(Dispatchers.IO) {
            val cached = stateLock.withLock { store.downloaded().firstOrNull { it.item.original.blob.sha256 == item.original.blob.sha256 } }
            val source = cached?.file ?: shared<File>("original:${item.original.blob.sha256}", onProgress) { notify ->
                val staging = part(store.root)
                try {
                    transport.download(item.original.blob, staging, notify)
                    verifyOriginal(staging, item)
                    currentCoroutineContext().ensureActive()
                    register(store, item, staging).file
                } finally { discard(staging) }
            }
            register(store, item, source)
        }
    }
    override suspend fun thumbnail(item: GalleryItem): File {
        validateItem(item)
        val store = initializedStore()
        return shared("thumbnail:${item.thumbnail.sha256}") {
            val target = File(store.thumbs, "${item.thumbnail.sha256}.thumbnail")
            if (target.isFile && runCatching { verifyBlob(target, item.thumbnail, GALLERY_MAX_THUMBNAIL_BYTES); image(target) }.isSuccess) return@shared target
            val generated = File(store.thumbs, "${item.original.blob.sha256}.sdr-thumbnail")
            val local = stateLock.withLock { store.resolve(GalleryAdoption(item.id, item.original.blob.sha256)) }
            if (local != null && generated.isFile && runCatching { require(generated.length() in 1..GALLERY_MAX_THUMBNAIL_BYTES); image(generated) }.isSuccess) return@shared generated
            val staging = part(store.thumbs)
            try {
                if (local != null) {
                    verifyOriginal(local.file, item); writeThumbnail(local.file, staging)
                    require(staging.length() in 1..GALLERY_MAX_THUMBNAIL_BYTES); image(staging)
                } else {
                    transport.download(item.thumbnail, staging) {}
                    verifyBlob(staging, item.thumbnail, GALLERY_MAX_THUMBNAIL_BYTES); image(staging)
                }
                currentCoroutineContext().ensureActive()
                val destination = if (local != null) generated else target
                if (!writeFileAtomically(destination) { out -> staging.inputStream().use { it.copyTo(out) } }) throw IOException("Gallery thumbnail replacement failed")
                destination
            } finally { discard(staging) }
        }
    }
    override suspend fun removeDownloaded(sha256: String, protected: Set<String>): Boolean {
        val store = initializedStore()
        return withContext(Dispatchers.IO) { stateLock.withLock {
            try { store.removeOriginal(sha256, protected) }
            finally { mutableState.value = mutableState.value.copy(downloaded = store.downloaded()) }
        } }
    }
    override suspend fun orphanedDownloads(): Set<String> = withContext(Dispatchers.IO) {
        val store = initializedStore(); stateLock.withLock { store.orphanedOriginals() }
    }
    override suspend fun cleanupOrphans(protected: Set<String>): Set<String> = withContext(Dispatchers.IO) {
        val store = initializedStore(); stateLock.withLock { store.cleanupOrphans(protected) }
    }
}
