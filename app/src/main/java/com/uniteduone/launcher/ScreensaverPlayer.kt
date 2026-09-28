package com.uniteduone.launcher

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** 图片扩展名(小写)。ImagePicker 的壁纸 / 卡片图列表用这一份;屏保图库另收视频(R100),见 [ScreensaverMedia.ALL_EXTS]。 */
internal val IMAGE_EXTS = ScreensaverMedia.PHOTO_EXTS

/** 下一张(spec §2):(i + 1) % size;空图库恒为 0。先夹回范围,过期的下标也不会越界。 */
internal fun nextIndex(i: Int, size: Int): Int = if (size <= 0) 0 else (clampIndex(i, size) + 1) % size

/** 图被删 / 重扫之后把下标夹回 [0, size − 1];空图库恒为 0。 */
internal fun clampIndex(i: Int, size: Int): Int = if (size <= 0) 0 else i.coerceIn(0, size - 1)

/**
 * 屏保图库的唯一扫描规则(spec §2「与今天相同」):library/screensavers/ 下 jpg / jpeg / png / webp 照片
 * + mp4 / m4v / mov / webm 视频(Ruling R100,[ScreensaverMedia.ALL_EXTS]),按文件名排序、照片视频混排。
 * 播放器、状态机「到点查图库非空」、屏保按钮、设置页计数四处都读它,口径一致。
 * 扫描前先把旧版单张 screensaver.jpg/png 迁进图库(原来在 HomeScreen 的待机重扫里,spec §2 迁到这里)。
 * 外置存储没挂(`baseOrNull == null`)时按空图库处理(spec §9):不走 `Paths.base` 的 internal 回落——
 * 那里必然没有图,`screensaverLibrary()` 还会在 internal 凭空建一层目录。**必须在 IO 线程调用。**
 */
internal fun scanScreensaverLibrary(ctx: Context): List<File> {
    if (Paths.baseOrNull(ctx) == null) return emptyList()
    migrateOldScreensaver(ctx)
    return Paths.screensaverLibrary(ctx).listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in ScreensaverMedia.ALL_EXTS }
        ?.sortedBy { it.name }
        ?: emptyList()
}

/**
 * **屏保轮播**(Ruling R117):参与轮播的内置屏保图(清单顺序,去掉 settings.json `excludedBuiltinScreensavers`
 * 里的 ID)+ 用户图库([scanScreensaverLibrary],照片与视频,R100–R104 规则不变)。内置图是伪路径文件
 * (见 [BUILTIN_PSEUDO_ROOT]),播放器 / 过渡 / 失败表照 `File` 处理,解码时 [decodeImagePath] 改读 assets。
 * 播放器、状态机「到点查轮播非空」、「立即开始屏保」、设置页计数都读它;图库网格按两块分开读(内置 + [safeScan])。
 * 外置存储没挂时用户图库那一半为空、排除集合按默认(全部参与)——内置图不靠外置存储,照样能播。
 * **必须在 IO 线程调用。**
 */
internal fun scanScreensaverPlaylist(ctx: Context): List<File> {
    val builtins = BuiltinImages.list(ctx, BuiltinKind.SCREENSAVERS)
    val excluded = if (builtins.isEmpty()) emptySet() else SettingsStore.read(ctx).excludedBuiltinScreensavers
    return screensaverPlaylist(builtins.map { it.file }, { builtinIdOf(it.name) }, excluded, scanScreensaverLibrary(ctx))
}

/**
 * 状态机与屏保按钮的「轮播非空」判据(spec §1.1 / §1.3;R117 起是轮播,不是图库——内置图全关掉、图库又空
 * 就算空)。**必须在 IO 线程调用。**
 * 扫描失败(外置存储被拔、权限变化)按空处理;调用方都在 LaunchedEffect 里,
 * 异常冒出去就是在主线程崩掉整个桌面。失败时由 [safePlaylist] 记录日志。
 */
internal fun hasScreensaverImages(ctx: Context): Boolean = safePlaylist(ctx)?.isNotEmpty() ?: false

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

