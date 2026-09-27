package com.uniteduone.launcher

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CompletableDeferred
import java.io.File

private const val TAG = "UnitedU"

/**
 * 进程内同一时刻最多一个 [MediaPlayer](Ruling R102;A95L 32 位 MTK 不同时解两路)。后来者 [acquire] 时
 * 直接把前一个收回([VideoController.suspend]:截下当前帧垫底、记下位置、释放播放器),不排队、不等——
 * 屏保与预览、桌面屏保与系统屏保交接时,总是新出现的那一个赢。只在主线程用。
 */
internal object VideoGate {
    private var holder: VideoController? = null

    fun acquire(c: VideoController) {
        val h = holder
        if (h != null && h !== c) {
            holder = null
            Log.i(TAG, "视频播放器交接:收回 ${h.path}")
            h.suspend()
        }
        holder = c
    }

    fun release(c: VideoController) {
        if (holder === c) holder = null
    }
}

/**
 * 一个视频文件的播放器(系统 [MediaPlayer],Ruling R101/R102)。静音;状态机:
 * [prepare](预加载,只准备不播)→ [play](有了画布就 start)→ 首帧出来报 [onStarted] → 播完 / 到 [capMs] 报
 * [onPlayedOut];出错或 [ScreensaverMedia.FIRST_FRAME_TIMEOUT_MS] 内出不了首帧报 [onFailed]。
 * [suspend](退出屏保 / 退到后台 / 被 [VideoGate] 收回)截帧、记位置、释放播放器,之后 [play] 会新建一个从原位置续播;
 * [release] 是终态。**只在主线程用**(MediaPlayer 回调也在主线程:它在主线程创建,监听器走创建线程的 Looper)。
 */
