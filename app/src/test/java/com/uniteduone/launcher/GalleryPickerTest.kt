package com.uniteduone.launcher

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.async
import java.io.File

class GalleryPickerTest {
    private fun item(id: String, hash: String = "a".repeat(64), kind: BuiltinKind = BuiltinKind.WALLPAPERS, packages: Set<String> = emptySet()) =
        GalleryItem(id, kind, mapOf("en" to id), 0, GalleryBlob("t.jpg", 1, hash), GalleryOriginal(GalleryBlob("o.jpg", 1, hash), 1, 1, "image/jpeg"), packages, "credit")
    @Test fun insertionAndReorderKeepIdentity() {
        assertEquals("gallery:b", resolveGalleryFocus("gallery:b", listOf("gallery:a", "gallery:b"), listOf("gallery:x", "gallery:b", "gallery:a")))
    }
    @Test fun removalFallsBackAtOldPositionAndEdges() {
        val old = listOf("gallery:a", "gallery:b", "gallery:c")
        assertEquals("gallery:b", resolveGalleryFocus("gallery:a", old, old.drop(1)))
        assertEquals("gallery:b", resolveGalleryFocus("gallery:c", old, old.dropLast(1)))
        assertEquals("gallery:refresh", resolveGalleryFocus("gallery:a", listOf("gallery:a"), emptyList()))
    }
    @Test fun downlistedAndUpdatedDownloadsRetainIndependentVersionKeys() {
        val old = item("a")
        val new = item("a", "b".repeat(64))
        val snapshot = GallerySnapshot(GalleryCatalog(2, listOf(new)), listOf(GalleryLocal(old, File("old")), GalleryLocal(item("removed"), File("removed"))), false, null)
        val rows = galleryRows(snapshot, BuiltinKind.WALLPAPERS, null)
        assertEquals(3, rows.size)
        assertTrue(rows.first().update)
        assertEquals("gallery:a", rows.first().key)
        assertEquals(3, rows.map { it.key }.toSet().size)
        assertTrue(rows.drop(1).all { it.retained && it.downloaded })
        assertEquals("a".repeat(64), rows[1].item.original.blob.sha256)
    }
    @Test fun appCardsFilterBothOnlineAndRetainedByExactPackage() {
        val unrelated = item("wrong", kind = BuiltinKind.CARDS, packages = setOf("com.app.other"))
        val match = item("right", kind = BuiltinKind.CARDS, packages = setOf("com.app.target"))
        val snapshot = GallerySnapshot(GalleryCatalog(1, listOf(unrelated)), listOf(GalleryLocal(match, File("old"))), false, null)
        assertEquals(listOf("right"), galleryRows(snapshot, BuiltinKind.CARDS, "com.app.target").map { it.item.id })
        assertTrue(galleryRows(snapshot, BuiltinKind.CARDS, "com.app").isEmpty())
    }
    @Test fun onlineCellsNeverAliasMineOrPhone() {
        assertEquals(PickerSection.MINE, pickerFocusGroup("mine:path"))
        assertEquals(PickerSection.MINE, pickerFocusGroup("add"))
        assertEquals(PickerSection.MINE, pickerFocusGroup("original"))
        assertEquals("add", resolveGalleryFocus("mine:last", listOf("add", "mine:last"), listOf("add")))
        val lines = pickerLines(2, 3, 4, 6)
        assertEquals(8, addCell(2, 6))
        assertEquals(9, cellOfImage(0, 2, 6))
        assertNull(imageOfCell(7, 2, 6))
        assertEquals(0, imageOfCell(9, 2, 6))
        assertEquals(10, landingCell(listOf("one", "two"), listOf("two"), 2, 6))
        assertEquals(PickerSection.GALLERY, (lines[lineOfCell(lines, 7)] as PickerLine.Cells).section)
        assertEquals(PickerSection.MINE, (lines[lineOfCell(lines, 8)] as PickerLine.Cells).section)
    }
    @Test fun previewFailureRetryNeverAdoptsAndOldProgressCannotChangeNewOperation() = kotlinx.coroutines.runBlocking {
        val row = GalleryRow("gallery:a", item("a"), false, false)
        var attempts = 0
        var picks = 0
        var staleProgress: ((Long) -> Unit)? = null
        val repository = object : GalleryRepository {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(GallerySnapshot(null, emptyList(), false, null))
            override suspend fun refresh() = Unit
            override suspend fun original(item: GalleryItem, onProgress: (Long) -> Unit): GalleryLocal {
                attempts++; staleProgress = onProgress; throw java.io.IOException("offline")
            }
            override suspend fun thumbnail(item: GalleryItem): File = error("unused")
            override suspend fun removeDownloaded(sha256: String, protected: Set<String>) = false
            override suspend fun orphanedDownloads() = emptySet<String>()
            override suspend fun cleanupOrphans(protected: Set<String>) = emptySet<String>()
        }
        val state = GalleryPickerState(repository, GalleryPickerActions({ _, _, _ -> picks++; {} }, {}, { _, _ -> }), this)
        state.preview(row); kotlinx.coroutines.yield()
        assertTrue(state.failed)
        state.retry(); kotlinx.coroutines.yield()
        assertEquals(2, attempts); assertEquals(0, picks)
        val old = staleProgress!!
        state.cancel(); state.pick(row)
        old(999); kotlinx.coroutines.yield()
        assertEquals(0L, state.progress)
        assertEquals(1, picks)
        state.cancel()
    }

