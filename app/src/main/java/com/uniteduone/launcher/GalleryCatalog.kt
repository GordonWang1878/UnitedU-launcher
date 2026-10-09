package com.uniteduone.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class GalleryBlob(val path: String, val bytes: Long, val sha256: String)
data class GalleryOriginal(val blob: GalleryBlob, val width: Int, val height: Int, val mediaType: String)
data class GalleryItem(val id: String, val kind: BuiltinKind, val names: Map<String, String>, val order: Int, val thumbnail: GalleryBlob, val original: GalleryOriginal, val packages: Set<String>, val credit: String)
data class GalleryCatalog(val revision: Long, val items: List<GalleryItem>)

private fun JSONObject.string(key: String): String = (get(key) as? String
    ?: error("$key must be a string")).also { require(it.isNotBlank()) { "$key must not be blank" } }
private fun JSONObject.integer(key: String): Long {
    val value = get(key)
    require(value is Int || value is Long) { "$key must be an integer" }
    return (value as Number).toLong()
}
private fun JSONObject.intValue(key: String): Int = integer(key).also {
    require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
}.toInt()
private fun JSONObject.objectValue(key: String) = get(key) as? JSONObject ?: error("$key must be an object")
private fun JSONObject.arrayValue(key: String) = get(key) as? JSONArray ?: error("$key must be an array")
private fun parseBlob(json: JSONObject, limit: Long): GalleryBlob {
    val path = json.string("path")
    galleryUrl(GALLERY_BASE_URL, path).getOrThrow()
    val bytes = json.integer("bytes").also { require(it in 1..limit) }
    val hash = json.string("sha256").also { require(it.matches(Regex("[a-fA-F0-9]{64}"))) }
    return GalleryBlob(path, bytes, hash.lowercase())
}

/** Reject an invalid item atomically with the entire catalog, preserving the caller's previous catalog. */
fun parseGalleryCatalog(json: String): Result<GalleryCatalog> = runCatching {
    require(json.toByteArray(Charsets.UTF_8).size <= GALLERY_MAX_CATALOG_BYTES)
    val tokener = JSONTokener(json)
    val root = tokener.nextValue() as? JSONObject ?: error("catalog must be an object")
    require(tokener.nextClean() == '\u0000') { "trailing content" }
    require(root.integer("schemaVersion") == 1L)
    val revision = root.integer("revision").also { require(it >= 0) }
    val entries = root.arrayValue("items")
    require(entries.length() <= GALLERY_MAX_ITEMS)
    val ids = mutableSetOf<String>()
    val items = (0 until entries.length()).map { index ->
        val entry = entries.get(index) as? JSONObject ?: error("item must be an object")
        val id = entry.string("id")
        require(id.matches(Regex("[a-z0-9][a-z0-9-]{0,95}")) && ids.add(id))
        val kindName = entry.string("kind")
        val kind = BuiltinKind.entries.firstOrNull { it.dir == kindName } ?: error("unknown kind")
        val namesJson = entry.objectValue("names")
        val names = listOf("zh-CN", "zh-TW", "en").associateWith { namesJson.string(it) }
        val originalJson = entry.objectValue("original")
        val original = GalleryOriginal(parseBlob(originalJson, GALLERY_MAX_ORIGINAL_BYTES),
            originalJson.intValue("width").also { require(it in 1..GALLERY_MAX_DIMENSION) },
            originalJson.intValue("height").also { require(it in 1..GALLERY_MAX_DIMENSION) },
            originalJson.string("mediaType").also { require(it in setOf("image/jpeg", "image/png", "image/webp")) })
        val packages = if (kind == BuiltinKind.CARDS || entry.has("packages")) {
            val values = entry.arrayValue("packages")
            (0 until values.length()).map { i ->
                (values.get(i) as? String ?: error("package must be a string")).also {
                    require(it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")))
                }
            }.toSet().also { if (kind == BuiltinKind.CARDS) require(it.isNotEmpty()) }
        } else emptySet()
        GalleryItem(id, kind, names, entry.intValue("order"), parseBlob(entry.objectValue("thumbnail"), GALLERY_MAX_THUMBNAIL_BYTES), original, packages, entry.string("credit"))
    }
    GalleryCatalog(revision, items)
}

fun galleryItems(catalog: GalleryCatalog, kind: BuiltinKind, packageName: String?): List<GalleryItem> =
    catalog.items.filter { it.kind == kind && (kind != BuiltinKind.CARDS || packageName in it.packages) }
        .sortedWith(compareBy<GalleryItem> { it.order }.thenBy { it.id })
