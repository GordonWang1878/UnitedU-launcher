package com.uniteduone.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/**
 * 桌面的自定义屏保层(spec §1.4 第 2 层):[active] 为真时 attach 播放器、淡入 1200 ms,为假时 detach、淡出 400 ms。
 * 计时、扫描、下标都在 [ScreensaverPlayer];这里只管淡入淡出与 attach / detach 配对。
 * 图库为空时什么都不画(播放器扫出空表,alpha 目标就是 0)。
 *
 * **不叠时钟(Ruling R23,终审 2026-09-20,撤回 R16)**:R16 曾在这里叠过一份 [HeroClock]——
 * 理由是 [UnitedUDream] 的 KDoc 断言「画面与桌面自定义屏保一致:全屏轮播 + 左上大字时钟」,
 * 而 gtv 线把 HomeScreen 那个 HeroClock 调用点删掉后这里从未补上,两者其实长得不一样。
 * owner 真机走查后否掉的不是「两者要不要长得一样」,是**大字时钟本身**——「我的 GTV 就是要
 * 尽可能还原 GTV 的那个样子,你加一个大时钟,整个气氛就破坏掉了」,三个候选方案里选了「只留
 * 顶栏小时钟」。所以这里不再叠 [HeroClock]:待机时不淡出的是 `HomeScreen` 顶栏自己的时钟 +
 * 字标(见其顶部 KDoc 的 `topBarClockAlpha`),不是在这一层新画一份。**Ruling R26(2026-09-21,
 * 推翻 R9)起**,系统屏保 [UnitedUDream] 画的也是同一行 `ClockWordmark`(不再是 84 sp `HeroClock`):
 * 首页待机 CLOCK_ONLY 与系统屏保留下的是同一行顶栏小字;这一层(桌面自定义屏保)按 R23 仍然
 * **不叠时钟**——照片上不浮任何 UI,`HomeScreen` 的 `topBarClockAlpha` 在自定义屏保期间也淡到 0。
 * 不要再往这里补。
 */
@Composable
fun Screensaver(active: Boolean, intervalMs: Long) {
    val ctx = LocalContext.current
    // attach / detach 严格成对:onDispose 读的是这一轮效果自己的 active(key 变了才换轮)。
    // 间隔变了也按一对 detach + attach 走——只可能在屏保不在时发生(设置页开着就不会进屏保)。
    DisposableEffect(active, intervalMs) {
        if (active) ScreensaverPlayer.attach(ctx, intervalMs)
        onDispose { if (active) ScreensaverPlayer.detach() }
    }
    val files by ScreensaverPlayer.files.collectAsState()
    // 空图库并进目标值而不是提前 return:动画状态从一开始就在组合里,首次进入才有 0→1 的淡入。
    val layerAlpha by animateFloatAsState(
        targetValue = if (active && files.isNotEmpty()) 1f else 0f,
        animationSpec = tween(if (active) 1200 else 400),
        label = "screensaverAlpha",
    )
    if (layerAlpha == 0f) return
    Box(Modifier.fillMaxSize().alpha(layerAlpha)) {
        ScreensaverContent(intervalMs = intervalMs, modifier = Modifier.fillMaxSize(), active = active)
    }
}

/**
 * 轮播层本体(spec §2),桌面自定义屏保 / 系统屏保两处共用:读 [ScreensaverPlayer] 的当前项,
 * 交给 [MotionSlideshow] 做推拉摇移 + 过渡(Ruling R95),视频项静音播放(Ruling R101)。**不画时钟**——时钟由调用方决定要不要叠:
 * 系统屏保([UnitedUDream.DreamContent])在这个组件之外单独叠一行 `ClockWordmark`(R26 起,与首页顶栏同款小字,不再是 R9 的 [HeroClock]);
 * 桌面自定义屏保([Screensaver])**不叠时钟**(Ruling R23,终审 2026-09-20,撤回 Fix R16 曾经补的那份
 * [HeroClock]——见 [Screensaver] 顶部 KDoc)。屏保图库的全屏预览(`ScreensaverPoolViewer`)是在看图,
 * 不走这里,用 [ScreensaverSlot] 的轻微放大(视频走 `PreviewVideo`)。
 * 以**文件**而不是下标作目标:删图重扫后同一个下标可能换了图,按文件比对才会过渡而不是硬切。
 * 下一项(跳过失败项)提前准备好:照片预解码,视频只 prepare 不播(R102)。
 * [active] = false(桌面屏保正在淡出)时视频当场截帧、释放播放器,淡出的是那一帧静图(R102)。
 */
