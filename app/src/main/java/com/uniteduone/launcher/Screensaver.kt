package com.uniteduone.launcher

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
 * 轮播层本体(spec §2),桌面自定义屏保 / 系统屏保 / 屏保图库全屏预览三处共用:读 [ScreensaverPlayer]
 * 的当前图,交叉淡入 + Ken Burns。**不画时钟**——时钟由调用方决定要不要叠,三个调用方现在各不相同:
 * 系统屏保([UnitedUDream.DreamContent])在这个组件之外单独叠一行 `ClockWordmark`(R26 起,与首页顶栏同款小字,不再是 R9 的 [HeroClock]);
 * 桌面自定义屏保([Screensaver])**不叠时钟**(Ruling R23,终审 2026-09-20,撤回 Fix R16 曾经补的那份
 * [HeroClock]——见 [Screensaver] 顶部 KDoc);屏保图库的全屏预览([ImagePicker.kt] 的
 * `ScreensaverPoolViewer`)是在看图,不是在展示待机画面,同样不叠时钟。
 * 以**文件**而不是下标作 Crossfade 的目标:删图重扫后同一个下标可能换了图,按文件比对才会淡入而不是硬切。
 */
@Composable
fun ScreensaverContent(intervalMs: Long, modifier: Modifier = Modifier) {
    val files by ScreensaverPlayer.files.collectAsState()
    val index by ScreensaverPlayer.index.collectAsState()
    val current = files.getOrNull(clampIndex(index, files.size))
    Box(modifier) {
        Crossfade(
            targetState = current,
            animationSpec = tween(Theme.ScreensaverCrossfadeMs),
            label = "screensaverCrossfade",
        ) { file ->
            ScreensaverSlot(file, intervalMs)
        }
    }
}

/**
 * 每张图的呈现(spec §2「不变」):RGBA_F16 解码保留 Ultra HDR gain map;解码尺寸封顶 1920×1080
 * ——桌面与系统屏保叠放时两层各解一张,别再放大内存(spec §9)。Ken Burns 放大到 1.08,
 * 时长 = 轮播间隔 + 交叉淡入(原来写死 30 s + 2 s)。屏保图库的全屏预览也用它(间隔传 `Theme.ScreensaverIntervalMs`)。
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
                durationMillis = (intervalMs + Theme.ScreensaverCrossfadeMs).toInt(),
                easing = LinearEasing,
            ),
        )
    }
    Image(
        bitmap = b.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().scale(scale.value),
    )
}
