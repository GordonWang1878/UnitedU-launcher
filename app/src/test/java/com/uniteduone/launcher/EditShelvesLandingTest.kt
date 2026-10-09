package com.uniteduone.launcher

import com.uniteduone.launcher.ShelfChip.DELETE
import com.uniteduone.launcher.ShelfChip.DOWN
import com.uniteduone.launcher.ShelfChip.ICON
import com.uniteduone.launcher.ShelfChip.UP
import com.uniteduone.launcher.ShelfZone.CARDS
import com.uniteduone.launcher.ShelfZone.CHIPS
import com.uniteduone.launcher.ShelfZone.NEW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R165 货架:动作后的落点(spec §2.2)、纵向位移(焦点线)、拿起时的方向箭头、架子明暗、几何与效果图对齐。纯 JVM。 */
class EditShelvesLandingTest {
    private fun row(icon: String, vararg apps: String) = LayoutRow(icon, apps = apps.toList())
    private val view3 = listOf(row("movie", "a", "b", "c", "d"), row("tv", "e", "f"), row("music"))
    private val three = shelvesOf(view3)

    /** Review Focus 2:按下的那颗胶囊在新位置上没了(移到顶没有「上移」、移到底没有「下移」)→ 落反方向那颗。 */
    @Test fun movingAShelfKeepsFocusOnTheSameChipOrItsOpposite() {
        assertEquals("移到中间:「下移」还在", ShelfSpot(1, CHIPS, 3), landingAfterSwap(three, 1, DOWN))
        assertEquals("移到最后一个内容层:没有「下移」→「上移」", ShelfSpot(2, CHIPS, 2), landingAfterSwap(three, 2, DOWN))
        assertEquals("移到第一层:没有「上移」→「下移」", ShelfSpot(0, CHIPS, 2), landingAfterSwap(three, 0, UP))
        val two = shelvesOf(view3.take(2))
        for (s in 0..1) for (c in listOf(UP, DOWN)) {
            val l = landingAfterSwap(two, s, c)
            assertEquals(s, l.shelf)
            assertEquals(CHIPS, l.zone)
            assertTrue("落点那颗胶囊一定存在:$l", l.index in shelfChips(two, s).indices)
        }
    }

    @Test fun deletingLandsOnTheFirstChipOfTheShelfAbove() {
        assertEquals(ShelfSpot(0, CHIPS, 0), landingAfterDelete(shelvesOf(listOf(view3[0], view3[2])), 1))
        assertEquals("删的是第一层:落新的第一层", ShelfSpot(0, CHIPS, 0), landingAfterDelete(shelvesOf(view3.drop(1)), 0))
        assertEquals("删的是最后一个内容层:落新的最后一层,不落到「新的一行」",
            ShelfSpot(1, CHIPS, 0), landingAfterDelete(shelvesOf(view3.take(2)), 2))
    }

    @Test fun appendingLandsOnTheNewShelfsAddTile() {
        assertEquals(ShelfSpot(3, CARDS, 0), landingAfterAppend(shelvesOf(view3 + LayoutRow(NEW_ROW_ICON))))
    }

    @Test fun cardAndChipLandingsAreClamped() {
        assertEquals(ShelfSpot(0, CARDS, 3), landingOnCard(three, 0, 9))
        assertEquals("移出第一张:落 0", ShelfSpot(0, CARDS, 0), landingOnCard(three, 0, -1))
        assertEquals("移空了:落「添加应用」方块", ShelfSpot(2, CARDS, 0), landingOnCard(three, 2, 0))
        assertEquals(ShelfSpot(0, CHIPS, 1), landingOnChip(three, 0, ICON))
        assertEquals(ShelfSpot(1, CHIPS, 4), landingOnChip(three, 1, DELETE))
        assertEquals("胶囊不在了:落第一颗", ShelfSpot(0, CHIPS, 0), landingOnChip(shelvesOf(listOf(row("movie"))), 0, DELETE))
        assertEquals(ShelfSpot(3, NEW, 0), clampSpot(three, ShelfSpot(3, NEW, 0)))
    }