@Composable
fun ScreensaverContent(intervalMs: Long, modifier: Modifier = Modifier, active: Boolean = true) {
    val files by ScreensaverPlayer.files.collectAsState()
    val index by ScreensaverPlayer.index.collectAsState()
    val round by ScreensaverPlayer.round.collectAsState()
    val i = clampIndex(index, files.size)
    val current = files.getOrNull(i)
    val upcoming = ScreensaverMedia.nextPlayable(i, files.size) { ScreensaverPlayer.isFailed(files[it]) }
        ?.let { files.getOrNull(it) }?.takeIf { it != current }
    MotionSlideshow(current, upcoming, round, intervalMs, active, modifier)
}

/** 画面上的一层(R95 照片 / R101 视频)。 */
private sealed class SlideLayer {
    abstract val path: String
}

/**
 * 一张照片:解好的位图 + 它这一段的运动 + 出现的那一帧时刻(帧时钟纳秒)。[motion] = null 是**静图**:
 * 视频之后接视频时,前一个视频的最后一帧截下来垫底(同一时刻只有一个播放器,R102),不动。
 */
private class PhotoLayer(
    override val path: String,
    val bitmap: ImageBitmap,
    val motion: KenBurns?,
    val startNanos: Long,
) : SlideLayer()

/**
 * 一个视频(R101):首帧出来之前 [startNanos] = [HIDDEN](画布已挂上、在后台准备,alpha 0),出来那一帧起淡入。
 * [round] = 正在播的那一轮([ScreensaverPlayer.round]),报事用;单视频循环时原地换成新一轮。不做推拉摇移。
 */
private class VideoLayer(override val path: String, val controller: VideoController, round: Int) : SlideLayer() {
    var round by mutableIntStateOf(round)
    var startNanos by mutableLongStateOf(HIDDEN)

    companion object { const val HIDDEN = Long.MAX_VALUE }
}

/** 预备好的下一项:照片 = 解好的位图,视频 = prepare 好、没 start 的播放器(R102)。只在效果协程里读写。 */
private class Preload {
    var path: String? = null
    var bitmap: ImageBitmap? = null
    var video: VideoController? = null

    fun takePhoto(p: String): ImageBitmap? = bitmap.takeIf { path == p }.also { if (path == p) bitmap = null; clear() }
    fun takeVideo(p: String): VideoController? = video.takeIf { path == p }.also { if (path == p) video = null; clear() }
    fun clearVideo() {
        video?.release()
        video = null
        if (bitmap == null) path = null
    }
    fun clear() {
        video?.release()
        video = null
        path = null
        bitmap = null
    }
}

/**
 * 推拉摇移 + 过渡(Ruling R95)+ 视频项(Ruling R101/R102)。
 *
 * - **动画全在绘制层**:只有一个 `withFrameNanos` 循环往 [clock] 写帧时刻,每张图的缩放 / 平移 / alpha
 *   都在 `graphicsLayer { }` 块里按「(此刻 − 出现时刻) / 时长」现算——读 [clock] 的只有图层块,
 *   每帧只更新 RenderNode 的变换属性,不重组、不重新测量、不重画位图。
 * - **最多两层**:稳定时 = 当前 + 预备的下一项;换项时预备的那一项直接变成「进来的」,
 *   旧的是「出去的」,预备槽此刻是空的;过渡结束撤掉旧的后才开始预备下一项。
 * - **过渡**:新项在旧项之上淡入([ScreensaverMotion.TRANSITION_MS]),旧项始终不透明(底层恒 alpha 1),
 *   淡完才撤——不会像两张同时半透明那样中途透出底下的壁纸 / 黑底。第一项(底下没有东西)自己淡入。
 *   照片 → 视频、视频 → 照片、视频 → 视频都是这一种(R101)。
 * - 没准备好就不换:照片解码没完成、视频首帧没出来,旧的继续;好了那一帧才开始淡入。解不出 / 播不了
 *   → 报 [SlideEvent.Failed],计时器跳过它,画面停在旧的那一项上(不黑屏)。
 * - **同一时刻一个播放器**(R102):视频之后接视频时,先把前一个的最后一帧截成静图、释放,再准备下一个;
 *   照片之后是视频才预加载(只 prepare)。[active] 为假或宿主不在前台时,预加载的播放器也放掉。
 */