    @Test fun disposingOldPickerCancelsOnlyItsOwnSelectionWaiter() = kotlinx.coroutines.runBlocking {
        val repository = object : GalleryRepository {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(GallerySnapshot(null, emptyList(), false, null))
            override suspend fun refresh() = Unit
            override suspend fun original(item: GalleryItem, onProgress: (Long) -> Unit): GalleryLocal = error("unused")
            override suspend fun thumbnail(item: GalleryItem): File = error("unused")
            override suspend fun removeDownloaded(sha256: String, protected: Set<String>) = false
            override suspend fun orphanedDownloads() = emptySet<String>()
            override suspend fun cleanupOrphans(protected: Set<String>) = emptySet<String>()
        }
        val cancelled = mutableListOf<String>()
        val actions = GalleryPickerActions({ item, _, _ -> { cancelled += item.id; Unit } }, { fail("Global cancellation must not be used") }, { _, _ -> })
        val old = GalleryPickerState(repository, actions, this)
        val current = GalleryPickerState(repository, actions, this)
        old.pick(GalleryRow("gallery:a", item("a"), false, false))
        current.pick(GalleryRow("gallery:b", item("b"), false, false))
        old.cancel(); old.cancel()
        assertEquals(listOf("a"), cancelled)
        assertEquals("b", current.active!!.item.id)
        current.cancel()
        assertEquals(listOf("a", "b"), cancelled)
    }

    @Test fun adoptionAndDeletionSerializeAcrossDifferentActivitySelections() = kotlinx.coroutines.runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val selection = GallerySelection()
        val token = selection.begin()
        val adoption = async(kotlinx.coroutines.Dispatchers.Default) {
            selection.commit(token) { entered.countDown(); finish.await(); true }
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { entered.await() }
        var deleted = false
        val deletion = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            GalleryMutationGate.locked { deleted = true }
        }
        assertFalse(deleted)
        finish.countDown()
        assertEquals(true, adoption.await()); deletion.await()
        assertTrue(deleted)
    }

    @Test fun failuresUseActionableReasonWithoutDisplayingRawPaths() {
        assertEquals(GalleryFailure.NETWORK, galleryFailure(java.net.SocketTimeoutException("timeout")))
        assertEquals(GalleryFailure.STORAGE, galleryFailure(java.io.IOException("No space left on device: /private/secret")))
        assertEquals(GalleryFailure.INVALID, galleryFailure(IllegalArgumentException("Gallery resource hash mismatch")))
    }

    @Test fun refreshSafetyBoundaryKeepsRowsAndManualRetryClearsStorageFailure() = kotlinx.coroutines.runBlocking {
        val cached = GallerySnapshot(GalleryCatalog(1, listOf(item("cached"))), emptyList(), false, null)
        var fail = true
        val repository = object : GalleryRepository {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(cached)
            override suspend fun refresh() { if (fail) throw java.io.IOException("No space left on device") }
            override suspend fun original(item: GalleryItem, onProgress: (Long) -> Unit): GalleryLocal = error("unused")
            override suspend fun thumbnail(item: GalleryItem): File = error("unused")
            override suspend fun removeDownloaded(sha256: String, protected: Set<String>) = false
            override suspend fun orphanedDownloads() = emptySet<String>()
            override suspend fun cleanupOrphans(protected: Set<String>) = emptySet<String>()
        }
        val state = GalleryPickerState(repository, GalleryPickerActions({ _, _, _ -> {} }, {}, { _, _ -> }), this)
        state.refresh()
        assertEquals(R.string.gallery_storage_failed, state.notice)
        assertEquals(listOf("cached"), galleryRows(repository.state.value, BuiltinKind.WALLPAPERS, null).map { it.item.id })
        fail = false; state.refresh()
        assertNull(state.notice)
        assertEquals(cached, repository.state.value)
    }
    @Test fun refreshSafetyBoundaryPropagatesCancellation() = kotlinx.coroutines.runBlocking {
        val cancelled = kotlinx.coroutines.CancellationException("cancelled")
        val repository = object : GalleryRepository {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(GallerySnapshot(null, emptyList(), false, null))
            override suspend fun refresh(): Unit = throw cancelled
            override suspend fun original(item: GalleryItem, onProgress: (Long) -> Unit): GalleryLocal = error("unused")
            override suspend fun thumbnail(item: GalleryItem): File = error("unused")
            override suspend fun removeDownloaded(sha256: String, protected: Set<String>) = false
            override suspend fun orphanedDownloads() = emptySet<String>()
            override suspend fun cleanupOrphans(protected: Set<String>) = emptySet<String>()
        }
        val state = GalleryPickerState(repository, GalleryPickerActions({ _, _, _ -> {} }, {}, { _, _ -> }), this)
        val failure = runCatching { state.refresh() }.exceptionOrNull()
        assertSame(cancelled, failure); assertNull(state.notice)
    }

    @Test fun cleanupReportsRemainingUnprotectedFilesRatherThanSuccessfullyRemovedFiles() = kotlinx.coroutines.runBlocking {
        var remaining = setOf("protected")
        val repository = object : GalleryRepository {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(GallerySnapshot(null, emptyList(), false, null))
            override suspend fun refresh() = Unit
            override suspend fun original(item: GalleryItem, onProgress: (Long) -> Unit): GalleryLocal = error("unused")
            override suspend fun thumbnail(item: GalleryItem): File = error("unused")
            override suspend fun removeDownloaded(sha256: String, protected: Set<String>) = false
            override suspend fun orphanedDownloads() = remaining
            override suspend fun cleanupOrphans(protected: Set<String>) = setOf("removed")
        }
        assertTrue(cleanupGalleryLeftovers(repository, setOf("protected")).isEmpty())
        remaining = setOf("protected", "undeleted")
        assertEquals(setOf("undeleted"), cleanupGalleryLeftovers(repository, setOf("protected")))
    }

}
