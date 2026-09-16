package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorTest {
    private fun luma(rgb: Int): Float {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    @Test fun darkWallpaperAccentLiftedToReadable() {
        // M3 实测:内置蓝底主色 ≈ (14,22,29),原样是黑,提亮后必须能在 #0A0A0A 上看清
        val out = usableAccent(0x0E161D)
        assertTrue("luma ${luma(out)}", luma(out) > 0.45f)
    }

    @Test fun grayWallpaperStaysNeutral() {
        // 纯灰不该凭噪声造出红色:三通道仍近似相等
        val out = usableAccent(0x202020)
        val r = (out shr 16) and 0xFF; val g = (out shr 8) and 0xFF; val b = out and 0xFF
        assertTrue("r=$r g=$g b=$b", maxOf(r, g, b) - minOf(r, g, b) < 12)
        assertTrue(luma(out) > 0.45f)   // 但仍被提亮
    }

    @Test fun hueIsPreserved() {
        // 暗蓝提亮后仍是蓝(b 通道最大)
        val out = usableAccent(0x0E161D)
        val r = (out shr 16) and 0xFF; val g = (out shr 8) and 0xFF; val b = out and 0xFF
        assertTrue("r=$r g=$g b=$b", b >= r && b >= g)
    }

    @Test fun alreadyBrightColorMostlyUnchanged() {
        // 已经够亮的色不需要大改(金预设 accent),亮度不降
        assertTrue(luma(usableAccent(0xC0A73A)) >= luma(0xC0A73A) - 0.02f)
    }
}