@Composable
internal fun MotionSlideshow(
    target: File?,
    upcoming: File?,
    round: Int,
    intervalMs: Long,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    // 按窗口像素尺寸解码(R122:取代「长边封顶 1920」;1080p 界面结果不变,4K 界面用满 4K,长边封顶 3840)。
    // 两层叠放时各解一张(spec §9)。读 LocalConfiguration 是为了窗口尺寸变了之后下一张按新尺寸解。
    val (dstW, dstH) = rememberScreenDecodeSize()
    var layers by remember { mutableStateOf(emptyList<SlideLayer>()) }
    val preload = remember { Preload() }
    val clock = remember { mutableLongStateOf(0L) }
    val latestUpcoming by rememberUpdatedState(upcoming)
    val latestInterval by rememberUpdatedState(intervalMs)
    val latestRound by rememberUpdatedState(round)
    val playable = active && rememberHostStarted()
    val latestPlayable by rememberUpdatedState(playable)

    // 换层时被撤下的视频层当场释放播放器(不等它的组合节点被拆):紧接着的预加载要建新播放器,
    // 这样 VideoGate 不必去「收回」一个本来就要走的(R102)
    fun show(next: List<SlideLayer>) {
        for (l in layers) if (l is VideoLayer && l !in next) l.controller.release()
        layers = next
    }
    DisposableEffect(Unit) { onDispose { preload.clear() } }
    LaunchedEffect(playable) { if (!playable) preload.clearVideo() }
    LaunchedEffect(Unit) { while (true) withFrameNanos { clock.longValue = it } }

    val targetPath = target?.absolutePath
    val targetIsVideo = target != null && ScreensaverMedia.isVideo(target.name)
    // 视频按轮次重跑(单视频循环);照片只认文件(只有一张照片时不因轮次重来)
    LaunchedEffect(targetPath, if (targetIsVideo) round else -1) {
        // 上一轮没等到首帧就被换掉的视频层:撤掉,不让它当「底下那层」露出空画布
        show(layers.filter { it !is VideoLayer || it.startNanos != VideoLayer.HIDDEN })
        val path = targetPath
        if (path == null) {
            show(emptyList())
            preload.clear()
            return@LaunchedEffect
        }
        val top = layers.lastOrNull()
        if (top?.path == path) {
            if (top is VideoLayer && top.round != round) {
                // 单视频循环(R101):同一个播放器从头再来,不过渡
                top.round = round
                top.controller.restart()
            }
            if (layers.size > 1) {
                // 上一段过渡被打断:补完再撤底层
                delay(ScreensaverMotion.TRANSITION_MS.toLong())
                show(listOf(top))
            }
        } else if (targetIsVideo) {
            // 同一时刻一个播放器(R102):底下 / 前一个视频先截成静图、释放
            layers = layers.mapNotNull { l ->
                if (l !is VideoLayer) l else l.controller.snapshot()?.let { PhotoLayer(l.path, it, null, l.startNanos) }
                    .also { l.controller.release() }
            }
            val ctrl = preload.takeVideo(path)
                ?: VideoController(path, looping = false, capMs = ScreensaverMedia.VIDEO_MAX_PLAY_MS)
            val layer = VideoLayer(path, ctrl, round)
            if (ctrl.failed) {
                // 预加载时就失败了(那时还没接上报事的回调)
                ctrl.release()
                ScreensaverPlayer.report(SlideEvent.Failed(path, round))
                return@LaunchedEffect
            }
            ctrl.onStarted = { d -> ScreensaverPlayer.report(SlideEvent.Started(path, layer.round, d)) }
            ctrl.onPlayedOut = { ScreensaverPlayer.report(SlideEvent.Done(path, layer.round)) }
            ctrl.onFailed = { ScreensaverPlayer.report(SlideEvent.Failed(path, layer.round)) }
            show(listOfNotNull(layers.lastOrNull(), layer))
            try {
                // 首帧超时由播放器自己计(只在真的在播时计,退到后台不算),失败时它已经报过 Failed
                if (!ctrl.firstFrame.await()) return@LaunchedEffect
                val start = withFrameNanos { it }
                clock.longValue = start
                layer.startNanos = start
                delay(ScreensaverMotion.TRANSITION_MS.toLong())
                show(listOf(layer))
            } finally {
                if (layer.startNanos == VideoLayer.HIDDEN) {
                    show(layers - layer)
                    ctrl.release()
                }
            }
        } else {
            // 预解码没命中(删图重扫、跳号)就先扔掉它,保证同时最多两张
            val bmp = preload.takePhoto(path) ?: decodeScreensaverPhoto(path, dstW, dstH)
            if (bmp == null) {
                ScreensaverPlayer.report(SlideEvent.Failed(path, latestRound))
                return@LaunchedEffect
            }
            val start = withFrameNanos { it }
            val prev = layers.lastOrNull()
            val layer = PhotoLayer(path, bmp, ScreensaverMotion.random(Random.Default, (prev as? PhotoLayer)?.motion), start)
            clock.longValue = start
            show(listOfNotNull(prev, layer))
            delay(ScreensaverMotion.TRANSITION_MS.toLong())
            show(listOf(layer))
        }
        // 过渡结束、旧的已撤:预备下一项
        val next = latestUpcoming ?: return@LaunchedEffect
        val nextPath = next.absolutePath
        if (nextPath == path || preload.path == nextPath) return@LaunchedEffect
        if (ScreensaverMedia.isVideo(next.name)) {
            // 视频之后是视频:不预加载(同一时刻一个播放器);不在前台也不预加载
            if (layers.lastOrNull() is VideoLayer || !latestPlayable) return@LaunchedEffect
            preload.clear()
            preload.path = nextPath
            preload.video = VideoController(nextPath, looping = false, capMs = ScreensaverMedia.VIDEO_MAX_PLAY_MS)
                .also { it.prepare() }
        } else {
            preload.clear()
            val bmp = decodeScreensaverPhoto(nextPath, dstW, dstH) ?: return@LaunchedEffect
            preload.path = nextPath
            preload.bitmap = bmp
        }
    }

    Box(modifier.clipToBounds()) {
        val shown = layers
        shown.forEachIndexed { i, layer ->
            val isBottom = i == 0 && shown.size > 1
            // key:同一层从「进来的」变成「底下的」时不重建节点(视频层的 TextureView 不能被拆,拆了画布就没了)
            key(layer) {
                when (layer) {
                    is PhotoLayer -> Image(
                        bitmap = layer.bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            val elapsedMs = (clock.longValue - layer.startNanos) / 1_000_000f
                            val fade = (elapsedMs / ScreensaverMotion.TRANSITION_MS).coerceIn(0f, 1f)
                            alpha = if (isBottom) 1f else fade
                            val motion = layer.motion ?: return@graphicsLayer
                            val p = (elapsedMs / ScreensaverMotion.durationMs(latestInterval)).coerceIn(0f, 1f)
                            val extra = if (isBottom) 1f else ScreensaverMotion.transitionScale(ScreensaverMotion.TRANSITION, fade)
                            val s = motion.scaleAt(p) * extra
                            scaleX = s
                            scaleY = s
                            translationX = motion.xAt(p) * size.width
                            translationY = motion.yAt(p) * size.height
                        },
                    )
                    is VideoLayer -> VideoSurface(
                        controller = layer.controller,
                        active = playable,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            val s = layer.startNanos
                            alpha = when {
                                isBottom -> 1f
                                s == VideoLayer.HIDDEN -> 0f
                                else -> ((clock.longValue - s) / 1_000_000f / ScreensaverMotion.TRANSITION_MS).coerceIn(0f, 1f)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 当前窗口的解码目标尺寸(R122,[screenDecodeSize]):屏保(桌面 / 系统屏保)与图库全屏预览共用。
 * 订阅 [LocalConfiguration]:窗口尺寸变了(换分辨率、`wm size`)之后重算。
 */
@Composable
internal fun rememberScreenDecodeSize(): Pair<Int, Int> {
    val ctx = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        ctx.resources.displayMetrics.let { screenDecodeSize(it.widthPixels, it.heightPixels) }
    }
}

/**
 * 屏保照片解码(Ruling R95):尺寸按 [ScreensaverMotion.decodePlan] 缩到「Crop 铺满屏幕」为止,不再像
 * [Apps.decodeScaled] 那样只按 2 的幂采样、可能留下接近两倍屏幕的大图。失败 → null。
 *
 * **R125:ARGB_8888 + 增益图**(R125 之前是 RGBA_F16、解不出再回落 8888)。HDR 只靠增益图:`BitmapFactory` 不论
 * 底图格式都把 Ultra HDR 的增益图挂上(同一个 inSampleSize / 缩放一起缩),底图存的永远是 8 位 JPEG 的 SDR 画面,
 * F16 只是把它加宽成每像素 8 字节——内存与显存翻倍,多不出一点 HDR。照片格式只收 jpg / png / webp(8 位)。
 * 4K 界面同时两张时,F16 是 2 × 66 MB,8888 是 2 × 33 MB(+ 单通道增益图各 8 MB)。
 */
private suspend fun decodeScreensaverBitmap(path: String, dstW: Int, dstH: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // 内置屏保图(R117)是伪路径,decodeImagePath 认出来改读 assets
            decodeImagePath(path, bounds)
            val (sample, density, target) =
                ScreensaverMotion.decodePlan(bounds.outWidth, bounds.outHeight, dstW, dstH)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                if (density > 0) {
                    inScaled = true
                    inDensity = density
                    inTargetDensity = target
                }
            }
            decodeImagePath(path, opts)?.also {
                android.util.Log.i("UnitedU", "屏保照片 ${File(path).name} 目标 ${dstW}x$dstH → ${HdrGainmaps.describe(it)}")
            }
        }.onFailure { android.util.Log.w("UnitedU", "屏保照片解码失败 $path", it) }.getOrNull()
    }

private suspend fun decodeScreensaverPhoto(path: String, dstW: Int, dstH: Int): ImageBitmap? =
    decodeScreensaverBitmap(path, dstW, dstH)?.asImageBitmap()

/**
 * 屏保图库全屏预览([ImagePicker.kt] 的 `ScreensaverPoolViewer`)的单张呈现:与屏保同一个解码([decodeScreensaverBitmap]:
 * ARGB_8888 + 增益图,按窗口尺寸 Crop 铺满为止,R122 / R125;之前是 RGBA_F16、封顶 1920×1080 的 2 的幂采样)。
 * 那里是在看图,只保留旧版的轻微放大(1.00 → [Theme.ScreensaverZoom],时长 = 间隔 + 过渡);
 * 屏保本体的推拉摇移见 [MotionSlideshow]。缩放在 `graphicsLayer` 块里读,不逐帧重组。
 */
@Composable
internal fun ScreensaverSlot(file: File?, intervalMs: Long) {
    file ?: return
    val (dstW, dstH) = rememberScreenDecodeSize()
    val bmp by produceState<Bitmap?>(null, file.absolutePath, dstW, dstH) {
        value = decodeScreensaverBitmap(file.absolutePath, dstW, dstH)
    }
    val b = bmp ?: return
    val scale = remember { Animatable(1.0f) }
    LaunchedEffect(file.absolutePath) {
        scale.snapTo(1.0f)
        scale.animateTo(
            targetValue = Theme.ScreensaverZoom,
            animationSpec = tween(
                durationMillis = ScreensaverMotion.durationMs(intervalMs).toInt(),
                easing = LinearEasing,
            ),
        )
    }
    Image(
        bitmap = b.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        },
    )
}