internal class VideoController(
    val path: String,
    private val looping: Boolean,
    /** 播到这么久报一次 [onPlayedOut](屏保的 60 s 上限),播放继续——下一项淡入时它还在动;null = 不设(预览)。 */
    private val capMs: Long?,
) {
    var onStarted: ((durationMs: Long) -> Unit)? = null
    var onPlayedOut: (() -> Unit)? = null
    var onFailed: (() -> Unit)? = null

    /** 第一次出首帧 → true;失败 / 释放 → false。 */
    val firstFrame = CompletableDeferred<Boolean>()
    /** 视频画面尺寸(已按旋转元数据换过宽高),给 [VideoSurface] 算 Crop。 */
    var videoSize by mutableStateOf(IntSize.Zero)
        private set
    /** 画布上盖着的静图:预览的首帧缩略图,或 [suspend] 时截下的那一帧。首帧真出来之后清掉。 */
    var still by mutableStateOf<ImageBitmap?>(null)
    /** 画布上此刻是不是真的视频帧(不是就把 [still] 盖在上面)。 */
    var showingFrame by mutableStateOf(false)
        private set
    var failed = false
        private set

    private var mp: MediaPlayer? = null
    private var prepared = false
    private var playing = false
    private var wantPlay = false
    private var surface: Surface? = null
    private var view: TextureView? = null
    private var resumeAtMs = 0
    private var durationMs = 0L
    private var released = false
    private var playedOut = false
    private val handler = Handler(Looper.getMainLooper())
    private val capRunnable = Runnable { reportPlayedOut() }
    private val timeoutRunnable = Runnable { fail("首帧超时") }

    /** 只准备不播(预加载,R102)。已有播放器 / 已释放 / 已失败时什么都不做。 */
    fun prepare() {
        if (released || failed || mp != null) return
        VideoGate.acquire(this)
        val p = MediaPlayer()
        mp = p
        prepared = false
        playing = false
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            p.setDataSource(path)
            p.setVolume(0f, 0f)
            p.isLooping = looping
            p.setOnPreparedListener { if (mp === it) { prepared = true; durationMs = it.duration.toLong().coerceAtLeast(0); maybeStart() } }
            p.setOnVideoSizeChangedListener { m, w, h -> if (mp === m && w > 0 && h > 0) videoSize = IntSize(w, h) }
            p.setOnInfoListener { m, what, _ ->
                if (mp === m && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onRendering()
                false
            }
            p.setOnCompletionListener { if (mp === it && !looping) reportPlayedOut() }
            p.setOnErrorListener { m, what, extra -> if (mp === m) fail("MediaPlayer error $what/$extra"); true }
            surface?.let { p.setSurface(it) }
            p.prepareAsync()
        } catch (e: Exception) {
            fail(e.toString())
        }
    }

    /** 要播:还没播放器就建一个(续播时从 [suspend] 记下的位置开始),有了画布且准备好就 start。 */
    fun play() {
        if (released || failed) return
        wantPlay = true
        if (!showingFrame) {
            handler.removeCallbacks(timeoutRunnable)
            handler.postDelayed(timeoutRunnable, ScreensaverMedia.FIRST_FRAME_TIMEOUT_MS)
        }
        if (mp == null) prepare() else { VideoGate.acquire(this); maybeStart() }
    }

    /** 单视频循环(R101):从头再播一遍,再报一次开始。播放器已被收回时按续播从 0 起。 */
    fun restart() {
        if (released || failed) return
        playedOut = false
        val p = mp
        if (p != null && prepared && playing) {
            runCatching { p.seekTo(0); p.start() }.onFailure { fail(it.toString()); return }
            onStarted?.invoke(durationMs)
            scheduleCap(0)
        } else {
            resumeAtMs = 0
            play()
        }
    }

    private fun maybeStart() {
        val p = mp ?: return
        if (!wantPlay || !prepared || surface == null || playing) return
        runCatching {
            if (resumeAtMs > 0) p.seekTo(resumeAtMs)
            p.start()
        }.onFailure { fail(it.toString()); return }
        playing = true
    }

    private fun onRendering() {
        handler.removeCallbacks(timeoutRunnable)
        showingFrame = true
        still = null
        firstFrame.complete(true)
        onStarted?.invoke(durationMs)
        scheduleCap(runCatching { mp?.currentPosition ?: 0 }.getOrDefault(0))
    }

    private fun scheduleCap(positionMs: Int) {
        handler.removeCallbacks(capRunnable)
        val cap = capMs ?: return
        if (durationMs in 1..cap) return   // 不超上限的视频等「播完」回调
        handler.postDelayed(capRunnable, (cap - positionMs).coerceAtLeast(0))
    }

    private fun reportPlayedOut() {
        if (playedOut) return
        playedOut = true
        onPlayedOut?.invoke()
    }

    /** [VideoSurface] 的 TextureView(截帧用)。 */
    fun bindView(v: TextureView?) {
        view = v
    }

    /** 画布来了 / 没了。 */
    fun onSurface(st: SurfaceTexture?) {
        surface?.release()
        surface = st?.let { Surface(it) }
        mp?.let { p -> runCatching { p.setSurface(surface) } }
        maybeStart()
    }

    /** 此刻画布上那一帧(视图尺寸);没有真帧 → null。 */
    fun snapshot(): ImageBitmap? {
        if (!showingFrame) return still
        val v = view ?: return still
        return runCatching { if (v.isAvailable) v.bitmap?.asImageBitmap() else null }.getOrNull() ?: still
    }

    /** 暂离(R102):截帧垫底、记下位置、释放播放器;之后 [play] 续播。没有播放器时只是不想播了。 */
    fun suspend() {
        wantPlay = false
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(capRunnable)
        val p = mp ?: return
        if (showingFrame) still = snapshot()
        showingFrame = false
        if (playing) resumeAtMs = runCatching { p.currentPosition }.getOrDefault(0)
        releasePlayer()
    }

    /** 终态:释放一切。重复调用无害。 */
    fun release() {
        if (released) return
        released = true
        wantPlay = false
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(capRunnable)
        releasePlayer()
        surface?.release()
        surface = null
        view = null
        firstFrame.complete(false)
    }

    private fun fail(why: String) {
        if (failed || released) return
        Log.w(TAG, "视频播放失败,跳过 $path:$why")
        failed = true
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(capRunnable)
        if (showingFrame) still = snapshot()
        showingFrame = false
        releasePlayer()
        firstFrame.complete(false)
        onFailed?.invoke()
    }

    private fun releasePlayer() {
        val p = mp ?: return
        mp = null
        prepared = false
        playing = false
        runCatching { p.reset() }
        runCatching { p.release() }
        VideoGate.release(this)
    }
}

