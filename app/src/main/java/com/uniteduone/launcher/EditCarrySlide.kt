package com.uniteduone.launcher

import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.Job

/**
 * R167 拿起搬运的位置过渡(报告 perf-2026-10-10 #8)。卡片的组合身份仍按位置(第 ci 格),焦点账本不动;
 * 换位只是**绘制**位移:某一格换上了原来在第 j 格的卡,就从 (j − ci) 个卡距、加上它在旧格里还没走完的位移,滑回 0。
 * 只认搬运造成的三种变化——同一批卡换了顺序(左右搬、取消)、少了一张(搬走 / 移出)、多了一张(搬进来);
 * 其余(整行换了内容:上移 / 下移 / 删行、数据重读)返回 null = 不动画、原样到位。[oldOffsets] = 旧的每一格此刻画在的位移。
 */
internal fun carrySlideStarts(old: List<String>, new: List<String>, oldOffsets: FloatArray, pitch: Float): FloatArray? {
    if (old == new) return null
    val shaped = (new.size == old.size && new.toSet() == old.toSet()) ||
        (new.size == old.size - 1 && isSubsequence(new, old)) ||
        (new.size == old.size + 1 && isSubsequence(old, new))
    if (!shaped) return null
    return FloatArray(new.size) { ci ->
        val j = old.indexOf(new[ci])
        if (j < 0) 0f else (j - ci) * pitch + oldOffsets.getOrElse(j) { 0f }
    }
}

private fun isSubsequence(small: List<String>, big: List<String>): Boolean {
    var i = 0
    for (x in big) if (i < small.size && small[i] == x) i++
    return i == small.size
}

/** 从别的架子搬进来的卡从哪边滑入:上一层来的从上方(−1),下一层来的从下方(+1)。 */
internal fun arrivalSign(fromRow: Int, toRow: Int): Int = when {
    fromRow < toRow -> -1
    fromRow > toRow -> 1
    else -> 0
}

/**
 * 跨层搬入的滑入距离(dp)。[sign] −1 = 从上方来,+1 = 从下方来,0 = 不滑。非焦点架子不开离屏层([modulate],Task 6)时没有图层可裁,
 * 用整段 [ShelfLayout.CARRY_ARRIVE_DP];仍是离屏层时收短到「卡 + 焦点放大 + 60 dp 柔光」在起点也不出撑大后的图层(R129f):
 * 卡片下方离架子底只有 CARDS_BOTTOM(15.4 dp),从下方来的只剩约 12 dp。
 */
internal fun carryArriveDp(sign: Int, modulate: Boolean = ShelfLayout.DIM_BY_MODULATE): Float {
    if (sign == 0) return 0f
    if (modulate) return ShelfLayout.CARRY_ARRIVE_DP
    val cardH = ShelfLayout.APP_SHELF_HEIGHT - ShelfLayout.CARDS_TOP - ShelfLayout.CARDS_BOTTOM
    val overflow = GtvLayout.APP_FOCUS_GLOW_DP + cardH * (GtvLayout.APP_FOCUS_SCALE - 1f) / 2f
    val room = (if (sign < 0) ShelfLayout.CARDS_TOP else ShelfLayout.CARDS_BOTTOM) + ShelfLayout.LAYER_PAD - overflow
    return ShelfLayout.CARRY_ARRIVE_DP.coerceAtMost(room.coerceAtLeast(0f))
}

/** 最近一次跨层搬入(或跨层取消放回):搬到了第 [row] 层、是哪个包、从哪边来。 */
internal data class CarryArrival(val row: Int, val pkg: String, val sign: Int)

/**
 * 一层卡片条的滑动状态。普通对象 + 一个快照状态 [progress](0 → 1,已含缓动):AppShelfView 在 SideEffect 里调 [begin](早于这一帧的绘制),
 * 卡片在绘制阶段读 [dx] / [dy]——位移每帧变化只重画,不重组、不改布局(`centers` 仍是布局位置)。
 */
