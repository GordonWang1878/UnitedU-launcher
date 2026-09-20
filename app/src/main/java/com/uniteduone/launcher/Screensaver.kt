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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 桌面的自定义屏保层(spec §1.4 第 2 层):[active] 为真时 attach 播放器、淡入 1200 ms,为假时 detach、淡出 400 ms。
 * 计时、扫描、下标都在 [ScreensaverPlayer];这里只管淡入淡出与 attach / detach 配对。
 * 图库为空时什么都不画(播放器扫出空表,alpha 目标就是 0,[HeroClock] 跟着不出现——这一点与
 * [UnitedUDream] 图库为空时「黑底 + 时钟」不同:桌面这一层叠在真实壁纸上面,图库空着不该拿黑底
 * 盖住壁纸,那是 [MainActivity] 的 BLACK 待机层的职责,不是这里)。
 *
 * **Fix R16(终审 2026-09-20)**:恢复丢失的 [HeroClock] 叠加。[UnitedUDream] 自己的 KDoc 一直
 * 断言「画面与桌面的自定义屏保一致——全屏轮播 + 左上大字时钟」,但 gtv 线把 HomeScreen 那个
 * HeroClock 调用点删掉后,这里从未补上、两者其实长得不一样了(见本文件改之前这里的 KDoc,
 * 如实记录过这个缺口)。现在与 [UnitedUDream.DreamContent] 同一处理:淡阴影([shadow] 恒
 * true——照片可能很亮)、同一个左上角坐标;跟着 [layerAlpha] 一起淡入淡出,不额外起一份动画,
 * 图库为空时随 `layerAlpha` 一起不出现(上一段的理由)。[showDate] 与 HomeScreen / UnitedUDream
 * 同源(MainActivity 传 `homeSettings.showDate`),三处保持同一个开关。
 */
@Composable
fun Screensaver(active: Boolean, intervalMs: Long, showDate: Boolean = true) {
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
        HeroClock(
            showDate = showDate,
            shadow = true,
            modifier = Modifier.padding(start = GtvLayout.CONTENT_KEYLINE.dp, top = HomeLayout.HERO_TOP.dp),
        )
    }
}

/**
 * 轮播层本体(spec §2),桌面自定义屏保 / 系统屏保 / 屏保图库全屏预览三处共用:读 [ScreensaverPlayer]
 * 的当前图,交叉淡入 + Ken Burns。**不画时钟**——时钟由调用方叠,不是每个调用方都需要:
 * 系统屏保([UnitedUDream.DreamContent])与桌面自定义屏保([Screensaver],Fix R16 补回)都在这个
 * 组件之外单独叠一层 [HeroClock];屏保图库的全屏预览([ImagePicker.kt] 的 `ScreensaverPoolViewer`)
 * 是在看图,不是在展示待机画面,不叠时钟。
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
