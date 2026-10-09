package com.uniteduone.launcher

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.*
import java.io.File

internal fun pickerFocusGroup(key: String): PickerSection = when (key.substringBefore(":")) { "builtin" -> PickerSection.BUILTIN; "gallery" -> PickerSection.GALLERY; else -> PickerSection.MINE }
internal fun galleryFocusKey(id: String) = "gallery:$id"
internal fun resolveGalleryFocus(previous: String, previousKeys: List<String>, newKeys: List<String>): String {
    if (previous in newKeys) return previous
    if (newKeys.isEmpty()) return "gallery:refresh"
    return newKeys[previousKeys.indexOf(previous).coerceAtLeast(0).coerceAtMost(newKeys.lastIndex)]
}
/** Store returns hashes it deleted; the menu only reports files that still need cleanup. */
internal suspend fun cleanupGalleryLeftovers(repository: GalleryRepository, protected: Set<String>): Set<String> {
    repository.cleanupOrphans(protected)
    return repository.orphanedDownloads() - protected
}

internal enum class GalleryFailure { NETWORK, STORAGE, INVALID, UNKNOWN }
internal fun galleryFailure(error: Throwable): GalleryFailure {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    val detail = causes.joinToString(" ") { it.message.orEmpty() }.lowercase()
    return when {
        "no space" in detail || "enospc" in detail || listOf("save", "stage", "replace", "registry", "write", "storage", "directory").any { it in detail } -> GalleryFailure.STORAGE
        causes.any { it is java.net.SocketTimeoutException || it is java.net.UnknownHostException || it is java.net.ConnectException } -> GalleryFailure.NETWORK
        causes.any { it is IllegalArgumentException } || listOf("decode", "corrupt", "hash", "mismatch", "dimensions", "media type").any { it in detail } -> GalleryFailure.INVALID
        causes.any { it is java.io.IOException } -> GalleryFailure.NETWORK
        else -> GalleryFailure.UNKNOWN
    }
}
internal data class GalleryRow(val key: String, val item: GalleryItem, val downloaded: Boolean, val retained: Boolean, val update: Boolean = false)
internal fun galleryRows(snapshot: GallerySnapshot, kind: BuiltinKind, packageName: String?): List<GalleryRow> {
    val current = snapshot.catalog?.let { galleryItems(it, kind, packageName) }.orEmpty()
    val locals = snapshot.downloaded.filter { it.item.kind == kind && (kind != BuiltinKind.CARDS || packageName in it.item.packages) }
    return current.map { item -> GalleryRow(galleryFocusKey(item.id), item, locals.any { it.item.id == item.id && it.item.original.blob.sha256 == item.original.blob.sha256 }, false, locals.any { it.item.id == item.id && it.item.original.blob.sha256 != item.original.blob.sha256 }) } +
        locals.filter { local -> current.none { it.id == local.item.id && it.original.blob.sha256 == local.item.original.blob.sha256 } }
            .sortedWith(compareBy<GalleryLocal> { it.item.order }.thenBy { it.item.id }.thenBy { it.item.original.blob.sha256 })
            .map { GalleryRow("gallery:${it.item.id}:${it.item.original.blob.sha256}", it.item, true, true) }
}
data class GalleryPickerActions(
    val pick: (GalleryItem, (Long) -> Unit, (Result<Unit>) -> Unit) -> (() -> Unit),
    val cancel: () -> Unit,
    val removeScreensaver: (String, (Result<Unit>) -> Unit) -> Unit,
)

