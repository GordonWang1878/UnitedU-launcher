package com.uniteduone.launcher

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.launch
import java.io.File

class GallerySelectionTest {
    private val adopted = GalleryAdoption("sky", "a".repeat(64))
    @Test fun oldAndCorruptFieldsKeepOtherSettings() {
        assertNull(parseSettings("{\"cardsPerRow\":5}").galleryWallpaper)
        val s = parseSettings("{\"cardsPerRow\":5,\"galleryWallpaper\":{\"id\":\"sky\",\"sha256\":\"bad\"}}")
        assertEquals(5, s.cardsPerRow); assertNull(s.galleryWallpaper)
    }
    @Test fun adoptionRoundTripAndDefaults() {
        val s = Settings(wallpaperFile = "gallery:sky", galleryWallpaper = adopted, galleryScreensavers = mapOf("sky" to adopted))
        assertEquals(s, parseSettings(s.toJson()))
        val reset = restoredDefaults(s, 1)
        assertNull(reset.galleryWallpaper); assertEquals(s.galleryScreensavers, reset.galleryScreensavers)
        assertEquals(ImageChoice.Gallery("sky"), decodeImageChoice(s.wallpaperFile))
        assertEquals(ImageChoice.Builtin("sky"), decodeImageChoice("builtin:sky"))
        assertEquals(ImageChoice.Mine("sky.jpg"), decodeImageChoice("sky.jpg"))
    }
    @Test fun pendingAThenBCannotApplyAAndFailureDoesNotCommit() = kotlinx.coroutines.runBlocking {
        val gate = GallerySelection(); var selection = "old"
        val a = gate.begin(); val b = gate.begin()
        assertNull(gate.commit(a) { selection = "a" })
        gate.commit(b) { selection = "b" }; assertEquals("b", selection)
        gate.invalidate(); assertNull(gate.commit(b) { selection = "late" })
        assertEquals("b", selection)
    }
    @Test fun adoptedQueueAndRenderInputArePinned() {
        val map = mapOf("sky" to adopted)
        assertEquals(listOf(File("a")), adoptedGalleryPlaylist(map) { if (it == adopted) File("a") else null })
        assertEquals(emptyList<File>(), adoptedGalleryPlaylist(map) { null })
        val s = Settings(wallpaperFile = "gallery:sky", galleryWallpaper = adopted)
        assertNotEquals(wallpaperSpecOf(s), wallpaperSpecOf(s.copy(galleryWallpaper = adopted.copy(sha256 = "b".repeat(64)))))
    }
    @Test fun delayedDownloadAndSerializedDiskCommitKeepNewestAdoption() = kotlinx.coroutines.runBlocking {
        val gate = GallerySelection()
        val pendingA = kotlinx.coroutines.CompletableDeferred<GalleryAdoption>()
        var disk = Settings().toJson()
        val a = gate.begin()
        val downloadA = launch {
            val original = pendingA.await()
            gate.commit(a) { disk = parseSettings(disk).copy(wallpaperFile = "gallery:${original.id}", galleryWallpaper = original).toJson() }
        }
        val b = gate.begin()
        val latest = adopted.copy(sha256 = "b".repeat(64))
        gate.commit(b) { disk = parseSettings(disk).copy(wallpaperFile = "gallery:${latest.id}", galleryWallpaper = latest).toJson() }
        pendingA.complete(adopted); downloadA.join()
        assertEquals(latest, parseSettings(disk).galleryWallpaper)
        val c = gate.begin(); gate.invalidate()
        assertNull(gate.commit(c) { disk = Settings().toJson() })
        assertEquals(latest, parseSettings(disk).galleryWallpaper)
    }
    @Test fun begunCommitSerializesBeforeNewIntentWithoutBlockingInvalidation() = kotlinx.coroutines.runBlocking {
        val gate = GallerySelection()
        val started = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        var disk = "old"
        val a = gate.begin()
        val writer = launch(kotlinx.coroutines.Dispatchers.Default) {
            gate.commit(a) { started.countDown(); check(finish.await(5, java.util.concurrent.TimeUnit.SECONDS)); disk = "a" }
        }
        check(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val b = gate.begin() // Must return while A still holds disk-commit gate.
        val newest = launch(kotlinx.coroutines.Dispatchers.Default) { gate.commit(b) { disk = "b" } }
        finish.countDown(); writer.join(); newest.join()
        assertEquals("b", disk)
        assertFalse(gate.isCurrent(a))
    }
}
