package com.uniteduone.launcher

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class GalleryStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun item(id: String = "wall", content: String = "original") : GalleryItem {
        val blob = GalleryBlob("originals/image.jpg", content.toByteArray().size.toLong(), hash(content.toByteArray()))
        return GalleryItem(id, BuiltinKind.WALLPAPERS, mapOf("zh-CN" to "图", "zh-TW" to "圖", "en" to "Image"), 0,
            blob, GalleryOriginal(blob, 2, 2, "image/jpeg"), emptySet(), "artist")
    }
    private fun store() = GalleryStore(temp.newFolder(), temp.newFolder())
    private fun part(store: GalleryStore, content: String) = File.createTempFile("download", ".part", store.root).apply { writeText(content) }
    @Test fun schemaRoundTripAndCatalogEnvelope() {
        val s = store(); val c = GalleryCatalog(8, listOf(item()))
        val json = serializeGalleryCatalog(c)
        assertEquals(c, parseGalleryCatalog(json).getOrThrow())
        s.writeCatalog(c, json, "etag-8")
        assertEquals(c, GalleryStore(s.root, s.thumbs).readCatalog())
        assertEquals("etag-8", s.readCatalogCache()!!.etag)
        assertEquals(json, s.readCatalogCache()!!.json)
    }
    @Test fun keepsOldAdoptionAfterContentUpdateAndDelisting() {
        val s = store(); val old = item(); val newer = item(content = "updated")
        val local = s.commitOriginal(old, part(s, "original"))
        s.commitOriginal(newer, part(s, "updated"))
        s.writeCatalog(GalleryCatalog(9, emptyList()), serializeGalleryCatalog(GalleryCatalog(9, emptyList())), null)
        val restart = GalleryStore(s.root, s.thumbs)
        assertEquals(local, restart.resolve(GalleryAdoption(old.id, old.original.blob.sha256)))
        assertEquals(2, restart.downloaded().size)
    }
    @Test fun sharedHashKeepsBothIdsAndDeletionProtectsHash() {
        val s = store(); val a = item("first"); val b = item("second")
        s.commitOriginal(a, part(s, "original")); s.commitOriginal(b, part(s, "original"))
        assertEquals(2, s.downloaded().size)
        assertNotNull(s.resolve(GalleryAdoption(b.id, b.original.blob.sha256)))
        assertFalse(s.removeOriginal(a.original.blob.sha256, setOf(a.original.blob.sha256)))
        assertTrue(s.removeOriginal(a.original.blob.sha256, emptySet()))
        assertTrue(GalleryStore(s.root, s.thumbs).downloaded().isEmpty())
    }
    @Test fun corruptionRecoveryRetainsDelistedMetadataAndReportsError() {
        val s = store(); val a = item(); s.commitOriginal(a, part(s, "original"))
        File(s.root, "registry.json").writeText("bad")
        File(s.root, "registry.json.prev").delete()
        val errors = mutableListOf<String>()
        val restarted = GalleryStore(s.root, s.thumbs, onError = { errors.add(it) })
        assertNotNull(restarted.resolve(GalleryAdoption(a.id, a.original.blob.sha256)))
        assertTrue(errors.isNotEmpty()); assertTrue(File(s.root, "registry.json.bad").exists())
    }
    @Test fun cleanupSkipsActivePartsAndSelectedContentIsVerified() {
        val s = store(); val active = part(s, "active"); val stale = part(s, "stale")
        s.sweepParts(setOf(active)); assertTrue(active.exists()); assertFalse(stale.exists())
        val a = item(); val local = s.commitOriginal(a, part(s, "original")); local.file.writeText("tampered")
        assertNull(s.resolve(GalleryAdoption(a.id, a.original.blob.sha256)))
    }
    @Test fun writingFailuresPreserveCommittedOriginalRegistryAndCatalog() {
        val s = store(); val a = item(); val original = s.commitOriginal(a, part(s, "original"))
        val c = GalleryCatalog(1, listOf(a)); s.writeCatalog(c, serializeGalleryCatalog(c), "old")
        for (stage in listOf("original", "metadata", "registry", "catalog")) {
            val failing = GalleryStore(s.root, s.thumbs, beforeWrite = { if (it == stage) throw IOException("full") })
            val update = item(content = "updated")
            assertThrows(IOException::class.java) {
                if (stage == "catalog") failing.writeCatalog(GalleryCatalog(2, emptyList()), serializeGalleryCatalog(GalleryCatalog(2, emptyList())), "new")
                else failing.commitOriginal(update, part(s, "updated"))
            }
            val restart = GalleryStore(s.root, s.thumbs)
            assertEquals(original, restart.resolve(GalleryAdoption(a.id, a.original.blob.sha256)))
            assertEquals(1, restart.downloaded().size)
            assertEquals("old", restart.readCatalogCache()!!.etag)
        }
    }
    @Test fun startupRecoversCompleteOriginalAfterRegistryInterruption() {
        val s = store(); val a = item(); s.commitOriginal(a, part(s, "original"))
        // Simulate a crash after complete original + recovery metadata, before registry commit.
        File(s.root, "registry.json").writeText("{\"schemaVersion\":1,\"entries\":[]}")
        val restart = GalleryStore(s.root, s.thumbs)
        assertNotNull(restart.resolve(GalleryAdoption(a.id, a.original.blob.sha256)))
        assertEquals(1, restart.downloaded().size)
    }
    @Test fun binaryWriteInterruptionPreservesDestination() {
        val s = store(); val a = item(); val local = s.commitOriginal(a, part(s, "original"))
        assertThrows(IOException::class.java) {
            writeFileAtomically(local.file) { output -> output.write("half".toByteArray()); throw IOException("full") }
        }
        assertEquals("original", local.file.readText())
        assertNotNull(s.resolve(GalleryAdoption(a.id, a.original.blob.sha256)))
    }
    @Test fun mismatchedCatalogAndWrongContentDoNotCommit() {
        val s = store(); val a = item()
        assertThrows(IllegalArgumentException::class.java) { s.commitOriginal(a, part(s, "wrong")) }
        assertThrows(IllegalArgumentException::class.java) { s.writeCatalog(GalleryCatalog(2, emptyList()), serializeGalleryCatalog(GalleryCatalog(1, emptyList())), null) }
        assertTrue(s.downloaded().isEmpty()); assertNull(s.readCatalog())
    }
}
