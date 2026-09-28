package com.uniteduone.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush

/**
 * **Ruling R110(2026-09-28 夜间性能,A95L 实测)**:首页背景(壁纸 + R85 竖直压暗渐变)改成**两张缓存图层**画。
 *
 * **为什么**:A95L(MT5897 / Mali-G57)上首页 GPU 的大头不是卡片,是两道**全屏**绘制——壁纸一道、R85 渐变一道,
 * 每帧都在 1920×1080 上各跑一遍(消融:去掉渐变 GPU 中位 16.6 → 10.2 ms,两道都去掉 → 3.0 ms;卡片的淡化 /
 * 透明度离屏层合计量不出差别)。这两道的内容在静止与左右移动时一帧都不变,变的只有「浏览态变暗」的壁纸 alpha。
 *
 * **怎么画**(数学等价,不是近似):原来每个像素是 `G·M + (1−G)·α·W`(黑底上画 α 的壁纸 W,再盖渐变 G·M)。
 * 它对 α **线性**,α 又恒在 `[B, 1]`(B = [GtvLayout.WALLPAPER_BROWSE_ALPHA],`wallpaperAlpha` 的值域),
 * 所以 = `lerp(P_B, P_1, t)`,`t = (α − B)/(1 − B)`,其中 `P_a` 是「黑底上 a·W 再盖渐变」这一整张图。
 * 两张 `P` 各放进一个 `CompositingStrategy.Offscreen` 图层(HWUI 的硬件层:内容不变就**不重画**,每帧只合成一张纹理):
 * 下层 `P_B` 不透明地铺,上层 `P_1` 以 alpha t 盖上去——静止在首行 t = 1 只合成上层一张,静止在其余行 t = 0
 * 只合成下层一张,只有换行那几百毫秒两张都合成。原来那两道全屏着色(F16 壁纸取样 + 21 个 stop 的渐变)
 * 只在图层内容变了(换壁纸、进出待机)时各重跑一次。
 *
 * **观感**:A95L 上同一状态截屏逐像素比(raw screencap):首行静止最大差 1/255(整屏 8 个像素)、行 1 / 行 2 静止
 * ≤ 2/255(8 位量化的舍入差:图层里先舍入一次再合成),见 `docs/design/perf-2026-09-28.md`。
 *
 * **渐变什么时候进图层**([gradientBaked]):只在首页的 `contentAlpha` 恰为 1、自定义屏保与全黑待机层都没在画时。
 * 其余时候(待机 / 屏保淡入淡出、编辑页、没有壁纸)渐变照旧由 HomeScreen 自己画——那几种情形下屏保 / 黑层
 * 夹在壁纸与首页之间,渐变挪到它们下面会改变叠放顺序。两边读的是同一个 [HomeBackdropBridge],
 * 都在**绘制阶段**读,同一帧里要么图层画、要么首页画,不会两边都画或都不画。
 *
 * **R124(2026-09-28,HDR 壁纸)**:带增益图的壁纸画进这两张图层,HDR 能不能保留?按 AOSP android14-release 的 HWUI:
 * - 图层里画位图与直接画进窗口走**同一段**增益图代码(`DrawGainmapBitmap`):图层是 8 位、色彩空间取窗口管线的
 *   「扩展范围 Display P3」,提亮权重读渲染线程当前的 HDR/SDR 比例——**创建图层时的比例下,HDR 头部空间完整保留**
 *   (模拟器探针实测,比例 4:直接画与图层画的峰值都是 SDR 白的 3.86 倍,线性误差最大 0.035——图层多一次 8 位量化;
 *   见 `docs/design/hdr-image-spec.md` 验证记录)。
 * - 但图层的色彩空间**只在创建时定一次**(`SkiaPipeline::createOrUpdateLayer` 只在尺寸变了才重建),内容也只在
 *   显示列表变了才重画;比例变了(手机调亮度时持续爬升;电视进出 HDR 输出)图层不会自己跟上:在比例 1 时建的
 *   图层,之后比例再高也只有 SDR 的范围,高光被截在 SDR 白(探针实测:切到比例 4 后峰值仍是 0.95,与 SDR 相同)。
 * - 所以 `Wallpaper` 用 [rememberHdrRatioBucket] 监听显示器的 HDR/SDR 比例,**跨档(1/8 档光圈)就整组重建图层**
 *   (`key(档位)`:新 RenderNode → 按新比例新建图层、画一次)。只对带增益图的壁纸生效;没有增益图 / API < 34 /
 *   显示器不报比例时档位恒 0,与 R110 完全相同。代价:跨档那一帧两张图层各重画一次(约 R110 之前两帧的背景开销),
 *   比例不变时为 0。
 * - 不改成直接画进窗口:那样每帧都要跑一遍全屏增益图着色器(每像素两次取样 + 对数运算),R110 省下的又回来了。
 */
class HomeBackdropBridge {
    /** HomeScreen 在每次组合后上报的 `contentAlpha`;HomeScreen 不在组合里(编辑页)时为 0。 */
    var homeContentAlpha by mutableFloatStateOf(0f)
    /** 壁纸层此刻是否走两张缓存图层的画法(有位图时才走;没壁纸时画纯深色,渐变由首页画)。 */
    var wallpaperLayered by mutableStateOf(false)
}

/** R110:浏览态变暗的 alpha → 上层图层的合成 alpha(见 [HomeBackdropBridge] 的推导)。纯函数。 */
internal fun backdropLerp(alpha: Float, floor: Float = GtvLayout.WALLPAPER_BROWSE_ALPHA): Float =
    ((alpha - floor) / (1f - floor)).coerceIn(0f, 1f)

/** R85 首页竖直压暗渐变的笔刷。首页与 R110 的背景图层共用这一份,两处画出来的渐变逐 stop 相同。 */
internal fun homeFadeBrush(): Brush = Brush.verticalGradient(
    *Array(GtvTokens.HOME_FADE_STOPS + 1) { i ->
        val t = i.toFloat() / GtvTokens.HOME_FADE_STOPS
        t to GtvTokens.MenuBg.copy(alpha = GtvTokens.homeFadeAlpha(t))
    },
)

/** 铺满的 R85 渐变;[visible] 在绘制阶段读,返回 false 时这一帧不画(节点仍在,切换不重组)。 */
@Composable
internal fun HomeFadeGradient(brush: Brush, visible: () -> Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent { if (visible()) drawContent() }
            .background(brush),
    )
}
