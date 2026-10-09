package com.uniteduone.launcher

import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** UI invalidation never waits for disk. Commits serialize; the check is their linearization point.
 * An already-started commit may finish, but cannot overwrite a newer commit. Callbacks recheck token.
 */
class GallerySelection {
    private val generation = AtomicLong()
    private val commits = GalleryMutationGate.mutex
    fun begin(): Long = generation.incrementAndGet()
    fun invalidate() { generation.incrementAndGet() }
    fun isCurrent(token: Long): Boolean = token == generation.get()
    suspend fun <T : Any> commit(token: Long, action: () -> T): T? = commits.withLock {
        if (isCurrent(token)) action() else null
    }
}

internal fun galleryAdoptionJson(a: GalleryAdoption): JSONObject = JSONObject().put("id", a.id).put("sha256", a.sha256)
internal fun galleryScreensaversJson(map: Map<String, GalleryAdoption>): JSONObject = JSONObject().apply {
    map.toSortedMap().forEach { (id, a) -> put(id, galleryAdoptionJson(a)) }
}
private fun adoption(value: Any?): GalleryAdoption? = runCatching {
    val o = value as? JSONObject ?: return null
    val id = o.get("id") as String; val hash = o.get("sha256") as String
    require(id.matches(Regex("[a-z0-9][a-z0-9-]{0,95}")))
    require(hash.matches(Regex("[a-fA-F0-9]{64}")))
    GalleryAdoption(id, hash.lowercase())
}.getOrNull()
internal fun parseGallerySettings(text: String): Pair<GalleryAdoption?, Map<String, GalleryAdoption>> {
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return null to emptyMap()
    val joined = json.optJSONObject("galleryScreensavers")
    val map = linkedMapOf<String, GalleryAdoption>()
    joined?.keys()?.forEach { id -> adoption(joined.opt(id))?.takeIf { it.id == id }?.let { map[id] = it } }
    return adoption(json.opt("galleryWallpaper")) to map
}
/** Resolve only adopted hashes, independent of the current online catalog. Blocking I/O caller. */
internal fun adoptedGalleryPlaylist(joined: Map<String, GalleryAdoption>, resolve: (GalleryAdoption) -> File?): List<File> =
    joined.toSortedMap().values.mapNotNull { runCatching { resolve(it) }.getOrNull() }

/** Process-wide adoption/delete gate; callers enter on IO and read fresh protection inside it. */
internal object GalleryMutationGate {
    val mutex = Mutex()
    suspend fun <T> locked(action: suspend () -> T): T = mutex.withLock { action() }
}
