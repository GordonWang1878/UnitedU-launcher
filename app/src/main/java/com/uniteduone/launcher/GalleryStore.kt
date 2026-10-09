package com.uniteduone.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class GalleryAdoption(val id: String, val sha256: String)
data class GalleryLocal(val item: GalleryItem, val file: File)
data class GalleryCatalogCache(val catalog: GalleryCatalog, val json: String, val etag: String?)

/** Blocking disk I/O only; callers dispatch off the UI thread. Adoption belongs in Settings. */
class GalleryStore(val root: File, val thumbs: File,
    private val onError: (String) -> Unit = {},
    private val beforeWrite: (String) -> Unit = {},
    private val deleteOriginal: (File) -> Boolean = { it.delete() }) {
    private class Stores {
        val registry = LockedFile("registry.json")
        val catalog = LockedFile("catalog.json")
        val metadata = ConcurrentHashMap<String, LockedFile>()
    }
    companion object { private val stores = ConcurrentHashMap<String, Stores>() }
    private val files = stores.computeIfAbsent(root.canonicalPath) { Stores() }
    private val metadataDir = File(root, "metadata")
    init { require(root.mkdirs() || root.isDirectory); require(metadataDir.mkdirs() || metadataDir.isDirectory) }

    private fun checkedHash(hash: String): String = hash.lowercase().also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    private fun original(hash: String) = File(root, "${checkedHash(hash)}.original")
    private fun metadata(item: GalleryItem): LockedFile {
        // Both components have already passed the shared schema parser; ID never becomes an original path.
        val key = "${item.id}-${checkedHash(item.original.blob.sha256)}.json"
        return files.metadata.computeIfAbsent(key) { LockedFile(key) }
    }
    private fun encode(items: List<GalleryItem>): String = JSONObject().put("schemaVersion", 1)
        .put("entries", JSONArray(items.map { JSONObject(serializeGalleryCatalog(GalleryCatalog(0, listOf(it)))) })).toString()
    private fun decode(text: String): List<GalleryItem> {
        val json = JSONObject(text); require(json.getInt("schemaVersion") == 1)
        val entries = json.getJSONArray("entries")
        return (0 until entries.length()).map { parseGalleryCatalog(entries.getJSONObject(it).toString()).getOrThrow().items.single() }
            .also { require(it.map { i -> i.id to i.original.blob.sha256 }.distinct().size == it.size) }
    }
    private fun write(store: LockedFile, dir: File, text: String, stage: String) {
        beforeWrite(stage)
        if (!store.write(dir, text)) throw IOException("Gallery $stage replacement failed")
    }
    private fun valid(item: GalleryItem, file: File): Boolean {
        val blob = item.original.blob
        if (!file.isFile || file.length() != blob.bytes || blob.bytes !in 1..GALLERY_MAX_ORIGINAL_BYTES) return false
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(8192); var n = input.read(buffer)
                while (n >= 0) { digest.update(buffer, 0, n); n = input.read(buffer) } }
            digest.digest().joinToString("") { "%02x".format(it) } == blob.sha256
        }.getOrDefault(false)
    }
    private fun loadRegistry(): List<GalleryItem> {
        val loaded = files.registry.load(root, maxBytes = 64L * 1024 * 1024, log = onError, parse = ::decode)
        val registered = if (loaded is LockedFile.Load.Ok) loaded.value else emptyList()
        if (loaded == LockedFile.Load.Corrupt) onError("Gallery registry is corrupt; recovering verified originals")
        val recovered = metadataDir.listFiles().orEmpty().filter { it.name.endsWith(".json") || it.name.endsWith(".json.prev") }
            .map { it.name.removeSuffix(".prev") }.distinct().flatMap { name ->
                val store = files.metadata.computeIfAbsent(name) { LockedFile(name) }
                when (val result = store.load(metadataDir, maxBytes = GALLERY_MAX_CATALOG_BYTES.toLong(), log = onError) { parseGalleryCatalog(it).getOrThrow().items.single() }) {
                    is LockedFile.Load.Ok -> listOf(result.value)
                    else -> emptyList()
                }
            }.filter { valid(it, original(it.original.blob.sha256)) }
        val combined = (registered + recovered).distinctBy { it.id to it.original.blob.sha256 }
        if (combined != registered || loaded is LockedFile.Load.Ok && loaded.restored)
            write(files.registry, root, encode(combined), "registry")
        return combined
    }
    fun readCatalog(): GalleryCatalog? = readCatalogCache()?.catalog
    fun readCatalogCache(): GalleryCatalogCache? = files.catalog.locked {
        when (val loaded = files.catalog.load(root, maxBytes = 6L * GALLERY_MAX_CATALOG_BYTES, log = onError) { text ->
            val envelope = JSONObject(text); require(envelope.getInt("schemaVersion") == 1)
            val json = envelope.getString("json")
            GalleryCatalogCache(parseGalleryCatalog(json).getOrThrow(), json, if (envelope.isNull("etag")) null else envelope.getString("etag"))
        }) {
            is LockedFile.Load.Ok -> loaded.value.also { if (loaded.restored) writeCatalog(it.catalog, it.json, it.etag) }
            LockedFile.Load.Corrupt -> { onError("Gallery catalog is corrupt"); null }
            else -> null
        }
    }
    fun writeCatalog(catalog: GalleryCatalog, json: String, etag: String?) = files.catalog.locked {
        require(parseGalleryCatalog(json).getOrThrow() == catalog) { "Catalog and JSON disagree" }
        val envelope = JSONObject().put("schemaVersion", 1).put("json", json).put("etag", etag ?: JSONObject.NULL)
        write(files.catalog, root, envelope.toString(), "catalog")
    }
    fun downloaded(): List<GalleryLocal> = files.registry.locked {
        loadRegistry().mapNotNull { item -> val file = original(item.original.blob.sha256); if (valid(item, file)) GalleryLocal(item, file) else null }
    }
    fun resolve(adoption: GalleryAdoption): GalleryLocal? = files.registry.locked {
        val hash = checkedHash(adoption.sha256)
        loadRegistry().firstOrNull { it.id == adoption.id && it.original.blob.sha256 == hash }?.let {
            val file = original(hash); if (valid(it, file)) GalleryLocal(it, file) else null
        }
    }
    fun commitOriginal(item: GalleryItem, part: File): GalleryLocal = files.registry.locked {
        require(parseGalleryCatalog(serializeGalleryCatalog(GalleryCatalog(0, listOf(item)))).getOrThrow().items.single() == item)
        require(part.canonicalFile.parentFile == root.canonicalFile && part.name.endsWith(".part")) { "Part must be exclusive and in gallery directory" }
        require(valid(item, part)) { "Original length or SHA-256 mismatch" }
        val items = loadRegistry()
        val dst = original(item.original.blob.sha256)
        val existed = dst.exists()
        val meta = metadata(item)
        val metaExisted = meta.file(metadataDir).exists()
        try {
            if (!valid(item, dst)) {
                beforeWrite("original")
                if (!writeFileAtomically(dst, verify = { valid(item, it) }) { out -> part.inputStream().use { it.copyTo(out) } }) throw IOException("Gallery original replacement failed")
            }
            if (!metaExisted) write(meta, metadataDir, serializeGalleryCatalog(GalleryCatalog(0, listOf(item))), "metadata")
            val updated = items.filterNot { it.id == item.id && it.original.blob.sha256 == item.original.blob.sha256 } + item
            write(files.registry, root, encode(updated), "registry")
            GalleryLocal(item, dst)
        } catch (failure: Throwable) {
            if (!metaExisted) { meta.file(metadataDir).delete(); meta.prev(metadataDir).delete() }
            if (!existed) dst.delete()
            throw failure
        } finally { part.delete() }
    }
    fun removeOriginal(sha256: String, protected: Set<String>): Boolean = files.registry.locked {
        val hash = checkedHash(sha256)
        if (protected.any { checkedHash(it) == hash }) return@locked false
        val entries = loadRegistry()
        val removed = entries.filter { it.original.blob.sha256 == hash }
        // A previous removal may have committed membership deletion but failed before deleting bytes.
        // The validated hash path remains retryable, with the same adoption protection as normal removal.
        if (removed.isEmpty()) return@locked removeOriginalFile(hash)
        // Removing recovery metadata first prevents a removed download being resurrected after corruption.
        val backups = removed.map { metadata(it) to serializeGalleryCatalog(GalleryCatalog(0, listOf(it))) }
        try {
            backups.forEach { (store, _) ->
                if (store.file(metadataDir).exists() && !store.file(metadataDir).delete()) throw IOException("Cannot remove gallery metadata")
                if (store.prev(metadataDir).exists() && !store.prev(metadataDir).delete()) throw IOException("Cannot remove gallery metadata backup")
            }
            write(files.registry, root, encode(entries.filterNot { it.original.blob.sha256 == hash }), "registry")
        } catch (failure: Throwable) {
            backups.forEach { (store, text) -> if (!store.write(metadataDir, text)) throw IOException("Cannot restore gallery metadata", failure) }
            throw failure
        }
        removeOriginalFile(hash)
        true
    }
    private fun removeOriginalFile(hash: String): Boolean {
        val dst = original(hash)
        if (!dst.exists()) return false
        beforeWrite("original-delete")
        if (!deleteOriginal(dst)) throw IOException("Cannot delete gallery original")
        return true
    }
    /** Enumerates retryable bytes left after membership removal; never deletes automatically. */
    fun orphanedOriginals(): Set<String> = files.registry.locked {
        val registered = loadRegistry().map { it.original.blob.sha256 }.toSet()
        root.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.original")) }
            .map { it.name.removeSuffix(".original") }.filter { it !in registered }.toSet()
    }
    fun cleanupOrphans(protected: Set<String>): Set<String> = files.registry.locked {
        val keep = protected.map(::checkedHash).toSet()
        orphanedOriginals().filter { it !in keep }.filter { removeOriginalFile(it) }.toSet()
    }
    fun sweepParts(active: Set<File>) = files.registry.locked {
        val protected = active.map { it.canonicalFile }.toSet()
        root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".part") && it.canonicalFile !in protected }
            .forEach { if (!it.delete()) onError("Cannot remove stale gallery part: ${it.name}") }
    }
}
