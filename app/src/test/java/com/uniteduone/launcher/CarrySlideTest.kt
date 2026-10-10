package com.uniteduone.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R167 拿起换位的绘制位移(纯函数)。卡距记作 P。 */
class CarrySlideTest {
    private val p = 100f
    private fun zeros(n: Int) = FloatArray(n)

    /** a 右移一格:第 0 格换上 b(原在第 1 格)从 +P 滑回,第 1 格换上 a 从 −P 滑回 */
    @Test fun swapRight() =
        assertArrayEquals(floatArrayOf(p, -p, 0f), carrySlideStarts(listOf("a", "b", "c"), listOf("b", "a", "c"), zeros(3), p), 0f)

    /** 搬走中间那张:后面的卡往左合拢,从 +P 滑回 */
    @Test fun removalClosesGap() =
        assertArrayEquals(floatArrayOf(0f, p), carrySlideStarts(listOf("a", "b", "c"), listOf("a", "c"), zeros(3), p), 0f)

    /** 搬进第 1 格:搬进来的那张水平不动(竖直滑入另算),后面的卡从 −P 滑回 */
    @Test fun insertionOpensGap() =
        assertArrayEquals(floatArrayOf(0f, 0f, -p), carrySlideStarts(listOf("a", "c"), listOf("a", "x", "c"), zeros(2), p), 0f)

    /** 上一步还没走完(第 1 格的 a 还在 −0.4P 处)又右移一步:a 到第 2 格,从 (1−2)P − 0.4P 接着滑,不先跳回(Review Focus 2) */
    @Test fun midAnimationStepContinuesFromCurrentOffset() {
        val s = carrySlideStarts(listOf("b", "a", "c"), listOf("b", "c", "a"), floatArrayOf(0.4f * p, -0.4f * p, 0f), p)!!
        assertEquals(-1.4f * p, s[2], 1e-3f)
        assertEquals(p, s[1], 1e-3f)
        assertEquals(0.4f * p, s[0], 1e-3f)
    }

    /** 整行换了内容(上移 / 下移 / 删行让这一层显示别的行):不动画 */
    @Test fun wholesaleChangeIsNotAnimated() = assertNull(carrySlideStarts(listOf("a", "b"), listOf("x", "y"), zeros(2), p))
    @Test fun unchangedIsNotAnimated() = assertNull(carrySlideStarts(listOf("a", "b"), listOf("a", "b"), zeros(2), p))
    /** 一次少两张(不是搬运能造成的):不动画 */
    @Test fun twoRemovedIsNotAnimated() = assertNull(carrySlideStarts(listOf("a", "b", "c"), listOf("a"), zeros(3), p))

    @Test fun arrivalComesFromWhereItWas() {
        assertEquals(-1, arrivalSign(fromRow = 0, toRow = 1))   // 从上一层下来:从上方滑入
        assertEquals(1, arrivalSign(fromRow = 2, toRow = 1))    // 从下一层上来:从下方滑入
        assertEquals(0, arrivalSign(fromRow = 1, toRow = 1))
    }