internal class ShelfSlide {
    var lastApps: List<String>? = null
    var startX = FloatArray(0)
    var arriveSlot = -1
    var arriveDy = 0f
    var job: Job? = null
    /** 已经用掉的那一次跨层搬入(见 [begin])。 */
    var consumedArrival: CarryArrival? = null
    val progress = mutableFloatStateOf(1f)
    fun dx(ci: Int): Float = startX.getOrElse(ci) { 0f } * (1f - progress.floatValue)
    fun dy(ci: Int): Float = if (ci == arriveSlot) arriveDy * (1f - progress.floatValue) else 0f
    fun currentX(n: Int): FloatArray = FloatArray(n) { dx(it) }

    /**
     * 这一层的卡换成了 [apps]:算出每一格的起点,返回 true = 要从 progress 0 跑一段动画(调用方负责启动),false = 原样到位。
     * [arrival] = 搬进这一层的那张(已按层过滤),[arriveDyPx] = 它竖直滑入的起点(px,带方向)。
     * 竖直滑入还没走完又左右搬一步:那张卡带着此刻的竖直位移去新格接着滑,不先跳回(同 Review Focus 2 的横向)。
     */
    fun begin(apps: List<String>, arrival: CarryArrival?, pitchPx: Float, arriveDyPx: Float): Boolean {
        val prev = lastApps
        lastApps = apps
        if (prev == null || prev == apps) return false
        val starts = carrySlideStarts(prev, apps, currentX(prev.size), pitchPx)
        val flyingPkg = prev.getOrNull(arriveSlot)
        val flyingDy = dy(arriveSlot)
        if (starts == null) {
            startX = FloatArray(0); arriveSlot = -1; arriveDy = 0f; progress.floatValue = 1f
            return false
        }
        startX = starts
        val inserted = apps.indexOfFirst { it !in prev }
        // 每次跨层搬入只用一次(按实例认,同样内容的新一次跨层是新实例):之后同一个包被移出再加回不会重放竖直滑入
        if (inserted >= 0 && arrival != null && arrival !== consumedArrival && apps[inserted] == arrival.pkg) {
            consumedArrival = arrival
            arriveSlot = inserted; arriveDy = arriveDyPx
        } else {
            val k = if (flyingDy != 0f && flyingPkg != null) apps.indexOf(flyingPkg) else -1
            arriveSlot = k; arriveDy = if (k >= 0) flyingDy else 0f
        }
        progress.floatValue = 0f
        return true
    }
}

/** R167:一张应用卡此刻该不该画成聚焦([shown]),以及这次变化要不要当场到位([snap])。 */
internal data class CarryFocusLook(val shown: Boolean, val snap: Boolean)

/**
 * 编辑页的焦点画法跟着应用走(R167 拿起搬运)。卡片按位置组合,搬一步时第 ci 格换上了别的应用,而焦点要晚一两帧才追到新格:
 * - 被搬的卡([moving])恒画成聚焦,当场到位——焦点画法属于它,不在每一格重放进焦动画;
 * - 取消搬运后正滑回出发格的那张([settling],焦点一直在它身上)同样恒画成聚焦,直到焦点真的落回它;
 * - 这一格得到焦点时放的应用([focusedPkg])已经不是现在这个([pkg]):原来的焦点画法当场收掉、不淡出
 *   (淡出的框会跟着让位的邻卡滑走,看起来像两张有框的卡交叉而过);
 * - 其余情况与 [focused] 相同、照常动画(平常浏览的 400 ms 淡出不变)。
 */
internal fun carryFocusLook(focused: Boolean, moving: Boolean, focusedPkg: String?, pkg: String, settling: Boolean = false): CarryFocusLook {
    if (moving || settling) return CarryFocusLook(shown = true, snap = true)
    val appChanged = focusedPkg != null && focusedPkg != pkg
    return if (appChanged) CarryFocusLook(shown = false, snap = true) else CarryFocusLook(shown = focused, snap = false)
}
