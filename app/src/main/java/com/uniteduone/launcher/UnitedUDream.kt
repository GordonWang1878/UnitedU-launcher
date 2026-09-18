package com.uniteduone.launcher

import android.content.pm.ActivityInfo
import android.service.dreams.DreamService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 系统屏保(M5 spec §4):UnitedU 在电视「屏幕保护程序」列表里的那一项。画面与桌面的自定义屏保一致——
 * 全屏轮播屏保图库 + 左上大字时钟(淡阴影);图库为空时黑底 + 时钟。与桌面**共用播放器和播放进度**
 * ([ScreensaverPlayer] 的 attach 不重置下标):桌面先进了屏保、系统屏保随后接管时,从同一张接着播。
 *
 * **Compose 宿主**(spec §9 风险一):DreamService 不是 LifecycleOwner,ComposeView 挂上窗口时要沿 View 树
 * 找到 LifecycleOwner 与 SavedStateRegistryOwner,少一个就在 attach 时崩。所以这里自己当这两个 owner,
 * 在 setContentView **之前**把它们设到 ComposeView 与 window.decorView 上。生命周期:
 * onCreate → CREATED;onDreamingStarted → RESUMED(帧时钟随 ON_START 恢复,动画才会动)+ attach;
 * onDreamingStopped → CREATED + detach;onDetachedFromWindow → DESTROYED(detach 兜底)。
 *
 * **冷进程**(spec §9 风险二):系统屏保可能在 UnitedU 进程不在时被拉起,MainActivity 没跑过——
 * 语言(AppLocale)、主题色、间隔都在这里自己从 settings.json 读;外置存储没挂时 SettingsStore 给默认值、
 * 播放器扫出空图库 → 黑底 + 时钟。任意键结束屏保是系统行为(isInteractive = false),这里不处理按键。
 */
class UnitedUDream : DreamService(), SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /**
     * 这一次屏保是否拿着播放器的一个引用。attach / detach 必须成对:多 detach 一次会抢走桌面那一份。
     * 两条路把它写回 false(onDreamingStopped、onDetachedFromWindow 兜底),不是只有一条窄路的闩(铁律 7)。
     */
    private var holdsPlayer = false
    private var intervalMs = Theme.ScreensaverIntervalMs

    override fun onCreate() {
        super.onCreate()
        // savedstate 规定:performRestore 必须在 owner 离开 INITIALIZED 之前调,之后调会抛。
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        // 与 MainActivity 同:RGBA_F16 解码的 Ultra HDR 图要 HDR 窗口才亮得出来(画面一致,spec §4)。
        window.colorMode = ActivityInfo.COLOR_MODE_HDR
        // 冷进程时 MainActivity.attachBaseContext 没跑过:AppLocale 还是 null,日期的星期会跟系统语言走。
        val settings = SettingsStore.read(this)
        AppLocale.current = localeFor(settings.language)
        val interval = settings.screensaverIntervalMs
        intervalMs = interval
        val view = ComposeView(this)
        // 两个 owner 先设再 setContentView:ComposeView 在挂上窗口那一刻沿 View 树往上找它们。
        window.decorView.setViewTreeLifecycleOwner(this)
        window.decorView.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setContent { DreamContent(settings, interval) }
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        if (!holdsPlayer) {
            ScreensaverPlayer.attach(this, intervalMs)
            holdsPlayer = true
        }
    }

    override fun onDreamingStopped() {
        releasePlayer()
        // 只从「开着」退回 CREATED:个别固件先拆窗(已 DESTROYED)再报停,不能倒着走回 CREATED。
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        // 兜底:拆窗前没收到 onDreamingStopped 时,引用也不能漏还。
        releasePlayer()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }

    /**
     * 第二重兜底(终审 Minor 1):个别固件销毁 service 时不先拆窗(decor 未 detach),
     * `onDetachedFromWindow` 那条路不会跑,registry 停不到 DESTROYED、ComposeView 挂的
     * window recomposer job 就一直活着。`releasePlayer()` 自己按 `holdsPlayer` 幂等,
     * `currentState` 的判断避免同一个实例被重复置一次已经到达的终态。
     */
    override fun onDestroy() {
        releasePlayer()
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        super.onDestroy()
    }

    private fun releasePlayer() {
        if (holdsPlayer) {
            ScreensaverPlayer.detach()
            holdsPlayer = false
        }
    }
}

/**
 * 系统屏保的画面:与桌面自定义屏保同一个轮播层 + 同一个大字时钟,位置与首页相同(左 58 dp、顶 150 dp)。
 * 图库为空 → 黑底 + 时钟,不加阴影(黑底上不需要,spec §4)。主题色走 [rememberThemeColors],与 MainActivity 同一条路。
 */
@Composable
private fun DreamContent(settings: Settings, intervalMs: Long) {
    val colors = rememberThemeColors(LocalContext.current, settings)
    UnitedUTheme(colors) {
        CompositionLocalProvider(LocalThemeColors provides colors) {
            val files by ScreensaverPlayer.files.collectAsState()
            val hasImages = files.isNotEmpty()
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (hasImages) ScreensaverContent(intervalMs = intervalMs, modifier = Modifier.fillMaxSize())
                HeroClock(
                    showDate = settings.showDate,
                    shadow = hasImages,
                    modifier = Modifier.padding(start = Theme.SidePadding, top = HomeLayout.HERO_TOP.dp),
                )
            }
        }
    }
}