    /**
     * 跨层滑入的起点上,卡 + 焦点放大 + 柔光仍在撑大后的图层内(R129f)——非焦点架子还是离屏层(DIM_BY_MODULATE 为假)时才有图层可裁。
     * 卡片上方离架子顶 CARDS_TOP、下方离架子底 CARDS_BOTTOM,图层四边各多 LAYER_PAD。
     */
    @Test fun arrivalGlowStaysInsideLayer() {
        val cardH = ShelfLayout.APP_SHELF_HEIGHT - ShelfLayout.CARDS_TOP - ShelfLayout.CARDS_BOTTOM
        val overflow = GtvLayout.APP_FOCUS_GLOW_DP + cardH * (GtvLayout.APP_FOCUS_SCALE - 1f) / 2f
        val up = carryArriveDp(sign = -1, modulate = false)
        val down = carryArriveDp(sign = 1, modulate = false)
        assertTrue("从上方:$up", ShelfLayout.CARDS_TOP + ShelfLayout.LAYER_PAD - up >= overflow - 1e-3f)
        assertTrue("从下方:$down", ShelfLayout.CARDS_BOTTOM + ShelfLayout.LAYER_PAD - down >= overflow - 1e-3f)
        assertTrue("仍有可见的滑入", down > 0f && up == ShelfLayout.CARRY_ARRIVE_DP)
    }
    /** 不开离屏层就没有图层可裁:两个方向都是整段 40 dp;同层(sign 0)不滑 */
    @Test fun modulateUsesFullArrivalDistance() {
        assertEquals(ShelfLayout.CARRY_ARRIVE_DP, carryArriveDp(sign = 1, modulate = true), 0f)
        assertEquals(ShelfLayout.CARRY_ARRIVE_DP, carryArriveDp(sign = -1, modulate = true), 0f)
        assertEquals(0f, carryArriveDp(sign = 0, modulate = false), 0f)
    }
}

/** [ShelfSlide.begin] 的状态推进(progress 由测试手动设,代替动画)。 */
class ShelfSlideTest {
    private val p = 100f

    @Test fun firstCompositionDoesNotAnimate() {
        val s = ShelfSlide()
        assertEquals(false, s.begin(listOf("a", "b"), null, p, 0f))
        assertEquals(0f, s.dx(0), 0f)
    }

    @Test fun arrivalSlidesVerticallyOnlyTheArrivingCard() {
        val s = ShelfSlide()
        s.begin(listOf("a", "c"), null, p, 0f)
        assertTrue(s.begin(listOf("a", "x", "c"), CarryArrival(row = 1, pkg = "x", sign = -1), p, -40f))
        assertEquals(-40f, s.dy(1), 0f)
        assertEquals(0f, s.dy(0), 0f)
        assertEquals(-p, s.dx(2), 0f)
        s.progress.floatValue = 1f
        assertEquals(0f, s.dy(1), 0f)
        assertEquals(0f, s.dx(2), 0f)
    }

    /** 竖直滑入走到一半又右移一步:那张卡带着剩下的竖直位移到新格,不跳回 0 */
    @Test fun stepDuringArrivalKeepsVerticalOffset() {
        val s = ShelfSlide()
        s.begin(listOf("a", "c"), null, p, 0f)
        val arr = CarryArrival(row = 1, pkg = "x", sign = 1)
        s.begin(listOf("a", "x", "c"), arr, p, 12f)
        s.progress.floatValue = 0.5f
        assertTrue(s.begin(listOf("a", "c", "x"), arr, p, 12f))
        assertEquals(6f, s.dy(2), 1e-3f)
        assertEquals(0f, s.dy(1), 0f)
        assertEquals(-p, s.dx(2), 1e-3f)          // x 从第 1 格(此刻 dx 已为 0)到第 2 格
        assertEquals(p - 0.5f * p, s.dx(1), 1e-3f) // c 原在第 2 格、还剩 −0.5P → 到第 1 格:+P − 0.5P
    }

    @Test fun wholesaleChangeSnapsAndClearsArrival() {
        val s = ShelfSlide()
        s.begin(listOf("a", "c"), null, p, 0f)
        s.begin(listOf("a", "x", "c"), CarryArrival(1, "x", -1), p, -40f)
        assertEquals(false, s.begin(listOf("q", "r"), null, p, 0f))
        assertEquals(0f, s.dy(1), 0f)
        assertEquals(1f, s.progress.floatValue, 0f)
    }
}

class ReuseEqualTest {
    @Test fun unchangedElementsKeepTheirInstance() {
        val a = listOf("x"); val b = listOf("y")
        val out = reuseEqual(listOf(a, b), listOf(listOf("x"), listOf("z")))
        assertTrue(out[0] === a)
        assertEquals(listOf("z"), out[1])
        assertEquals(listOf(listOf("x"), listOf("z")), out)
    }
    @Test fun firstCallIsFresh() {
        val f = listOf(listOf("x"))
        assertTrue(reuseEqual(null, f) === f)
    }
}