    // ---- 纵向位移:焦点层顶边对齐焦点线(180 dp),夹到内容末尾(c1 / c2 的数,1 px = 1 dp) ----
    private val heights = listOf(140, 140, 140, 222)   // 三层应用架子 + 新的一行(190 + 说明一行)
    private fun scroll(f: Int, h: List<Int> = heights, viewport: Int = 540) =
        shelfScroll(h, gap = 14, focused = f, top = 84, focusLine = 180, viewport = viewport, bottomPad = 24)

    @Test fun focusedShelfTopSitsOnTheFocusLineClampedToTheContentEnd() {
        assertEquals("第一层本来就在焦点线之上:不动", 0, scroll(0))
        assertEquals(58, scroll(1))                       // 84 + 154 − 180
        assertEquals(212, scroll(2))                      // 84 + 308 − 180
        assertEquals("最后一层:夹到内容末尾", 252, scroll(3))   // 84 + 642 + 42 + 24 − 540
        assertEquals("越界当最后一层", 252, scroll(9))
        for (f in heights.indices) {
            val top = 84 + (0 until f).sumOf { heights[it] + 14 } - scroll(f)
            assertTrue("第 $f 层完整可见", top >= 0 && top + heights[f] <= 540)
        }
    }

    @Test fun shortContentAndUnmeasuredLayoutsDoNotScroll() {
        assertEquals(0, scroll(1, h = listOf(140, 222)))
        assertEquals("视窗还没量到", 0, scroll(2, viewport = 0))
        assertEquals(0, shelfScroll(emptyList(), 14, 0, 84, 180, 540, 24))
    }

    // ---- 拿起时的方向箭头:只画真的挪得动的方向(照 Google TV gtv-04) ----
    @Test fun carryArrowsShowOnlyDirectionsThatMove() {
        val v = listOf(row("movie", "a", "b", "c"), row("tv", "d"), row("music"))
        assertEquals(setOf(MoveDir.RIGHT, MoveDir.DOWN), carryArrows(v, MovePos(0, 0)))
        assertEquals(setOf(MoveDir.LEFT, MoveDir.RIGHT, MoveDir.DOWN), carryArrows(v, MovePos(0, 1)))
        assertEquals("空的第 3 行也是落点", setOf(MoveDir.UP, MoveDir.DOWN), carryArrows(v, MovePos(1, 0)))
        val dup = listOf(row("movie", "a", "b"), row("tv", "a"))
        assertEquals("下一行已有同一个应用:不能往下", setOf(MoveDir.RIGHT), carryArrows(dup, MovePos(0, 0)))
        assertEquals(emptySet<MoveDir>(), carryArrows(v, MovePos(5, 0)))
    }

    // ---- 架子明暗(spec §2.5)----
    @Test fun shelfLookInterpolatesBetweenIdleAndFocused() {
        assertEquals(ShelfLayout.IDLE_SHELF_ALPHA, shelfAlpha(0f), 1e-6f)
        assertEquals(1f, shelfAlpha(1f), 1e-6f)
        assertEquals(1f, shelfAlpha(3f), 1e-6f)
        val idle = shelfLook(0f, dashed = false)
        assertEquals(0.055f, idle.bg, 1e-6f); assertEquals(0.07f, idle.border, 1e-6f); assertEquals(0f, idle.shadow, 1e-6f)
        val on = shelfLook(1f, dashed = false)
        assertEquals(0.10f, on.bg, 1e-6f); assertEquals(0.14f, on.border, 1e-6f); assertEquals(0.45f, on.shadow, 1e-6f)
        assertEquals("新的一行平时更淡(c1)", 0.03f, shelfLook(0f, dashed = true).bg, 1e-6f)
        assertEquals("新的一行聚焦时同焦点层(c2)", 0.10f, shelfLook(1f, dashed = true).bg, 1e-6f)
    }

