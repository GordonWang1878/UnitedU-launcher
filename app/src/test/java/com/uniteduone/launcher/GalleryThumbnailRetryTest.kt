package com.uniteduone.launcher

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class GalleryThumbnailRetryTest {
    @get:Rule val temp = TemporaryFolder()
    private suspend fun eventually(check: () -> Boolean) {
        withTimeout(5000) { while (!check()) { Snapshot.sendApplyNotifications(); yield() } }
    }
    @Test fun mountedVisibleEffectRetriesAfter304AndExplicitRetryButNotWhenHidden() = runBlocking {
        val bytes = "thumbnail".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val item = GalleryItem("wall", BuiltinKind.WALLPAPERS, mapOf("en" to "Wall", "zh-CN" to "图", "zh-TW" to "圖"), 0,
            GalleryBlob("thumbs/a.jpg", bytes.size.toLong(), hash),
            GalleryOriginal(GalleryBlob("originals/a.jpg", bytes.size.toLong(), hash), 2, 2, "image/jpeg"), emptySet(), "credit")
        val catalog = GalleryCatalog(1, listOf(item))
        val store = GalleryStore(temp.newFolder(), temp.newFolder())
        store.writeCatalog(catalog, serializeGalleryCatalog(catalog), "etag")
        val offline = java.util.concurrent.atomic.AtomicBoolean(true)
        val attempts = AtomicInteger()
        val refreshes = AtomicInteger()
        val transport = object : GalleryTransport {
            override suspend fun catalog(etag: String?): GalleryFetch {
                assertEquals("etag", etag); refreshes.incrementAndGet(); return GalleryFetch.NotModified
            }
            override suspend fun download(blob: GalleryBlob, part: File, onProgress: (Long) -> Unit) {
                assertEquals("thumbs/a.jpg", blob.path) // Never request originals.
                attempts.incrementAndGet()
                if (offline.get()) throw IOException("offline")
                part.writeBytes(bytes)
            }
        }
        val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = SharedGalleryRepository(store, transport, repositoryScope, { GalleryImageInfo(2, 2, "image/jpeg") }, { _, _ -> error("No originals") })
        val page = GalleryPickerState(repository, GalleryPickerActions({ _, _, _ -> error("No adoption") }, {}, { _, _ -> }), pageScope)
        val visible = mutableStateOf(true)
        val image = java.util.concurrent.atomic.AtomicReference<File?>(null)
        val shownNow = java.util.concurrent.atomic.AtomicBoolean(true)
        // The mounted producer observes the same visible + page epoch keys used by ThumbCard.
        val mounted = pageScope.launch {
            snapshotFlow { visible.value to page.thumbnailGeneration }.collectLatest { (shown, _) ->
                shownNow.set(shown)
                if (shown) {
                    val result = try { repository.thumbnail(item) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                    image.set(result); page.thumbnailResult("gallery:wall", result == null)
                }
            }
        }
        try {
            eventually { "gallery:wall" in page.thumbnailFailures }
            assertNull(image.get()); assertEquals(1, attempts.get())
            offline.set(false); page.refresh()
            eventually { image.get() != null && page.thumbnailFailures.isEmpty() }
            assertEquals(1, refreshes.get()); assertEquals(2, attempts.get())
            assertEquals(catalog, repository.state.value.catalog)
            assertTrue(store.downloaded().isEmpty())
            image.set(null); visible.value = false
            eventually { !shownNow.get() }
            val before = attempts.get(); page.retryThumbnails()
            Snapshot.sendApplyNotifications(); yield()
            assertEquals(before, attempts.get())
            // Same mount, same item: retry key changes while offscreen cause no fetch; returning visible uses cache.
            visible.value = true
            eventually { image.get() != null }
            assertEquals(before, attempts.get())
            page.thumbnailResult("gallery:wall", true)
            page.retryThumbnails()
            eventually { page.thumbnailFailures.isEmpty() }
            assertEquals(1, refreshes.get()) // Explicit Retry does not fetch the manifest.
        } finally { mounted.cancelAndJoin(); pageScope.cancel(); repositoryScope.cancel() }
    }

    @Test fun cancelledRefreshCannotAdvancePageThumbnailEpoch() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val repository = object : GalleryRepository {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(GallerySnapshot(null, emptyList(), false, null))
            override suspend fun refresh() { entered.complete(Unit); awaitCancellation() }
            override suspend fun thumbnail(item: GalleryItem): File = error("unused")
            override suspend fun original(item: GalleryItem, onProgress: (Long) -> Unit): GalleryLocal = error("unused")
            override suspend fun removeDownloaded(sha256: String, protected: Set<String>) = false
            override suspend fun orphanedDownloads() = emptySet<String>()
            override suspend fun cleanupOrphans(protected: Set<String>) = emptySet<String>()
        }
        val page = GalleryPickerState(repository, GalleryPickerActions({ _, _, _ -> {} }, {}, { _, _ -> }), this)
        val refresh = launch { page.refresh() }
        entered.await(); refresh.cancelAndJoin()
        assertEquals(0L, page.thumbnailGeneration)
        assertNull(page.notice)
    }
}