/** R167 复审:跨层搬入只滑一次;焦点画法跟着应用走。 */
class CarryFixTest {
    private val p = 100f

    /** 同一次搬入用过之后,同一个包移出再加回(没有新的跨层)不再竖直滑入 */
    @Test fun arrivalIsConsumedOnce() {
        val s = ShelfSlide()
        val arr = CarryArrival(row = 1, pkg = "x", sign = -1)
        s.begin(listOf("a", "c"), null, p, 0f)
        s.begin(listOf("a", "x", "c"), arr, p, -40f)
        assertEquals(-40f, s.dy(1), 0f)
        s.progress.floatValue = 1f
        s.begin(listOf("a", "c"), arr, p, -40f)        // 移出
        s.begin(listOf("a", "x", "c"), arr, p, -40f)   // 加回,arrival 还是那一个实例
        assertEquals(0f, s.dy(1), 0f)
        // 新的一次跨层(内容相同的新实例)照常滑入
        s.begin(listOf("a", "c"), null, p, 0f)
        s.begin(listOf("a", "x", "c"), CarryArrival(row = 1, pkg = "x", sign = -1), p, -40f)
        assertEquals(-40f, s.dy(1), 0f)
    }

    @Test fun carriedCardIsAlwaysFocusedAndSnaps() =
        assertEquals(CarryFocusLook(shown = true, snap = true), carryFocusLook(focused = false, moving = true, focusedPkg = null, pkg = "a"))

    /** 格子换了应用(还没失焦的那一两帧也算):当场收掉,不淡出 */
    @Test fun slotThatChangedAppDropsFocusAtOnce() {
        assertEquals(CarryFocusLook(false, true), carryFocusLook(focused = true, moving = false, focusedPkg = "a", pkg = "b"))
        assertEquals(CarryFocusLook(false, true), carryFocusLook(focused = false, moving = false, focusedPkg = "a", pkg = "b"))
    }

    /** 平常浏览:与 focused 相同,照常动画 */
    @Test fun normalBrowsingAnimatesAsBefore() {
        assertEquals(CarryFocusLook(true, false), carryFocusLook(focused = true, moving = false, focusedPkg = "a", pkg = "a"))
        assertEquals(CarryFocusLook(false, false), carryFocusLook(focused = false, moving = false, focusedPkg = "a", pkg = "a"))
        assertEquals(CarryFocusLook(false, false), carryFocusLook(focused = false, moving = false, focusedPkg = null, pkg = "a"))
    }
}

/** R167 复审 2:取消搬运时那张卡一直有焦点,滑回途中照样画成聚焦、当场到位,不重放进焦动画。 */
class CarryCancelLookTest {
    /** 取消那一刻:卡已不在搬运中(moving = false)、焦点还没落回出发格(focused = false),出发格先前记的是别的包 */
    @Test fun cancelledCardKeepsFocusedLookWhileSliding() =
        assertEquals(CarryFocusLook(shown = true, snap = true),
            carryFocusLook(focused = false, moving = false, focusedPkg = "other", pkg = "yt", settling = true))

    /** 焦点落回之后(settling 清掉):仍是聚焦,不再 snap——目标值不变,动画值早已在终点 */
    @Test fun afterFocusLandsItIsPlainFocused() =
        assertEquals(CarryFocusLook(shown = true, snap = false),
            carryFocusLook(focused = true, moving = false, focusedPkg = "yt", pkg = "yt", settling = false))

    /** 原来搬运停的那一格(换回了别的应用):照样当场收掉 */
    @Test fun slotItLeftStillDropsAtOnce() =
        assertEquals(CarryFocusLook(shown = false, snap = true),
            carryFocusLook(focused = true, moving = false, focusedPkg = "yt", pkg = "a3", settling = false))
}
