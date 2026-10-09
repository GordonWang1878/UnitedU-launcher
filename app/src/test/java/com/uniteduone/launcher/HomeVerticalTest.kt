package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R164 spec §4:首页纵向位移从「每行等高」改成「逐行高度累计」;应用行之间逐像素不变。 */
class HomeVerticalTest {
    private val sizes = GtvCardSize.values().toList()
    private val heights = listOf(540f, 720f)

    private fun appsOnly(n: Int, size: GtvCardSize, titles: Boolean, h: Float) =
        HomeVertical(List(n) { appRowGeom(size, titles) }, h, appRowGeom(size, titles))

    @Test fun appOnlyLayoutsArePixelIdenticalToToday() {
        for (size in sizes) for (titles in listOf(false, true)) for (h in heights) for (n in 1..5) {
            val v = appsOnly(n, size, titles, h)
            val tag = "$size titles=$titles h=$h n=$n"
            assertEquals("$tag rowsTop", GtvLayout.rowsTop(size, titles, h), v.rowsTop, 0.001f)
            assertEquals("$tag 顶栏 = 静止", 0f, v.shiftY(-1), 0.001f)
            for (r in 0 until n) {
                assertEquals("$tag 行 $r 静止卡顶", GtvLayout.restCardTop(r, size, titles, h), v.restCardTop(r), 0.001f)
                assertEquals("$tag 行 $r 位移", GtvLayout.rowShiftY(r, size, titles), v.shiftY(r), 0.001f)
                assertEquals("$tag 行 $r 焦点线", GtvLayout.focusLineCardTop(size, titles, h), v.focusLine(r), 0.001f)
            }
        }
    }

    @Test fun emptyHomeFallsBackToTheAppGeometry() {
        val v = HomeVertical(emptyList(), 540f, appRowGeom(GtvCardSize.MEDIUM, false))
        assertEquals(GtvLayout.rowsTop(GtvCardSize.MEDIUM, false, 540f), v.rowsTop, 0.001f)
        assertEquals(0f, v.shiftY(0), 0.001f)
    }

    @Test fun channelRowGeometryNumbers() {
        // 卡高 110、聚焦溢出 110 × 0.05 + 2 + 1.5 = 9;行头 22;卡下 9 + 21 + 16 = 46
        assertEquals(9f, ChannelRowLayout.headerGap(), 0.0001f)
        assertEquals(46f, ChannelRowLayout.infoHeight(), 0.0001f)
        val g = channelRowGeom()
        assertEquals(7f + 22f + 9f, g.cardTop, 0.0001f)
        assertEquals(2 * 7f + 22f + 9f + 110f + 46f + GtvLayout.ROW_GAP, g.pitch, 0.0001f)
        assertEquals(110f + 46f, g.visibleBelow, 0.0001f)
        assertEquals(540f - 32f - 156f, HomeVertical(listOf(g), 540f, g).focusLine(0), 0.0001f)
    }

    @Test fun focusRowCardTopAlwaysSitsOnItsOwnFocusLine() {
        val a = appRowGeom(GtvCardSize.MEDIUM, false)
        val c = channelRowGeom()
        val v = HomeVertical(listOf(a, c, a, c, c, a), 540f, a)
        for (r in 0..5) assertEquals("行 $r", v.focusLine(r), v.restCardTop(r) + v.shiftY(r), 0.001f)
    }

    @Test fun blocksStackByTheirOwnPitches() {
        val a = appRowGeom(GtvCardSize.LARGE, true)
        val c = channelRowGeom()
        val v = HomeVertical(listOf(c, a, c), 540f, a)
        assertEquals(v.restBlockTop(0) + c.pitch, v.restBlockTop(1), 0.001f)
        assertEquals(v.restBlockTop(1) + a.pitch, v.restBlockTop(2), 0.001f)
    }

    /** R52「静止只露行 0」在混排下照样成立:行 1 的整块(含频道行行头)静止时在屏外。 */
    @Test fun onlyRowZeroIsVisibleAtRestInMixedLayouts() {
        val c = channelRowGeom()
        for (size in sizes) for (titles in listOf(false, true)) {
            val a = appRowGeom(size, titles)
            for (pair in listOf(listOf(a, c), listOf(c, a), listOf(c, c))) {
                val v = HomeVertical(pair, 540f, a)
                assertTrue("$size titles=$titles ${pair.map { it === c }} 行 1 顶 ${v.restBlockTop(1)}", v.restBlockTop(1) >= 540f)
            }
        }
    }

    /** 壁纸逐行压暗(WALLPAPER_DIM_PER_ROW):全是应用行时与改前 `homeWallpaperAlpha(shift, rowPitch)` 逐值相同(含越过最后一行的弹簧过冲)。 */
    @Test fun wallpaperDimForAppOnlyLayoutsIsUnchanged() {
        for (size in sizes) for (titles in listOf(false, true)) for (n in 1..5) {
            val v = appsOnly(n, size, titles, 540f)
            val pitch = GtvLayout.rowPitch(size, titles)
            var s = 0f
            while (s <= 8 * pitch) {
                for (perRow in listOf(true, false)) for (sign in listOf(-1f, 1f))
                    assertEquals("$size titles=$titles n=$n s=$s perRow=$perRow", GtvLayout.homeWallpaperAlpha(sign * s, pitch, perRow), v.wallpaperAlpha(sign * s, perRow), 0.0001f)
                s += 7.3f
            }
        }
    }

    /** 混排:每行静止位移上 = 该位移的静止 alpha;两行之间按这次位移的进度线性插值(不按统一 rowPitch 折行)。 */
    @Test fun wallpaperDimInMixedLayoutsFollowsCumulativeTops() {
        val a = appRowGeom(GtvCardSize.MEDIUM, false)
        val c = channelRowGeom()
        val v = HomeVertical(listOf(a, c, a, c), 540f, a)
        for (r in 0..3) assertEquals("行 $r", GtvLayout.wallpaperAlpha(v.shiftY(r)), v.wallpaperAlpha(v.shiftY(r), perRow = true), 0.0001f)
        val mid = (v.shiftY(1) + v.shiftY(2)) / 2
        assertEquals((GtvLayout.wallpaperAlpha(v.shiftY(1)) + GtvLayout.wallpaperAlpha(v.shiftY(2))) / 2, v.wallpaperAlpha(mid, perRow = true), 0.0001f)
    }

    @Test fun widthListShiftEqualsTheUniformOneForEqualWidths() {
        for (size in sizes) for (f in 0 until 12) {
            val widths = List(12) { GtvLayout.cardWidth(size) }
            assertEquals("$size f=$f", GtvLayout.rowShiftX(f, size, 960f), GtvLayout.rowShiftX(f, widths, 960f), 0.001f)
        }
    }

    @Test fun widthListShiftForMixedPosters() {
        val widths = List(5) { 196f } + listOf(73f, 73f, 110f)
        assertEquals(0f, GtvLayout.rowShiftX(0, widths, 960f), 0f)
        // 58 + (980 + 146 + 110) + 20 × 7 + 9(110 的聚焦溢出)+ 58 − 960 = 541
        assertEquals(-541f, GtvLayout.rowShiftX(7, widths, 960f), 0.001f)
        assertEquals(0f, GtvLayout.rowShiftX(0, emptyList(), 960f), 0f)
    }
}
