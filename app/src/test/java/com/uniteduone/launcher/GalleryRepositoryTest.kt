package com.uniteduone.launcher

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class GalleryRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val bytes = "image".toByteArray()
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private fun item(id: String = "wall") = GalleryItem(id, setOf(BuiltinKind.WALLPAPERS),
        mapOf("zh-CN" to "图", "zh-TW" to "圖", "en" to "Image"), 0,
        GalleryBlob("thumbs/image.jpg", bytes.size.toLong(), hash(bytes)),
        GalleryOriginal(GalleryBlob("originals/image.jpg", bytes.size.toLong(), hash(bytes)), 2, 2, "image/jpeg"), emptySet(), "artist")
    private fun store() = GalleryStore(temp.newFolder(), temp.newFolder())
    private class FakeTransport(val bytes: ByteArray) : GalleryTransport {
        val catalogCalls = AtomicInteger(); val downloadCalls = AtomicInteger()
        val catalogEntered = CompletableDeferred<Unit>(); val downloadEntered = CompletableDeferred<Unit>()
        var catalogGate: CompletableDeferred<Unit>? = null; var downloadGate: CompletableDeferred<Unit>? = null
        var fetch: GalleryFetch = GalleryFetch.NotModified
        var failure: Exception? = null
        var seenEtag: String? = null
        @Volatile var emitProgress: ((Long) -> Unit)? = null
        override suspend fun catalog(etag: String?): GalleryFetch {
            catalogCalls.incrementAndGet(); seenEtag = etag; catalogEntered.complete(Unit)
            catalogGate?.await(); failure?.let { throw it }; return fetch
        }
        override suspend fun download(blob: GalleryBlob, part: File, onProgress: (Long) -> Unit) {
            downloadCalls.incrementAndGet(); emitProgress = onProgress; downloadEntered.complete(Unit); downloadGate?.await()
            failure?.let { throw it }; part.writeBytes(bytes); onProgress(bytes.size.toLong())
        }
    }
    private fun repo(s: GalleryStore, t: FakeTransport, scope: CoroutineScope,
        inspect: (File) -> GalleryImageInfo = { GalleryImageInfo(2, 2, "image/jpeg") }) =
        SharedGalleryRepository(s, t, scope, inspect, { _, target -> target.writeBytes(bytes) })
    private suspend fun eventually(check: () -> Boolean) { withTimeout(5000) { while (!check()) yield() } }
    @Test fun addingUsageReusesBytesAndRecoveryPreservesBothUses() = runBlocking {
        val s = store(); val t = FakeTransport(bytes)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = repo(s, t, scope)
            val old = r.original(item()) {}
            val shared = item().copy(usages = setOf(BuiltinKind.WALLPAPERS, BuiltinKind.SCREENSAVERS))
            assertEquals(old.file, r.original(shared) {}.file)
            assertEquals(shared.usages, r.original(item()) {}.item.usages)
            assertEquals(1, t.downloadCalls.get())
            File(s.root, "registry.json").writeText("broken")
            assertEquals(shared.usages, s.resolve(GalleryAdoption(item().id, hash(bytes)))!!.item.usages)
            File(s.root, "registry.json.prev").writeText("broken")
            val recovered = s.resolve(GalleryAdoption(item().id, hash(bytes)))!!
            assertEquals(shared.usages, recovered.item.usages)
            assertFalse(s.removeOriginal(hash(bytes), setOf(hash(bytes))))
            assertArrayEquals(bytes, recovered.file.readBytes())
        } finally { scope.cancel() }
    }
    @Test fun cacheAppearsBeforeNetworkAnd304PreservesIt() = runBlocking {
        val s = store(); val c = GalleryCatalog(9, listOf(item())); s.writeCatalog(c, serializeGalleryCatalog(c), "cached")
        val t = FakeTransport(bytes).apply { catalogGate = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = repo(s, t, scope); val refresh = async { r.refresh() }; t.catalogEntered.await()
            assertEquals(c, r.state.value.catalog); assertTrue(r.state.value.refreshing); assertEquals("cached", t.seenEtag)
            t.catalogGate!!.complete(Unit); refresh.await(); assertEquals(c, r.state.value.catalog)
            assertFalse(r.state.value.refreshing)
        } finally { scope.cancel() }
    }
    @Test fun invalidAndOlderCatalogCannotReplaceCache() = runBlocking {
        val s = store(); val c = GalleryCatalog(9, listOf(item())); s.writeCatalog(c, serializeGalleryCatalog(c), "cached")
        val t = FakeTransport(bytes); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = repo(s, t, scope); t.fetch = GalleryFetch.Text("broken", "bad"); r.refresh()
            assertEquals(c, r.state.value.catalog); assertNotNull(r.state.value.error)
            t.fetch = GalleryFetch.Text(serializeGalleryCatalog(GalleryCatalog(8, emptyList())), "old"); r.refresh()
            assertEquals(c, r.state.value.catalog); assertEquals("cached", s.readCatalogCache()!!.etag)
        } finally { scope.cancel() }
    }
    @Test fun simultaneousRefreshUsesOneRequest() = runBlocking {
        val t = FakeTransport(bytes).apply { catalogGate = CompletableDeferred() }; val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = repo(store(), t, scope); val first = async(start = CoroutineStart.UNDISPATCHED) { r.refresh() }
            t.catalogEntered.await(); val second = async(start = CoroutineStart.UNDISPATCHED) { r.refresh() }
            t.catalogGate!!.complete(Unit); first.await(); second.await(); assertEquals(1, t.catalogCalls.get())
        } finally { scope.cancel() }
    }
    @Test fun sameHashUsesOneDownloadButRegistersEveryIdAndCancellationIsIndependent() = runBlocking {
        val s = store(); val t = FakeTransport(bytes).apply { downloadGate = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = repo(s, t, scope)
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { r.original(item()) {} }
            t.downloadEntered.await()
            val joined = CompletableDeferred<Unit>()
            val b = async(start = CoroutineStart.UNDISPATCHED) { r.original(item("second")) { joined.complete(Unit) } }
            eventually { t.emitProgress?.invoke(0); joined.isCompleted }
            cancelled.cancelAndJoin(); t.downloadGate!!.complete(Unit); assertEquals("second", b.await().item.id)
            assertEquals(1, t.downloadCalls.get()); assertNotNull(s.resolve(GalleryAdoption("second", hash(bytes))))
            assertEquals(2, s.downloaded().size)
            assertEquals(2, r.state.value.downloaded.size)
        } finally { scope.cancel() }
    }
    @Test fun lastConsumerCancelsAndRetryStartsFreshDownload() = runBlocking {
        val s = store(); val t = FakeTransport(bytes).apply { downloadGate = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = repo(s, t, scope); val first = async { r.original(item()) {} }; t.downloadEntered.await()
            first.cancelAndJoin(); t.downloadGate!!.complete(Unit)
            r.original(item()) {}; assertEquals(2, t.downloadCalls.get()); assertTrue(s.root.listFiles()!!.none { it.name.endsWith(".part") })
        } finally { scope.cancel() }
    }
    @Test fun cancelledThumbnailEffectReleasesLastWaiterAndRetryStartsFresh() = runBlocking {
        val s = store(); val t = FakeTransport(bytes).apply { downloadGate = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = repo(s, t, scope)
            val mountedEffect = async { r.thumbnail(item()) }
            t.downloadEntered.await()
            mountedEffect.cancelAndJoin()
            t.downloadGate!!.complete(Unit)
            assertTrue(r.thumbnail(item()).isFile)
            assertEquals(2, t.downloadCalls.get())
            eventually { s.thumbs.listFiles().orEmpty().none { it.name.endsWith(".part") } }
            assertTrue(s.downloaded().isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun decodedFormatOrDimensionsRejectBeforeCommit() = runBlocking {
        val s = store(); val t = FakeTransport(bytes); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            for (info in listOf(GalleryImageInfo(3, 2, "image/jpeg"), GalleryImageInfo(2, 2, "image/png"))) {
                val r = repo(s, t, scope) { info }
                assertTrue(runCatching { r.original(item()) {} }.isFailure); assertTrue(s.downloaded().isEmpty())
            }
        } finally { scope.cancel() }
    }
    @Test fun offlineDelistedOriginalRegeneratesMissingThumbnailAndRetainsVersion() = runBlocking {
        val s = store(); val a = item(); val part = File(s.root, "seed.part").apply { writeBytes(bytes) }; s.commitOriginal(a, part)
        val c = GalleryCatalog(12, emptyList()); s.writeCatalog(c, serializeGalleryCatalog(c), null)
        val t = FakeTransport(bytes).apply { failure = java.io.IOException("offline") }; val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = repo(s, t, scope); r.refresh(); assertEquals(1, r.state.value.downloaded.size)
            assertTrue(r.thumbnail(a).isFile); assertEquals(0, t.downloadCalls.get())
            assertNotNull(s.resolve(GalleryAdoption(a.id, a.original.blob.sha256)))
        } finally { scope.cancel() }
    }
    @Test fun thumbnailChecksActualBoundsAndHash() = runBlocking {
        val s = store(); val t = FakeTransport(bytes); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = repo(s, t, scope) { GalleryImageInfo(8193, 2, "image/jpeg") }
            assertTrue(runCatching { r.thumbnail(item()) }.isFailure)
            assertTrue(s.thumbs.listFiles().orEmpty().none { !it.name.endsWith(".part") })
        } finally { scope.cancel() }
    }
    @Test fun firstOfflineRefreshKeepsEmptyUsableSnapshot() = runBlocking {
        val t = FakeTransport(bytes).apply { failure = java.io.IOException("offline") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = repo(store(), t, scope); r.refresh()
            assertNull(r.state.value.catalog); assertTrue(r.state.value.downloaded.isEmpty())
            assertEquals("offline", r.state.value.error); assertFalse(r.state.value.refreshing)
        } finally { scope.cancel() }
    }
    @Test fun forgedTransportBytesAreRejectedBeforeCommit() = runBlocking {
        val s = store(); val t = FakeTransport("other".toByteArray()); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = repo(s, t, scope)
            assertTrue(runCatching { r.original(item()) {} }.isFailure)
            assertTrue(runCatching { r.thumbnail(item()) }.isFailure)
            assertTrue(s.downloaded().isEmpty()); assertTrue(s.root.listFiles()!!.none { it.name.endsWith(".part") })
        } finally { scope.cancel() }
    }
    @Test fun refreshAndDownloadCannotOverwriteEachOthersSnapshot() = runBlocking {
        val s = store(); val c = GalleryCatalog(20, listOf(item("second")))
        val t = FakeTransport(bytes).apply { catalogGate = CompletableDeferred(); fetch = GalleryFetch.Text(serializeGalleryCatalog(c), "new") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = repo(s, t, scope); val refresh = async { r.refresh() }; t.catalogEntered.await()
            r.original(item()) {}; assertTrue(r.state.value.refreshing); assertEquals(1, r.state.value.downloaded.size)
            t.catalogGate!!.complete(Unit); refresh.await()
            assertEquals(c, r.state.value.catalog); assertEquals(1, r.state.value.downloaded.size)
        } finally { scope.cancel() }
    }
    @Test fun cancelledRefreshFinallyCannotClearSuccessorRefreshingState() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>(); val firstFinally = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>(); val secondEntered = CompletableDeferred<Unit>(); val releaseNew = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val transport = object : GalleryTransport {
            override suspend fun catalog(etag: String?): GalleryFetch {
                if (calls.incrementAndGet() == 1) {
                    firstEntered.complete(Unit)
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { firstFinally.complete(Unit); releaseOld.await() } }
                }
                secondEntered.complete(Unit); releaseNew.await(); return GalleryFetch.NotModified
            }
            override suspend fun download(blob: GalleryBlob, part: File, onProgress: (Long) -> Unit) = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = SharedGalleryRepository(store(), transport, scope, { GalleryImageInfo(2, 2, "image/jpeg") }, { _, _ -> })
            val first = async { r.refresh() }; firstEntered.await(); first.cancelAndJoin(); firstFinally.await()
            val second = async { r.refresh() }; secondEntered.await()
            releaseOld.complete(Unit)
            eventually { scope.coroutineContext[Job]!!.children.count() == 1 }
            assertTrue(r.state.value.refreshing)
            releaseNew.complete(Unit); second.await(); assertFalse(r.state.value.refreshing)
        } finally { releaseOld.complete(Unit); releaseNew.complete(Unit); scope.cancel() }
    }
    @Test fun orphanCleanupIsExplicitAndProtectsSelectedHash() = runBlocking {
        val s = store(); val orphan = hash(bytes); val file = File(s.root, "$orphan.original").apply { writeBytes(bytes) }
        val t = FakeTransport(bytes); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = repo(s, t, scope); r.refresh(); assertTrue(file.exists())
            assertEquals(setOf(orphan), r.orphanedDownloads()); assertTrue(r.cleanupOrphans(setOf(orphan)).isEmpty())
            assertTrue(file.exists()); assertEquals(setOf(orphan), r.cleanupOrphans(emptySet())); assertFalse(file.exists())
        } finally { scope.cancel() }
    }

    @Test fun cancelledSelectionReleasesOriginalWaiterAndNeverAdopts() = runBlocking {
        val storage = store(); val transport = FakeTransport(bytes).apply { downloadGate = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val selection = GallerySelection(); var settings = Settings()
        try {
            val repository = repo(storage, transport, scope)
            val token = selection.begin()
            val job = launch {
                val downloaded = repository.original(item()) {}
                selection.commit(token) { settings = settings.copy(wallpaperFile = "gallery:wall", galleryWallpaper = GalleryAdoption(downloaded.item.id, downloaded.item.original.blob.sha256)) }
            }
            transport.downloadEntered.await()
            selection.invalidate(); job.cancelAndJoin()
            eventually { storage.root.listFiles().orEmpty().none { it.name.endsWith(".part") } }
            assertNull(settings.galleryWallpaper)
            assertTrue(settings.galleryScreensavers.isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun previewFailureAndCatalogChangesDoNotAdoptOrChangePinnedVersion() = runBlocking {
        val storage = store(); val transport = FakeTransport(bytes)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var settings = Settings()
        try {
            val repository = repo(storage, transport, scope)
            transport.failure = java.io.IOException("offline")
            assertTrue(runCatching { repository.original(item()) {} }.isFailure)
            assertNull(settings.galleryWallpaper); assertTrue(settings.galleryScreensavers.isEmpty())
            transport.failure = null
            val preview = repository.original(item()) {}
            assertTrue(preview.file.isFile); assertTrue(settings.galleryScreensavers.isEmpty())
            val adopted = GalleryAdoption(preview.item.id, preview.item.original.blob.sha256)
            settings = settings.copy(wallpaperFile = "gallery:wall", galleryWallpaper = adopted, galleryScreensavers = mapOf("wall" to adopted))
            storage.writeCatalog(GalleryCatalog(99, emptyList()), serializeGalleryCatalog(GalleryCatalog(99, emptyList())), null)
            assertEquals(preview.file, storage.resolve(settings.galleryWallpaper!!)?.file)
            preview.file.writeText("corrupt")
            assertNull(storage.resolve(adopted))
            assertTrue(adoptedGalleryPlaylist(settings.galleryScreensavers) { storage.resolve(it)?.file }.isEmpty())
            assertEquals(adopted, settings.galleryWallpaper)
        } finally { scope.cancel() }
    }
    @Test fun failedInitializationPublishesErrorAndManualRefreshRetriesWithoutPoisoningRepository() = runBlocking {
        val storage = store()
        val catalog = GalleryCatalog(7, listOf(item()))
        storage.writeCatalog(catalog, serializeGalleryCatalog(catalog), "cached")
        val attempts = AtomicInteger()
        val failed = CompletableDeferred<Unit>()
        val transport = FakeTransport(bytes).apply { catalogGate = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = SharedGalleryRepository({
                if (attempts.incrementAndGet() == 1) { failed.complete(Unit); throw java.io.IOException("No space left on device") }
                storage
            }, transport, scope, { GalleryImageInfo(2, 2, "image/jpeg") }, { _, target -> target.writeBytes(bytes) })
            failed.await()
            repository.refresh()
            assertNotNull(repository.state.value.error)
            assertFalse(repository.state.value.refreshing)
            assertEquals(0, transport.catalogCalls.get())
            val retry = async { repository.refresh() }
            transport.catalogEntered.await()
            assertEquals(catalog, repository.state.value.catalog)
            assertTrue(repository.state.value.refreshing)
            assertNull(repository.state.value.error)
            transport.catalogGate!!.complete(Unit); retry.await()
            assertEquals(2, attempts.get())
            assertEquals(catalog, repository.state.value.catalog)
            assertFalse(repository.state.value.refreshing)
        } finally { scope.cancel() }
    }

    @Test fun cancellingInitializationWaiterDoesNotPoisonSharedStorage() = runBlocking {
        val storage = store()
        val attempts = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = SharedGalleryRepository({ attempts.incrementAndGet(); entered.complete(Unit); gate.await(); storage },
                FakeTransport(bytes), scope, { GalleryImageInfo(2, 2, "image/jpeg") }, { _, target -> target.writeBytes(bytes) })
            entered.await()
            val waiter = launch { repository.refresh() }
            eventually { repository.state.value.refreshing }
            waiter.cancelAndJoin()
            eventually { !repository.state.value.refreshing }
            assertNull(repository.state.value.error)
            gate.complete(Unit); repository.refresh()
            assertEquals(1, attempts.get())
            assertNull(repository.state.value.error)
        } finally { scope.cancel() }
    }

}