/** [scanScreensaverPlaylist] 的「失败记日志、退回 null」版(同 [safeScan] 的口径)。播放器与「轮播非空」判据用它。 */
internal fun safePlaylist(app: Context): List<File>? =
    runCatching { scanScreensaverPlaylist(app) }
        .onFailure { android.util.Log.w("UnitedU", "屏保轮播扫描失败", it) }
        .getOrNull()

/**
 * 画面那一侧报给计时器的事(Ruling R101)。[round] 是报的时候画面正在播的那一轮([ScreensaverPlayer.round]),
 * 计时器只认「当前这一项、当前这一轮」的事,换过项之后迟到的旧事件直接丢掉。
 */
internal sealed class SlideEvent {
    abstract val path: String
    abstract val round: Int

    /** 视频首帧出来、开始播(每次 start / 重播 / 从后台回来续播都报一次);[durationMs] 读不到为 0。 */
    data class Started(override val path: String, override val round: Int, val durationMs: Long) : SlideEvent()
    /** 视频播完一遍,或到了 [ScreensaverMedia.VIDEO_MAX_PLAY_MS] 上限。 */
    data class Done(override val path: String, override val round: Int) : SlideEvent()
    /** 照片解不出来 / 视频解码失败或首帧超时:跳过,本进程内不再轮到它(文件改了再给机会)。 */
    data class Failed(override val path: String, override val round: Int) : SlideEvent()

    fun isFor(p: String, r: Int): Boolean = path == p && round == r
}

/**
 * 屏保播放器(spec §2):进程级单例,桌面屏保层([Screensaver])与系统屏保([UnitedUDream])都只读它。
 *
 * - **换项计时只此一份**(一个 `Dispatchers.Main` 协程):两处同时在场也不会双倍推进;
 *   两处都 attach 时间隔取最后一次 attach 传入的值(两处读同一份设置,正常情况下相等)。
 * - **照片停「换图间隔」,视频停到播完**(Ruling R101):轮到视频时计时器不按间隔走,等画面那一侧经 [report]
 *   报 [SlideEvent.Started] / [SlideEvent.Done] / [SlideEvent.Failed];没有任何画面在渲染时(桌面退到后台等)
 *   等 [ScreensaverMedia.START_WAIT_MS] 自己往下走,报了开始却一直没报完也只多等 [ScreensaverMedia.END_GRACE_MS],不会卡死。
 * - **[round]** 每换一次项 +1(包括只有一项、下标没变的时候):只有一个视频时靠它让画面从头重播(= 循环)。
 * - **失败表**:报过失败的文件(路径 + 修改时间)之后换项时跳过([ScreensaverMedia.nextPlayable]);全都失败就原地等。
 * - **轮播**([files])= 参与的内置屏保图 + 用户图库(R117,[scanScreensaverPlaylist])。
 * - **引用计数**:第一个 [attach] 在 IO 线程重扫轮播、启动计时;最后一个 [detach] 停计时,
 *   [index] 与 [files] **保留**——下次 attach 从同一张接着播。「系统屏保接桌面屏保的班」靠的就是这一条。
 * - [attach] / [detach] / [report] 只在主线程调(Compose 效果与 DreamService 回调都在主线程),`refs`([RefCounter])不加锁。
 */
object ScreensaverPlayer {
    private val _files = MutableStateFlow<List<File>>(emptyList())
    private val _index = MutableStateFlow(0)
    private val _round = MutableStateFlow(0)
    val files: StateFlow<List<File>> = _files.asStateFlow()
    val index: StateFlow<Int> = _index.asStateFlow()
    val round: StateFlow<Int> = _round.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val refs = RefCounter()
    private var ticker: Job? = null
    private var intervalNow = Theme.ScreensaverIntervalMs
    private val events = Channel<SlideEvent>(Channel.UNLIMITED)
    /** 失败过的文件:路径 → 失败时的修改时间。文件被换掉(重新上传同名)修改时间变了,就再给一次机会。 */
    private val failed = HashMap<String, Long>()

