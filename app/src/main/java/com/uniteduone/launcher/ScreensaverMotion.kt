package com.uniteduone.launcher

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 屏保照片之间怎么换(Ruling R95)。[CROSSFADE] = 新图在旧图之上 0 → 1 淡入、旧图保持不透明,
 * 淡完才撤掉旧图(不像 Compose `Crossfade` 两张同时半透明,中途不会透出底下的壁纸 / 黑底);
 * [ZOOM_FADE] = 同样的淡入,新图另外从 [ScreensaverMotion.ZOOM_FADE_FROM] 倍缩回 1 倍,像是「落」进画面。
 */
enum class ScreensaverTransition { CROSSFADE, ZOOM_FADE }

/**
 * 一张照片在它那一段时间里的推拉摇移(Ken Burns)。缩放以画面中心为轴;平移是**画面宽 / 高的分数**,
 * 正数向右 / 向下。照片先按 `ContentScale.Crop` 铺满画面再套这套变换,所以「不露边」的条件只看画面:
 * 缩放 s 时每边多出 (s − 1) / 2,平移不超过它就不露边([covers])。
 */
data class KenBurns(
    val startScale: Float,
    val endScale: Float,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    /** 生成它的那一种组合(0 until [ScreensaverMotion.COMBOS]),只用来让相邻两张不重样。 */
    val combo: Int,
) {
    fun scaleAt(p: Float): Float = lerp(startScale, endScale, p)
    fun xAt(p: Float): Float = lerp(startX, endX, p)
    fun yAt(p: Float): Float = lerp(startY, endY, p)

    /**
     * 两端都不露边。缩放与平移都随进度线性变化,「|平移| ≤ (缩放 − 1) / 2」这个集合是凸的,
     * 所以两端满足 = 全程满足(换成缓动曲线也一样:缓动只改变沿直线走的快慢,不离开这条线段)。
     */
    fun covers(): Boolean =
        fits(startScale, startX) && fits(startScale, startY) && fits(endScale, endX) && fits(endScale, endY)

    private fun fits(s: Float, o: Float) = abs(o) <= (s - 1f) / 2f + 1e-6f
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * 屏保照片动感的全部数值(Ruling R95,2026-09-27 Gordon:「本质上还是静态照片,要明显的动感」)。
 * 取代 R95 之前的「每张从 1.00 线性放大到 1.08、不平移」(`Theme.ScreensaverZoom`,现在只剩图库预览在用)。
 */
object ScreensaverMotion {
    /** 小的那一端的缩放。平移最多 [PAN_MAX] 时,两端各自的「不露边」由 [placeOnAxis] 摆位保证。 */
    const val ZOOM_NEAR = 1.06f
    /** 大的那一端的缩放:比 [ZOOM_NEAR] 大 15%(1.06 × 1.15 ≈ 1.22),推拉幅度是旧版 1.08 的约两倍。 */
    const val ZOOM_FAR = 1.22f
    /** 每张照片沿对角线平移的距离,按画面宽(横向)/ 高(纵向)的分数,在这个区间里随机取。 */
    const val PAN_MIN = 0.05f
    const val PAN_MAX = 0.08f
    /** 推 / 拉 × 四条对角线(左上→右下、右上→左下、左下→右上、右下→左上)= 8 种组合。 */
    const val COMBOS = 8

    /** 照片之间的过渡时长。旧版 Compose Crossfade 是 2000 ms。 */
    const val TRANSITION_MS = 1400
    /** 默认过渡方式;想换成 [ScreensaverTransition.ZOOM_FADE] 改这一处。 */
    val TRANSITION = ScreensaverTransition.CROSSFADE
    /** [ScreensaverTransition.ZOOM_FADE] 里新图的起始倍数(再乘在 Ken Burns 缩放之上,≥ 1 所以不会露边)。 */
    const val ZOOM_FADE_FROM = 1.06f

    /**
     * 一张照片的运动总时长 = 换图间隔 + 过渡:淡入时已经在动,下一张淡入盖住它的那 [TRANSITION_MS] 里
     * 还在动,整段走完才停(停的那一刻正好被下一张盖满)。
     */
    fun durationMs(intervalMs: Long): Long = intervalMs + TRANSITION_MS

    /** 过渡进度 → 新图的额外缩放倍数。CROSSFADE 恒 1;ZOOM_FADE 从 [ZOOM_FADE_FROM] 线性缩回 1。 */
    fun transitionScale(style: ScreensaverTransition, fade: Float): Float = when (style) {
        ScreensaverTransition.CROSSFADE -> 1f
        ScreensaverTransition.ZOOM_FADE -> lerp(ZOOM_FADE_FROM, 1f, fade.coerceIn(0f, 1f))
    }

    /**
     * 随机生成一张照片的运动。组合与上一张([previous])不同;平移距离、推还是拉、哪条对角线都随机。
     * 组合编号:bit 2 = 推(放大,0)/ 拉(缩小,1);bit 0 = 横向往右(0)/ 往左(1);bit 1 = 纵向往下(0)/ 往上(1)。
     */
    fun random(random: Random, previous: KenBurns? = null): KenBurns {
        var combo = random.nextInt(COMBOS)
        if (previous != null && combo == previous.combo) combo = (combo + 1 + random.nextInt(COMBOS - 1)) % COMBOS
        return motionFor(combo, pan = PAN_MIN + (PAN_MAX - PAN_MIN) * random.nextFloat())
    }

    /** 确定性的那一半(单测直接喂组合与平移距离)。 */
    fun motionFor(combo: Int, pan: Float): KenBurns {
        val zoomOut = combo and 4 != 0
        val dirX = if (combo and 1 == 0) 1f else -1f
        val dirY = if (combo and 2 == 0) 1f else -1f
        val s0 = if (zoomOut) ZOOM_FAR else ZOOM_NEAR
        val s1 = if (zoomOut) ZOOM_NEAR else ZOOM_FAR
        val (x0, x1) = placeOnAxis(s0, s1, pan * dirX)
        val (y0, y1) = placeOnAxis(s0, s1, pan * dirY)
        return KenBurns(s0, s1, x0, y0, x1, y1, combo)
    }

    /**
     * 在一条轴上摆放起点 a 与终点 b = a + [delta]:两端各自不露边(|a| ≤ (s0 − 1)/2,|b| ≤ (s1 − 1)/2),
     * 在可行区间里取最接近「以画面中心对称」(a = −delta / 2)的那一个。缩放小的一端余量小,
     * 所以路径会偏向缩放大的那一端——这正是 Ken Burns 该有的样子:推近的同时滑向某个角。
     * 可行区间为空(平移超过两端余量之和)时退而把两端都夹进各自的余量,平移距离打折但绝不露边。
     */
    internal fun placeOnAxis(s0: Float, s1: Float, delta: Float): Pair<Float, Float> {
        val r0 = (s0 - 1f) / 2f
        val r1 = (s1 - 1f) / 2f
        val lo = max(-r0, -r1 - delta)
        val hi = min(r0, r1 - delta)
        if (lo > hi) return (-delta / 2f).coerceIn(-r0, r0) to (delta / 2f).coerceIn(-r1, r1)
        val a = (-delta / 2f).coerceIn(lo, hi)
        return a to a + delta
    }

    /**
     * 按屏幕尺寸解码(Ruling R95,32 位 MTK 的内存):先用 2 的幂 inSampleSize 缩到**仍不小于**
     * 覆盖屏幕所需的尺寸,再用 inDensity / inTargetDensity 精确缩到「按 Crop 铺满屏幕」的大小
     * ——只靠 inSampleSize 时 3000×2000 的图会原尺寸解码(RGBA_F16 下 48 MB)。
     * 返回 (inSampleSize, inDensity, inTargetDensity);不需要再缩时后两者为 0(不设)。
     */
    internal fun decodePlan(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Triple<Int, Int, Int> {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return Triple(1, 0, 0)
        var sample = 1
        while (srcW / (sample * 2) >= dstW && srcH / (sample * 2) >= dstH) sample *= 2
        val w = srcW / sample
        val h = srcH / sample
        // Crop 铺满需要的缩放 = max(dstW / w, dstH / h);≥ 1 说明已经不比屏幕大,不再缩(不放大)
        val cover = max(dstW.toFloat() / w, dstH.toFloat() / h)
        if (cover >= 0.95f) return Triple(sample, 0, 0)
        // 以宽 / 高里决定 cover 的那一条作密度比:inTargetDensity / inDensity = cover
        return if (dstW.toFloat() / w >= dstH.toFloat() / h) Triple(sample, w, dstW) else Triple(sample, h, dstH)
    }
}
