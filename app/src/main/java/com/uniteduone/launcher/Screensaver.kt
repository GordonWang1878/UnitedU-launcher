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
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
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
        ScreensaverContent(intervalMs = intervalMs, modifier = Modifier.fillMaxSize())
    }
}

/**
 * 轮播层本体(spec §2),桌面自定义屏保 / 系统屏保两处共用:读 [ScreensaverPlayer] 的当前图,
 * 交给 [MotionSlideshow] 做推拉摇移 + 过渡(Ruling R95)。**不画时钟**——时钟由调用方决定要不要叠:
 * 系统屏保([UnitedUDream.DreamContent])在这个组件之外单独叠一行 `ClockWordmark`(R26 起,与首页顶栏同款小字,不再是 R9 的 [HeroClock]);
 * 桌面自定义屏保([Screensaver])**不叠时钟**(Ruling R23,终审 2026-09-20,撤回 Fix R16 曾经补的那份
 * [HeroClock]——见 [Screensaver] 顶部 KDoc)。屏保图库的全屏预览(`ScreensaverPoolViewer`)是在看图,
 * 不走这里,用 [ScreensaverSlot] 的轻微放大。
 * 以**文件**而不是下标作目标:删图重扫后同一个下标可能换了图,按文件比对才会过渡而不是硬切。
 * 下一张(播放器下标 + 1)提前解码好,换图时直接淡入,不等解码。
 */
@Composable
fun ScreensaverContent(intervalMs: Long, modifier: Modifier = Modifier) {
    val files by ScreensaverPlayer.files.collectAsState()
    val index by ScreensaverPlayer.index.collectAsState()
    val i = clampIndex(index, files.size)
    val current = files.getOrNull(i)
    val upcoming = files.getOrNull(nextIndex(i, files.size))?.takeIf { it != current }
    MotionSlideshow(current, upcoming, intervalMs, modifier)
}

/** 画面上的一张照片:解好的位图 + 它这一段的运动 + 出现的那一帧时刻(帧时钟纳秒)。 */
private class PhotoLayer(val path: String, val bitmap: ImageBitmap, val motion: KenBurns, val startNanos: Long)

/** 预解码的下一张。普通字段而非 State:只在效果协程里读写,不参与绘制。 */
private class Preload {
    var path: String? = null
    var bitmap: ImageBitmap? = null

    fun take(p: String): ImageBitmap? = bitmap.takeIf { path == p }.also { clear() }
    fun clear() { path = null; bitmap = null }
}

/**
 * 推拉摇移 + 过渡(Ruling R95)。
 *
 * - **动画全在绘制层**:只有一个 `withFrameNanos` 循环往 [clock] 写帧时刻,每张图的缩放 / 平移 / alpha
 *   都在 `graphicsLayer { }` 块里按「(此刻 − 出现时刻) / 时长」现算——读 [clock] 的只有图层块,
 *   每帧只更新 RenderNode 的变换属性,不重组、不重新测量、不重画位图。
 * - **最多两张位图**:稳定时 = 当前 + 预解码的下一张;换图时预解码那张直接变成「进来的」,
 *   旧图是「出去的」,预解码槽此刻是空的;过渡结束撤掉旧图后才开始解下一张。
 * - **过渡**:新图在旧图之上淡入([ScreensaverMotion.TRANSITION_MS]),旧图始终不透明(底层恒 alpha 1),
 *   淡完才撤——不会像两张同时半透明那样中途透出底下的壁纸 / 黑底。第一张(底下没有图)自己淡入。
 * - 解码没完成就不换:旧图继续动,新图解好那一帧才开始淡入(原来 Crossfade 是先开始淡、图还没解出来)。
 */