/** Page-owned state: refresh is keyed by mount, preview owns its own repository waiter. */
internal class GalleryPickerState(val repository: GalleryRepository, val actions: GalleryPickerActions, val scope: CoroutineScope) {
    var menu by mutableStateOf<GalleryRow?>(null)
    var refreshMenu by mutableStateOf(false)
    var active by mutableStateOf<GalleryRow?>(null)
    var progress by mutableStateOf(0L)
    var failed by mutableStateOf(false)
    var failure by mutableStateOf(GalleryFailure.UNKNOWN)
    var preview by mutableStateOf<File?>(null)
    var previewItem by mutableStateOf<GalleryItem?>(null)
    var previewJob: Job? = null
    var previewIntent by mutableStateOf(false)
    var notice by mutableStateOf<Int?>(null)
    private var generation = 0L
    private var cancelUse: (() -> Unit)? = null
    var joined by mutableStateOf<Map<String, GalleryAdoption>>(emptyMap())
    var thumbnailGeneration by mutableStateOf(0L)
        private set
    var thumbnailFailures by mutableStateOf<Set<String>>(emptySet())
        private set
    fun thumbnailResult(key: String, failed: Boolean) {
        thumbnailFailures = if (failed) thumbnailFailures + key else thumbnailFailures - key
    }
    /** Effects own repository waiters; changing only this key preserves the grid/focus identity. */
    fun retryThumbnails() { thumbnailGeneration++ }
    suspend fun refresh() {
        notice = null
        try {
            repository.refresh()
            repository.state.value.error?.let { message -> showRefreshFailure(java.io.IOException(message)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { showRefreshFailure(failure) }
        // Includes unchanged catalog/304 and manifest errors: cached rows may still recover thumbnails.
        retryThumbnails()
    }
    private fun showRefreshFailure(error: Exception) {
        notice = when (galleryFailure(error)) {
            GalleryFailure.STORAGE -> R.string.gallery_storage_failed
            GalleryFailure.NETWORK -> R.string.gallery_network_failed
            GalleryFailure.INVALID -> R.string.gallery_invalid_failed
            GalleryFailure.UNKNOWN -> R.string.gallery_refresh_failed
        }
    }
    fun cancel() { generation++; previewJob?.cancel(); previewJob = null; cancelUse?.invoke(); cancelUse = null; active = null; failed = false }
    fun pick(row: GalleryRow) {
        cancel(); previewIntent = false; active = row; progress = 0; menu = null
        val token = generation
        cancelUse = actions.pick(row.item, { if (generation == token) progress = it }, { if (generation == token) { failed = it.isFailure; it.exceptionOrNull()?.let { error -> failure = galleryFailure(error) }; if (it.isSuccess) active = null } })
    }
    fun retry() { active?.let { if (previewIntent) preview(it) else pick(it) } }
    fun preview(row: GalleryRow) {
        cancel(); previewIntent = true; active = row; progress = 0; menu = null
        val token = generation
        previewJob = scope.launch {
            try {
                val local = repository.original(row.item) { count -> scope.launch { if (generation == token) progress = count } }
                if (generation == token) { preview = local.file; previewItem = row.item; active = null }
            } catch (e: CancellationException) { throw e }
            catch (error: Exception) { if (generation == token) { failed = true; failure = galleryFailure(error) } }
        }
    }
}
@Composable
internal fun rememberGalleryPicker(kind: BuiltinKind, packageName: String?, actions: GalleryPickerActions?): Pair<GalleryPickerState?, List<GalleryRow>> {
    if (actions == null) return null to emptyList()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { GalleryAndroid.repository(ctx) }
    val currentActions by rememberUpdatedState(actions)
    val state = remember { GalleryPickerState(repository, GalleryPickerActions(
        { item, progress, result -> currentActions.pick(item, progress, result) },
        { currentActions.cancel() }, { id, result -> currentActions.removeScreensaver(id, result) }), scope) }
    val ghost = LocalPageGhost.current
    LaunchedEffect(ghost) { if (ghost) state.cancel() }
    val snapshot by repository.state.collectAsState()
    LaunchedEffect(repository) { state.refresh() }
    LaunchedEffect(snapshot, state.menu, state.active) {
        state.joined = withContext(Dispatchers.IO) { SettingsStore.read(ctx).galleryScreensavers }
    }
    DisposableEffect(state) { onDispose { state.cancel() } }
    return state to remember(snapshot, kind, packageName) { galleryRows(snapshot, kind, packageName) }
}

@Composable
internal fun GalleryPickerOverlay(state: GalleryPickerState, screensaver: Boolean, nonce: Int) {
    val ctx = LocalContext.current
    val menu = state.menu
    val active = state.active
    val title = menu?.item?.names?.get(stringResource(R.string.builtin_names_lang)) ?: menu?.item?.id.orEmpty()
    val pick = stringResource(if (screensaver) R.string.gallery_join else R.string.gallery_use)
    val preview = stringResource(R.string.gallery_preview)
    val remove = stringResource(R.string.gallery_remove_rotation)
    val delete = stringResource(R.string.gallery_delete)
    val cancel = stringResource(R.string.dialog_cancel)
    val retry = stringResource(R.string.gallery_retry)
    if (state.notice != null) GearMenu(title = stringResource(R.string.picker_section_gallery), body = stringResource(state.notice!!), nonce = nonce,
        items = listOf(MenuItem(cancel, "") { state.notice = null }), onDismiss = { state.notice = null })
    else if (state.refreshMenu) GearMenu(title = stringResource(R.string.gallery_refresh), nonce = nonce,
        items = listOf(
            MenuItem(stringResource(R.string.gallery_refresh), "") { state.refreshMenu = false; state.scope.launch { state.refresh() } },
            MenuItem(stringResource(R.string.gallery_cleanup), "") {
                state.scope.launch {
                    try {
                        val leftovers = withContext(Dispatchers.IO) { GalleryMutationGate.locked {
                            val settings = SettingsStore.read(ctx)
                            cleanupGalleryLeftovers(state.repository, settings.galleryScreensavers.values.map { it.sha256 }.toSet() + listOfNotNull(settings.galleryWallpaper?.sha256))
                        } }
                        state.refreshMenu = false
                        if (leftovers.isNotEmpty()) state.notice = R.string.gallery_delete_failed
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { state.refreshMenu = false; state.notice = R.string.gallery_delete_failed }
                }
            }, MenuItem(cancel, "") { state.refreshMenu = false }), onDismiss = { state.refreshMenu = false })
    else if (state.preview != null) ScreensaverPreview(listOf(state.preview!!), 0, nonce, displayNames = mapOf(state.preview!!.absolutePath to (state.previewItem?.names?.get(stringResource(R.string.builtin_names_lang)) ?: state.previewItem?.id.orEmpty()))) { state.preview = null; state.previewItem = null }
    else if (active != null) GearMenu(
        title = active.item.names[stringResource(R.string.builtin_names_lang)] ?: active.item.id,
        body = if (state.failed) stringResource(when (state.failure) { GalleryFailure.NETWORK -> R.string.gallery_network_failed; GalleryFailure.STORAGE -> R.string.gallery_storage_failed; GalleryFailure.INVALID -> R.string.gallery_invalid_failed; GalleryFailure.UNKNOWN -> R.string.gallery_failed }) else stringResource(R.string.gallery_progress, state.progress * 100 / active.item.original.blob.bytes),
        nonce = nonce,
        items = buildList {
            add(MenuItem(cancel, "") { state.cancel() })
            if (state.failed) add(MenuItem(retry, "") { state.retry() })
        }, onDismiss = { state.cancel() },
    )
    else if (menu != null) GearMenu(title = title, nonce = nonce,
        body = if (menu.update) stringResource(R.string.gallery_update) else if (menu.retained) stringResource(R.string.gallery_retained) else if (menu.downloaded) stringResource(R.string.gallery_downloaded) else stringResource(R.string.gallery_available),
        items = buildList {
            if (menu.key in state.thumbnailFailures) add(MenuItem(retry, "") {
                state.menu = null; state.retryThumbnails()
            })
            add(MenuItem(preview, "") { state.preview(menu) })
            if (screensaver && state.joined[menu.item.id]?.sha256 == menu.item.original.blob.sha256) {
                add(MenuItem(remove, "") { state.actions.removeScreensaver(menu.item.id) { result -> if (result.isFailure) state.notice = R.string.gallery_failed; state.menu = null } })
            } else add(MenuItem(pick, "") { state.pick(menu) })
            if (menu.downloaded) add(MenuItem(delete, "") {
                state.scope.launch {
                    try {
                    val removed = withContext(Dispatchers.IO) {
                        GalleryMutationGate.locked {
                            val settings = SettingsStore.read(ctx)
                            val protected = settings.galleryScreensavers.values.map { it.sha256 }.toSet() + listOfNotNull(settings.galleryWallpaper?.sha256)
                            val result = state.repository.removeDownloaded(menu.item.original.blob.sha256, protected)
                            state.repository.cleanupOrphans(protected)
                            result
                        }
                    }
                    state.menu = null
                    if (!removed) state.notice = R.string.gallery_protected
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { state.menu = null; state.notice = R.string.gallery_delete_failed }
                }
            })
            add(MenuItem(cancel, "") { state.menu = null })
        }, onDismiss = { state.menu = null })
}