    fun attach(ctx: Context, intervalMs: Long) {
        intervalNow = intervalMs
        if (!refs.acquire()) return
        val app = ctx.applicationContext
        // 上一段留下的旧事件不作数(轮次可能刚好对得上)
        while (events.tryReceive().isSuccess) Unit
        ticker = scope.launch {
            // 扫描失败不能杀掉这个协程(原来 IO 异常直接冒出去,轮播从此停转)
            withContext(Dispatchers.IO) { safePlaylist(app) }?.let { publish(it) }
            while (true) {
                val list = _files.value
                val cur = list.getOrNull(clampIndex(_index.value, list.size))
                when {
                    cur == null -> Unit   // 空图库:下面 advance 失败、按间隔再看
                    isFailed(cur) -> Unit
                    ScreensaverMedia.isVideo(cur.name) -> awaitVideo(cur.absolutePath, _round.value)
                    // 照片:停一个间隔;期间画面报「解不出来」就提前走
                    else -> withTimeoutOrNull(intervalNow) { awaitEvent(cur.absolutePath, _round.value) }
                        ?.let { if (it is SlideEvent.Failed) markFailed(it.path) }
                }
                if (!advance()) delay(intervalNow)
            }
        }
    }

    fun detach() {
        if (refs.release()) {
            ticker?.cancel()
            ticker = null
        }
    }

    /** 画面那一侧报事(主线程)。没有计时器在跑时直接丢掉(全屏预览不报;屏保已退出时报的也不作数)。 */
    internal fun report(event: SlideEvent) {
        if (ticker == null) return
        if (event is SlideEvent.Failed) markFailed(event.path)
        events.trySend(event)
    }

    /** 这个文件之前失败过、且之后没被换掉。主线程读修改时间:只对失败表里有的路径才读。 */
    internal fun isFailed(f: File): Boolean = failed[f.absolutePath]?.let { it == f.lastModified() } ?: false

    private fun markFailed(path: String) {
        val mtime = File(path).lastModified()
        if (failed.put(path, mtime) != mtime) android.util.Log.w("UnitedU", "屏保跳过无法播放的一项 $path")
    }

    /**
     * 等一个视频播完(R101):先等画面报开始(至多 [ScreensaverMedia.START_WAIT_MS]),再等播完(播放时长 +
     * [ScreensaverMedia.END_GRACE_MS]);期间再报一次开始(从后台回来续播、系统屏保接班从头播)就按新的时长重新计。
     */
    private suspend fun awaitVideo(path: String, round: Int) {
        var e = withTimeoutOrNull(ScreensaverMedia.START_WAIT_MS) { awaitEvent(path, round) } ?: return
        while (e is SlideEvent.Started) {
            e = withTimeoutOrNull(ScreensaverMedia.endWaitMs(e.durationMs)) { awaitEvent(path, round) } ?: return
        }
        if (e is SlideEvent.Failed) markFailed(path)
    }

    private suspend fun awaitEvent(path: String, round: Int): SlideEvent {
        while (true) {
            val e = events.receive()
            if (e.isFor(path, round)) return e
        }
    }

    /** 换到下一项能播的([ScreensaverMedia.nextPlayable]);全都失败 → false,原地不动。 */
    private fun advance(): Boolean {
        val list = _files.value
        val next = ScreensaverMedia.nextPlayable(_index.value, list.size) { isFailed(list[it]) } ?: return false
        _index.value = next
        _round.value = _round.value + 1
        return true
    }

    /**
     * 删图后调用(spec §5),R117 起内置图「不参与 / 加入轮播」后也调:重扫轮播并把 [index] 夹回范围。
     * 计时状态不动;扫描失败保留旧列表。
     */
    fun rescan(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { withContext(Dispatchers.IO) { safePlaylist(app) }?.let { publish(it) } }
    }

    private fun publish(list: List<File>) {
        _files.value = list
        _index.value = clampIndex(_index.value, list.size)
    }
}