@Composable
internal fun MotionSlideshow(target: File?, upcoming: File?, intervalMs: Long, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val dm = ctx.resources.displayMetrics
    // 按屏幕尺寸解码,长边封顶 1920(4K 面板的 UI 多半仍是 1080p;两层叠放时各解一张,spec §9)
    val k = min(1f, 1920f / max(dm.widthPixels, dm.heightPixels).coerceAtLeast(1))
    val dstW = (dm.widthPixels * k).toInt().coerceAtLeast(1)
    val dstH = (dm.heightPixels * k).toInt().coerceAtLeast(1)
    var layers by remember { mutableStateOf(emptyList<PhotoLayer>()) }
    val preload = remember { Preload() }
    val clock = remember { mutableLongStateOf(0L) }
    val latestUpcoming by rememberUpdatedState(upcoming)
    val latestInterval by rememberUpdatedState(intervalMs)

    LaunchedEffect(Unit) { while (true) withFrameNanos { clock.longValue = it } }
    LaunchedEffect(target?.absolutePath) {
        val path = target?.absolutePath
        if (path == null) {
            layers = emptyList()
            preload.clear()
            return@LaunchedEffect
        }
        if (layers.lastOrNull()?.path != path) {
            // 预解码没命中(删图重扫、跳号)就先扔掉它,保证同时最多两张
            val bmp = preload.take(path) ?: decodeScreensaverPhoto(path, dstW, dstH) ?: return@LaunchedEffect
            val start = withFrameNanos { it }
            val prev = layers.lastOrNull()
            val layer = PhotoLayer(path, bmp, ScreensaverMotion.random(Random.Default, prev?.motion), start)
            clock.longValue = start
            layers = listOfNotNull(prev, layer)
            delay(ScreensaverMotion.TRANSITION_MS.toLong())
            layers = listOf(layer)
        }
        // 过渡结束、旧图已撤:预解码下一张
        val next = latestUpcoming?.absolutePath ?: return@LaunchedEffect
        if (next == path || preload.path == next) return@LaunchedEffect
        preload.clear()
        val bmp = decodeScreensaverPhoto(next, dstW, dstH) ?: return@LaunchedEffect
        preload.path = next
        preload.bitmap = bmp
    }

    Box(modifier.clipToBounds()) {
        val shown = layers
        shown.forEachIndexed { i, layer ->
            val isBottom = i == 0 && shown.size > 1
            // key:同一张图从「进来的」变成「底下的」时不重建 Image 节点
            key(layer.path, layer.startNanos) {
                Image(
                    bitmap = layer.bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        val elapsedMs = (clock.longValue - layer.startNanos) / 1_000_000f
                        val p = (elapsedMs / ScreensaverMotion.durationMs(latestInterval)).coerceIn(0f, 1f)
                        val fade = (elapsedMs / ScreensaverMotion.TRANSITION_MS).coerceIn(0f, 1f)
                        val extra = if (isBottom) 1f else ScreensaverMotion.transitionScale(ScreensaverMotion.TRANSITION, fade)
                        val s = layer.motion.scaleAt(p) * extra
                        scaleX = s
                        scaleY = s
                        translationX = layer.motion.xAt(p) * size.width
                        translationY = layer.motion.yAt(p) * size.height
                        alpha = if (isBottom) 1f else fade
                    },
                )
            }
        }
    }
}

/**
 * 屏保照片解码(Ruling R95):RGBA_F16 保留 Ultra HDR gain map(spec §2「不变」);尺寸按
 * [ScreensaverMotion.decodePlan] 缩到「Crop 铺满屏幕」为止,不再像 [Apps.decodeScaled] 那样只按
 * 2 的幂采样、可能留下接近两倍屏幕的大图。F16 解不出来回落 ARGB_8888。失败 → null。
 */
private suspend fun decodeScreensaverPhoto(path: String, dstW: Int, dstH: Int): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val (sample, density, target) =
                ScreensaverMotion.decodePlan(bounds.outWidth, bounds.outHeight, dstW, dstH)
            fun opts(config: Bitmap.Config) = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = config
                if (density > 0) {
                    inScaled = true
                    inDensity = density
                    inTargetDensity = target
                }
            }
            (BitmapFactory.decodeFile(path, opts(Bitmap.Config.RGBA_F16))
                ?: BitmapFactory.decodeFile(path, opts(Bitmap.Config.ARGB_8888)))
                ?.asImageBitmap()
        }.onFailure { android.util.Log.w("UnitedU", "屏保照片解码失败 $path", it) }.getOrNull()
    }

/**
 * 屏保图库全屏预览([ImagePicker.kt] 的 `ScreensaverPoolViewer`)的单张呈现:RGBA_F16 解码保留 Ultra HDR
 * gain map;解码尺寸封顶 1920×1080。那里是在看图,只保留旧版的轻微放大(1.00 → [Theme.ScreensaverZoom],
 * 时长 = 间隔 + 过渡);屏保本体的推拉摇移见 [MotionSlideshow]。缩放在 `graphicsLayer` 块里读,不逐帧重组。
 */
@Composable
internal fun ScreensaverSlot(file: File?, intervalMs: Long) {
    file ?: return
    val bmp by produceState<Bitmap?>(null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                Apps.decodeScaled(file.absolutePath, 1920, 1080, Bitmap.Config.RGBA_F16)
            }.getOrNull()
        }
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
