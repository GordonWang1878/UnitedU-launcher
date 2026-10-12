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
    val locals = snapshot.downloaded.filter { kind in it.item.usages && (kind != BuiltinKind.CARDS || packageName in it.item.packages) }
    return current.map { item -> GalleryRow(galleryFocusKey(item.id), item, snapshot.downloaded.any { it.item.id == item.id && it.item.original.blob.sha256 == item.original.blob.sha256 }, false, locals.any { it.item.id == item.id && it.item.original.blob.sha256 != item.original.blob.sha256 }) } +
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
    /** [report] = 失败时弹提示页;打开页时的自动刷新不弹(标题旁已写「刷新失败」),用户手动重试才弹。 */
    suspend fun refresh(report: Boolean = true) {
        notice = null
        try {
            repository.refresh()
            repository.state.value.error?.let { message -> if (report) showRefreshFailure(java.io.IOException(message)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (report) showRefreshFailure(failure) }
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
    /**
     * 已下载的图不经过「下载中」那一层(2026-10-12 Gordon:点预览会闪一下「下载中」):菜单留着,等本地原图取到再直接换成
     * 预览 / 收菜单;只有取不到(文件丢了要重下、或出错)才把进度 / 失败页推上来。没下载的照旧先出进度页。
     */
    /** 返回 quiet:从菜单上点的、且已下载 → 不推进度页。重试(失败页上点的)一律走进度页。 */
    private fun begin(row: GalleryRow, intent: Boolean): Boolean {
        val quiet = row.downloaded && menu?.key == row.key && active == null
        cancel(); previewIntent = intent; progress = 0
        if (!quiet) { active = row; menu = null }
        return quiet
    }
    /** quiet 这一步还在菜单上时,用户按返回 / 取消关了菜单 → 结果作废(关菜单同时 [cancel])。 */
    private fun stillWanted(row: GalleryRow, quiet: Boolean) = !quiet || menu?.key == row.key || active?.key == row.key
    private fun fail(row: GalleryRow, error: Throwable) { menu = null; active = row; failed = true; failure = galleryFailure(error) }
    fun pick(row: GalleryRow) {
        val quiet = begin(row, intent = false)
        val token = generation
        cancelUse = actions.pick(row.item, { if (generation == token) { progress = it; if (quiet && active == null && stillWanted(row, quiet)) { menu = null; active = row } } }) {
            if (generation == token && stillWanted(row, quiet)) {
                it.exceptionOrNull()?.let { error -> fail(row, error) }
                if (it.isSuccess) { active = null; menu = null }
            }
        }
    }
    fun retry() { active?.let { if (previewIntent) preview(it) else pick(it) } }
    fun preview(row: GalleryRow) {
        val quiet = begin(row, intent = true)
        val token = generation
        previewJob = scope.launch {
            try {
                val local = repository.original(row.item) { count -> scope.launch { if (generation == token) { progress = count; if (quiet && active == null && stillWanted(row, quiet)) { menu = null; active = row } } } }
                if (generation == token && stillWanted(row, quiet)) { preview = local.file; previewItem = row.item; active = null; menu = null }
            } catch (e: CancellationException) { throw e }
            catch (error: Exception) { if (generation == token && stillWanted(row, quiet)) fail(row, error) }
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
    // 打开页即刷新;刷完顺手清掉没被壁纸 / 轮播引用的残留下载(原来是刷新格菜单里的「清理」,失败不打扰用户,下次打开再清)。
    LaunchedEffect(repository) {
        state.refresh(report = false)
        try {
            withContext(Dispatchers.IO) { GalleryMutationGate.locked {
                val settings = SettingsStore.read(ctx)
                cleanupGalleryLeftovers(repository, settings.galleryScreensavers.values.map { it.sha256 }.toSet() + listOfNotNull(settings.galleryWallpaper?.sha256))
            } }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {}
    }
    LaunchedEffect(snapshot, state.menu, state.active) {
        state.joined = withContext(Dispatchers.IO) { SettingsStore.read(ctx).galleryScreensavers }
    }
    DisposableEffect(state) { onDispose { state.cancel() } }
    return state to remember(snapshot, kind, packageName) { galleryRows(snapshot, kind, packageName) }
}

/**
 * 图库浮层的一层快照(R136 残影约定):残影画关掉前那一份,不按 [GalleryPickerState] 现查。
 * 要放进 [OverlayStack]:选图页本身已在一摞里(`LocalBackdropProvided` 为真),直接叠在网格上的 [GearMenu]
 * 不铺底(R142),整页透明地压在图库网格上(2026-10-11 模拟器实测)。
 */
internal sealed class GalleryLayer(val key: String) {
    class Notice(val text: Int) : GalleryLayer("notice")
    class Preview(val file: File, val name: String) : GalleryLayer("preview")
    class Active(val row: GalleryRow, val failed: Boolean, val failure: GalleryFailure, val progress: Long) : GalleryLayer("active:${row.key}")
    class Menu(val row: GalleryRow, val joined: Boolean, val thumbnailFailed: Boolean, val refreshFailed: Boolean) : GalleryLayer("menu:${row.key}")
}

@Composable
internal fun GalleryPickerOverlay(state: GalleryPickerState, screensaver: Boolean, nonce: Int) {
    val ctx = LocalContext.current
    val lang = stringResource(R.string.builtin_names_lang)
    val snapshot by state.repository.state.collectAsState()
    val menu = state.menu
    val active = state.active
    val layer = when {
        state.notice != null -> GalleryLayer.Notice(state.notice!!)
        state.preview != null -> GalleryLayer.Preview(state.preview!!, state.previewItem?.names?.get(lang) ?: state.previewItem?.id.orEmpty())
        active != null -> GalleryLayer.Active(active, state.failed, state.failure, state.progress)
        menu != null -> GalleryLayer.Menu(menu, screensaver && state.joined[menu.item.id]?.sha256 == menu.item.original.blob.sha256, menu.key in state.thumbnailFailures, snapshot.error != null)
        else -> null
    }
    val pick = stringResource(if (screensaver) R.string.gallery_join else R.string.gallery_use)
    val preview = stringResource(R.string.gallery_preview)
    val remove = stringResource(R.string.gallery_remove_rotation)
    val delete = stringResource(R.string.gallery_delete)
    val cancel = stringResource(R.string.dialog_cancel)
    val retry = stringResource(R.string.gallery_retry)
    val refreshLabel = stringResource(R.string.gallery_refresh)
    OverlayStack(state = layer, layerKey = { it.key }) { l -> when (l) {
    is GalleryLayer.Notice -> GearMenu(title = stringResource(R.string.picker_section_gallery), body = stringResource(l.text), nonce = nonce,
        items = listOf(MenuItem(cancel, "") { state.notice = null }), onDismiss = { state.notice = null })
    is GalleryLayer.Preview -> ScreensaverPreview(listOf(l.file), 0, nonce, displayNames = mapOf(l.file.absolutePath to l.name)) { state.preview = null; state.previewItem = null }
    is GalleryLayer.Active -> GearMenu(
        title = l.row.item.names[lang] ?: l.row.item.id,
        body = if (l.failed) stringResource(when (l.failure) { GalleryFailure.NETWORK -> R.string.gallery_network_failed; GalleryFailure.STORAGE -> R.string.gallery_storage_failed; GalleryFailure.INVALID -> R.string.gallery_invalid_failed; GalleryFailure.UNKNOWN -> R.string.gallery_failed }) else stringResource(R.string.gallery_progress, l.progress * 100 / l.row.item.original.blob.bytes),
        nonce = nonce,
        items = buildList {
            add(MenuItem(cancel, "") { state.cancel() })
            if (l.failed) add(MenuItem(retry, "") { state.retry() })
        }, onDismiss = { state.cancel() },
    )
    is GalleryLayer.Menu -> { val row = l.row; GearMenu(title = row.item.names[lang] ?: row.item.id, nonce = nonce,
        body = if (row.update) stringResource(R.string.gallery_update) else if (row.retained) stringResource(R.string.gallery_retained) else if (row.downloaded) stringResource(R.string.gallery_downloaded) else stringResource(R.string.gallery_available),
        items = buildList {
            if (l.thumbnailFailed) add(MenuItem(retry, "") {
                state.menu = null; state.retryThumbnails()
            })
            add(MenuItem(preview, "") { state.preview(row) })
            if (l.joined) {
                add(MenuItem(remove, "") { state.actions.removeScreensaver(row.item.id) { result -> if (result.isFailure) state.notice = R.string.gallery_failed; state.menu = null } })
            } else add(MenuItem(pick, "") { state.pick(row) })
            if (row.downloaded) add(MenuItem(delete, "") {
                state.scope.launch {
                    try {
                    val removed = withContext(Dispatchers.IO) {
                        GalleryMutationGate.locked {
                            val settings = SettingsStore.read(ctx)
                            val protected = settings.galleryScreensavers.values.map { it.sha256 }.toSet() + listOfNotNull(settings.galleryWallpaper?.sha256)
                            val result = state.repository.removeDownloaded(row.item.original.blob.sha256, protected)
                            state.repository.cleanupOrphans(protected)
                            result
                        }
                    }
                    state.menu = null
                    if (!removed) state.notice = R.string.gallery_protected
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { state.menu = null; state.notice = R.string.gallery_delete_failed }
                }
            })
            // 上次刷新失败(标题旁写着「刷新失败」)时才给手动重刷;平时打开页就自动刷新,不占菜单。
            if (l.refreshFailed) add(MenuItem(refreshLabel, "") { state.menu = null; state.scope.launch { state.refresh() } })
            add(MenuItem(cancel, "") { state.cancel(); state.menu = null })
        }, onDismiss = { state.cancel(); state.menu = null }) }
    } }
}
