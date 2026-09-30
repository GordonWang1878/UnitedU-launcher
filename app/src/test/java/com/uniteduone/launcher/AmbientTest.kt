package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R142 整屏页氛围底的纯计算(Ambient.kt 的 ambientPixels)。 */
class AmbientTest {
    private fun ch(p: Int, c: Int) = (p shr (16 - 8 * c)) and 0xFF
    private fun lum(p: Int): Double {
        fun lin(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(ch(p, 0)) + 0.7152 * lin(ch(p, 1)) + 0.0722 * lin(ch(p, 2))
    }

    @Test fun `纯白壁纸也不把底抬灰——最弱一档文字仍读得出`() {
        val white = IntArray(96 * 54) { 0xFFFFFFFF.toInt() }
        val out = ambientPixels(white, 96, 54, 96, 54)
        val max = out.maxOf { maxOf(ch(it, 0), ch(it, 1), ch(it, 2)) }
        assertTrue("最亮通道 $max", max <= 0x24)
        // 最弱一档文字色在最亮的底上仍 ≥ 4.0:1(纯白壁纸是极端情况,正常壁纸模糊后远比这暗)
        val worst = out.maxOf { lum(it) }
        val ratio = (lum(Ink.Tertiary.toArgbInt()) + 0.05) / (worst + 0.05)
        assertTrue("对比度 $ratio", ratio >= 4.0)
    }

    /** R144 复审:胶囊、信息块是半透明白(SurfaceIdle),叠在最亮的氛围底上;上面的小字用说明那一档灰,仍要 ≥ 4.5:1。 */
    @Test fun `最亮的氛围底再叠胶囊底,说明灰仍读得出`() {
        val white = IntArray(96 * 54) { 0xFFFFFFFF.toInt() }
        val brightest = ambientPixels(white, 96, 54, 96, 54).maxByOrNull { lum(it) }!!
        val a = GtvTokens.SurfaceIdle.alpha
        fun over(c: Int) = (c + (255 - c) * a).toInt()
        val composite = (0xFF shl 24) or (over(ch(brightest, 0)) shl 16) or (over(ch(brightest, 1)) shl 8) or over(ch(brightest, 2))
        val ratio = (lum(Ink.Secondary.toArgbInt()) + 0.05) / (lum(composite) + 0.05)
        assertTrue("对比度 $ratio(底 ${Integer.toHexString(composite)})", ratio >= 4.5)
    }

    @Test fun `纯黑壁纸就是原来的底色(上下差不过一级抖动)`() {
        val black = IntArray(96 * 54) { 0xFF000000.toInt() }
        val out = ambientPixels(black, 96, 54, 192, 108)
        for (p in out) for (c in 0..2) {
            val base = (0xFF0E0E0F.toInt() shr (16 - 8 * c)) and 0xFF
            val expect = base * (1 - Ambient.MIX)
            assertTrue("通道 $c = ${ch(p, c)}", Math.abs(ch(p, c) - expect) <= 1.0)
        }
    }

    @Test fun `不透明、尺寸对、同一输入结果恒定`() {
        val src = IntArray(96 * 54) { i -> 0xFF000000.toInt() or ((i * 2654435761L).toInt() and 0xFFFFFF) }
        val a = ambientPixels(src, 96, 54, 960, 540)
        val b = ambientPixels(src, 96, 54, 960, 540)
        assertEquals(960 * 540, a.size)
        assertTrue(a.all { (it ushr 24) == 0xFF })
        assertTrue(a.contentEquals(b))
    }

    @Test fun `抖动在正负半级之内、平均接近 0`() {
        var sum = 0.0
        for (y in 0 until 200) for (x in 0 until 200) {
            val d = ditherAt(x, y)
            assertTrue(d >= -0.5f && d < 0.5f)
            sum += d
        }
        assertTrue("平均 ${sum / 40000}", Math.abs(sum / 40000) < 0.01)
    }

    @Test fun `模糊之后只剩大块色调——相邻像素差很小`() {
        // 棋盘格(最高频)输入,模糊后相邻输出像素最多差几级
        val checker = IntArray(96 * 54) { i -> if ((i % 96 + i / 96) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val out = ambientPixels(checker, 96, 54, 96, 54)
        var maxStep = 0
        for (i in 1 until out.size) if (i % 96 != 0) maxStep = maxOf(maxStep, Math.abs(ch(out[i], 1) - ch(out[i - 1], 1)))
        assertTrue("相邻最大差 $maxStep", maxStep <= 2)
    }

    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int =
        (0xFF shl 24) or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()
}