/** 宿主(Activity / 系统屏保)是否至少 STARTED:退到后台 / 系统屏保结束时视频要立刻停(R102)。 */
@Composable
internal fun rememberHostStarted(): Boolean {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return started
}

/**
 * 视频画布:TextureView(能吃 `graphicsLayer` 的 alpha,交叉淡化要它)按 Crop 铺满([ScreensaverMedia.cropScale]),
 * 上面盖着 [VideoController.still] 直到真帧出来。[active] 且宿主 STARTED 才播,否则当场 [VideoController.suspend];
 * 离开组合即 [VideoController.release]。不可聚焦,不进任何焦点账本。
 */
@Composable
internal fun VideoSurface(controller: VideoController, active: Boolean, modifier: Modifier = Modifier) {
    val started = rememberHostStarted()
    val canPlay = active && started
    LaunchedEffect(controller, canPlay) { if (canPlay) controller.play() else controller.suspend() }
    DisposableEffect(controller) { onDispose { controller.release() } }
    Box(modifier) {
        key(controller) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        isOpaque = false
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                applyCrop(this@apply, controller.videoSize)
                                controller.onSurface(st)
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) =
                                applyCrop(this@apply, controller.videoSize)
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                controller.onSurface(null)
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                        controller.bindView(this)
                    }
                },
                update = { applyCrop(it, controller.videoSize) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        val still = controller.still
        if (still != null && !controller.showingFrame) {
            Image(still, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun applyCrop(v: TextureView, size: IntSize) {
    val (sx, sy) = ScreensaverMedia.cropScale(v.width, v.height, size.width, size.height)
    v.setTransform(Matrix().apply { setScale(sx, sy, v.width / 2f, v.height / 2f) })
}

/**
 * 视频的首帧缩略图 + 时长(Ruling R104):[MediaMetadataRetriever],**只在 IO 线程调 [load]**;按「路径 + 大小 +
 * 修改时间」缓存(LRU,约 8 MB),同名文件被换掉会重新取。取不到帧的也缓存(frame = null),不反复打开坏文件。
 */
internal object VideoThumbs {
    class Info(val frame: Bitmap?, val durationMs: Long)

    private val cache = object : LruCache<String, Info>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Info): Int = (value.frame?.byteCount ?: 0) + 64
    }

    private fun key(f: File) = "${f.absolutePath}|${f.length()}|${f.lastModified()}"

    /** 盒子 [box]×[box] 内等比缩放(横片 384×216、竖片 216×384),网格格子与预览垫底都够用。 */
    fun load(f: File, box: Int = 384): Info {
        val k = key(f)
        cache.get(k)?.let { return it }
        val info = probe(f.absolutePath, box)
        cache.put(k, info)
        return info
    }

    private fun probe(path: String, box: Int): Info {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frame = runCatching {
                r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, box, box)
            }.getOrNull()
            Info(frame, duration)
        } catch (e: Exception) {
            Log.w(TAG, "视频缩略图失败 $path: $e")
            Info(null, 0L)
        } finally {
            runCatching { r.release() }
        }
    }

    /** 上传校验(R103):系统能打开这个文件、而且有视频轨。**IO 线程。** */
    fun hasVideoTrack(path: String): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        } catch (e: Exception) {
            Log.w(TAG, "上传的视频打不开 $path: $e")
            false
        } finally {
            runCatching { r.release() }
        }
    }
}
