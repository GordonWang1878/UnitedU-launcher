package com.uniteduone.launcher

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 图片扩展名(小写)。图库扫描与 ImagePicker 的壁纸 / 卡片图列表共用这一份,口径一致。 */
internal val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp")

/** 下一张(spec §2):(i + 1) % size;空图库恒为 0。先夹回范围,过期的下标也不会越界。 */
internal fun nextIndex(i: Int, size: Int): Int = if (size <= 0) 0 else (clampIndex(i, size) + 1) % size

/** 图被删 / 重扫之后把下标夹回 [0, size − 1];空图库恒为 0。 */
internal fun clampIndex(i: Int, size: Int): Int = if (size <= 0) 0 else i.coerceIn(0, size - 1)

/**
 * 屏保图库的唯一扫描规则(spec §2「与今天相同」):library/screensavers/ 下 jpg / jpeg / png / webp,按文件名排序。
 * 播放器、状态机「到点查图库非空」、屏保按钮、设置页计数四处都读它,口径一致。
 * 扫描前先把旧版单张 screensaver.jpg/png 迁进图库(原来在 HomeScreen 的待机重扫里,spec §2 迁到这里)。
 * 外置存储没挂(`baseOrNull == null`)时按空图库处理(spec §9):不走 `Paths.base` 的 internal 回落——
 * 那里必然没有图,`screensaverLibrary()` 还会在 internal 凭空建一层目录。**必须在 IO 线程调用。**
 */
internal fun scanScreensaverLibrary(ctx: Context): List<File> {
    if (Paths.baseOrNull(ctx) == null) return emptyList()
    migrateOldScreensaver(ctx)
    return Paths.screensaverLibrary(ctx).listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTS }
        ?.sortedBy { it.name }
        ?: emptyList()
}

/**
 * 状态机与屏保按钮的「图库非空」判据(spec §1.1 / §1.3)。**必须在 IO 线程调用。**
 * 扫描失败(外置存储被拔、权限变化)按空图库处理;调用方都在 LaunchedEffect 里,
 * 异常冒出去就是在主线程崩掉整个桌面。失败时由 [safeScan] 记录日志。
 */
internal fun hasScreensaverImages(ctx: Context): Boolean = safeScan(ctx)?.isNotEmpty() ?: false

/** 把旧版单张 screensaver.jpg/png 迁移到图库目录。只在图库为空时迁移一次。 */
private fun migrateOldScreensaver(ctx: Context) {
    val dir = Paths.screensaverLibrary(ctx)
    if (dir.listFiles()?.any { it.isFile } == true) return
    for (old in listOf(Paths.screensaver(ctx), Paths.screensaverPng(ctx))) {
        if (old.exists()) {
            old.renameTo(File(dir, old.name))
            break
        }
    }
}

/**
 * 播放器的引用计数(spec §2):第一个 [acquire] 返回 true(调用方启动计时),最后一个 [release] 返回 true
 * (调用方停计时);多余的 [release] 是空操作、返回 false——计数不会变负,也不会因为多 detach 一次就把
 * 另一处还在用的计时停掉。只在主线程调,不加锁。
 */
internal class RefCounter {
    var count = 0
        private set

    fun acquire(): Boolean {
        count++
        return count == 1
    }

    fun release(): Boolean {
        if (count == 0) return false
        count--
        return count == 0
    }
}

/**
 * 扫一次图库;任何异常都当作「这次没扫到」,返回 null,调用方保留上一份列表、计时照走。
 * internal(不是 file-private):图库查看器(ImagePicker.kt)与设置页的张数统计
 * (SettingsScreen.kt)也走这一份——原来各自另包一层 runCatching,失败要么不记日志、
 * 要么直接让异常冒出协程,现在统一成这一个「失败记日志、退回 null」的口径。
 */
internal fun safeScan(app: Context): List<File>? =
    runCatching { scanScreensaverLibrary(app) }
        .onFailure { android.util.Log.w("UnitedU", "屏保图库扫描失败", it) }
        .getOrNull()

/**
 * 屏保播放器(spec §2):进程级单例,桌面屏保层([Screensaver])与系统屏保([UnitedUDream])都只读它。
 *
 * - **换图计时只此一份**(一个 `Dispatchers.Main` 协程):两处同时在场也不会双倍推进;
 *   两处都 attach 时间隔取最后一次 attach 传入的值(两处读同一份设置,正常情况下相等)。
 * - **引用计数**:第一个 [attach] 在 IO 线程重扫图库、启动计时;最后一个 [detach] 停计时,
 *   [index] 与 [files] **保留**——下次 attach 从同一张接着播。「系统屏保接桌面屏保的班」靠的就是这一条。
 * - [attach] / [detach] 只在主线程调(Compose 效果与 DreamService 回调都在主线程),`refs`([RefCounter])不加锁。
 */
object ScreensaverPlayer {
    private val _files = MutableStateFlow<List<File>>(emptyList())
    private val _index = MutableStateFlow(0)
    val files: StateFlow<List<File>> = _files.asStateFlow()
    val index: StateFlow<Int> = _index.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val refs = RefCounter()
    private var ticker: Job? = null
    private var intervalNow = Theme.ScreensaverIntervalMs

    fun attach(ctx: Context, intervalMs: Long) {
        intervalNow = intervalMs
        if (!refs.acquire()) return
        val app = ctx.applicationContext
        ticker = scope.launch {
            // 扫描失败不能杀掉这个协程(原来 IO 异常直接冒出去,轮播从此停转)
            withContext(Dispatchers.IO) { safeScan(app) }?.let { publish(it) }
            while (true) {
                // 每轮重读间隔:另一处 attach 改了它,下一张起就按新值
                delay(intervalNow)
                _index.value = nextIndex(_index.value, _files.value.size)
            }
        }
    }

    fun detach() {
        if (refs.release()) {
            ticker?.cancel()
            ticker = null
        }
    }

    /** 删图后调用(spec §5):重扫并把 [index] 夹回范围。计时状态不动;扫描失败保留旧列表。 */
    fun rescan(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { withContext(Dispatchers.IO) { safeScan(app) }?.let { publish(it) } }
    }

    private fun publish(list: List<File>) {
        _files.value = list
        _index.value = clampIndex(_index.value, list.size)
    }
}