    @Test fun hintsFollowTheZoneAndCarrying() {
        assertEquals(EditHintSet.BROWSE, editHintSet(CARDS, carrying = false))
        assertEquals(EditHintSet.BROWSE, editHintSet(CHIPS, carrying = false))
        assertEquals(EditHintSet.BROWSE, editHintSet(null, carrying = false))
        assertEquals(EditHintSet.NEW_ROW, editHintSet(NEW, carrying = false))
        assertEquals(EditHintSet.CARRY, editHintSet(CARDS, carrying = true))
    }

    // ---- 几何:与效果图、与首页基准线对齐 ----
    @Test fun shelfGeometryMatchesTheMockupAndTheHomeKeyline() {
        assertEquals("卡片起点 = 首页基准线,rowShiftX 直接可用", GtvLayout.CONTENT_KEYLINE, ShelfLayout.SIDE + ShelfLayout.PAD_START, 0f)
        assertEquals("固定中档", 122f, GtvLayout.cardWidth(GtvCardSize.MEDIUM), 0f)
        assertEquals(GtvLayout.CARD_GAP, ShelfLayout.CARD_GAP, 0f)
        assertEquals(ShelfLayout.APP_SHELF_HEIGHT,
            ShelfLayout.CARDS_TOP + GtvLayout.cardHeight(GtvCardSize.MEDIUM) + ShelfLayout.CARDS_BOTTOM, 1e-3f)
        assertEquals(ShelfLayout.CARDS_TOP, ShelfLayout.HEADER_TOP + ShelfLayout.HEADER_HEIGHT + 16f, 0f)
        val overH = GtvLayout.appFocusOverflow(GtvLayout.cardHeight(GtvCardSize.MEDIUM))
        assertTrue("焦点卡放大 + 描边不压到胶囊", ShelfLayout.CARDS_TOP - (ShelfLayout.HEADER_TOP + ShelfLayout.HEADER_HEIGHT) >= overH)
        assertTrue("焦点卡放大 + 描边不出架子底边", ShelfLayout.CARDS_BOTTOM >= overH)
        assertTrue("方向箭头画在放大溢出之外", ShelfLayout.ARROW_GAP > GtvLayout.appFocusOverflow(GtvLayout.cardWidth(GtvCardSize.MEDIUM)))
        assertEquals(ShelfLayout.NEW_ROW_HEIGHT, ShelfLayout.CHOICE_TOP + ShelfLayout.CHOICE_HEIGHT + 28f, 0f)
        assertEquals("焦点线 = 屏幕上方 1/3", 540f / 3f, ShelfLayout.FOCUS_LINE, 0f)
        assertTrue("整层淡化的离屏层四边撑够柔光(R129f)", ShelfLayout.LAYER_PAD >= GtvLayout.APP_FOCUS_GLOW_DP)
    }

    /** Review Focus 4:一行多于一屏,焦点走到哪张,那张放大后的外缘都在架子左右边缘之内。 */
    @Test fun longRowSlidesSoTheFocusedCardStaysInsideTheShelf() {
        val w = GtvLayout.cardWidth(GtvCardSize.MEDIUM)
        val over = GtvLayout.appFocusOverflow(w)
        for (col in 0 until 12) {
            val dx = GtvLayout.rowShiftX(col, GtvCardSize.MEDIUM, 960f)
            val left = GtvLayout.CONTENT_KEYLINE + col * (w + GtvLayout.CARD_GAP) + dx - over
            val right = left + w + 2 * over
            assertTrue("第 $col 张左缘 $left 越过架子左边", left >= ShelfLayout.SIDE)
            assertTrue("第 $col 张右缘 $right 越过架子右边", right <= 960f - ShelfLayout.SIDE)
        }
    }
}
