package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

// CardColorTest(cardTintMatrix 的四条)随「主题化卡片」一起删掉(2026-09-23,gtv spec R58)。

class EdgeColorTest {
    @Test fun averagesOpaqueEdgePixels() {
        // 全是纯红边 → 红
        val red = IntArray(40) { 0xFFFF0000.toInt() }
        assertEquals(0xFFFF0000.toInt(), edgeColor(red))
    }

    @Test fun ignoresTransparentEdge() {
        // 全透明边 → null(别硬造底)
        val clear = IntArray(40) { 0x00000000 }
        assertEquals(null, edgeColor(clear))
    }

    @Test fun mixedMostlyOpaqueAverages() {
        // 一半纯蓝一半透明,有效过半 → 蓝
        val px = IntArray(40) { if (it % 2 == 0) 0xFF0000FF.toInt() else 0 }
        assertEquals(0xFF0000FF.toInt(), edgeColor(px))
    }

    // owner 反馈 Round 5:以「咪视界」为例,边缘一圈里有多种颜色(上方大半圈白、下方小圈蓝紫)时,
    // 旧实现对全部不透明像素取算术均色,会落在两者中间、算出原图里不存在的折中色。
    // 规则改成:取占比更高的那个颜色,不再和稀泥。

    @Test fun uniformEdgeReturnsExactColour() {
        // 非三原色的普通色也要能精确复原——分桶只应影响「多种颜色混合」的情形,
        // 纯色/近纯色边缘必须原样通过。
        val teal = 0xFF11A0A0.toInt()
        assertEquals(teal, edgeColor(IntArray(40) { teal }))
    }

    @Test fun dominantColourWinsOverMean_whiteMajorityBluePurpleMinority() {
        // 70% 白 + 30% 蓝紫(咪视界场景的抽象复现)。
        val white = 0xFFFFFFFF.toInt()
        val bluePurple = 0xFF5B4FA8.toInt()
        val px = IntArray(100) { if (it < 70) white else bluePurple }
        val result = edgeColor(px)
        assertEquals(white, result)
        // 反证:两色的算术均值是第三种颜色(灰蓝),不等于白也不等于蓝紫——
        // 证明这个断言真的在测「取占比更高的一个」而不是凑巧与旧实现同值。
        val meanR = (0xFF * 70 + 0x5B * 30) / 100
        val meanG = (0xFF * 70 + 0x4F * 30) / 100
        val meanB = (0xFF * 70 + 0xA8 * 30) / 100
        val mean = (0xFF shl 24) or (meanR shl 16) or (meanG shl 8) or meanB
        org.junit.Assert.assertNotEquals(mean, result)
    }

    @Test fun tiedTwoColoursReturnsOneNeverABlend() {
        // 50/50 打平:不允许出现融合出的第三色,只能是原来两色之一。
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val px = IntArray(40) { if (it % 2 == 0) red else blue }
        val result = edgeColor(px)
        org.junit.Assert.assertTrue(result == red || result == blue)
    }

    @Test fun transparentPixelsNeverEnterDominantColourVote() {
        // 70% 透明(RGB 恰好是「白」)+ 30% 不透明红。不透明占比 30% ≥ 25% 门槛,不会走 null;
        // 若透明像素被误计入分桶(bug:忘记在分桶前 continue),占多数的「白」会赢——
        // 必须仍然是红,证明透明像素连投票资格都没有,不只是不算进有效像素计数。
        val px = IntArray(100) { if (it < 30) 0xFFFF0000.toInt() else 0x00FFFFFF }
        assertEquals(0xFFFF0000.toInt(), edgeColor(px))
    }

    @Test fun dominantColourStillNullBelowValidThreshold() {
        // 不透明只占 20%(< 25% 门槛)→ null,门槛判定不受「改成分桶取众数」影响。
        val px = IntArray(100) { if (it < 20) 0xFFFF0000.toInt() else 0x00000000 }
        assertEquals(null, edgeColor(px))
    }

    @Test fun bucketBoundaryDoesNotSplitOneColourIntoTwoLosers() {
        // 整枝审查存疑 3(2026-09-22):同一种近白灰恰好跨在桶界两侧——0xDF(223 → 桶 6)与
        // 0xE0(224 → 桶 7)各占 30%,再加 40% 纯蓝。肉眼看这是「60% 近白 + 40% 蓝」,该返回近白;
        // 只按单桶计数的话近白被桶界撕成两张 30% 的票,40% 的蓝反而当选。
        val greyA = 0xFFDFDFDF.toInt()
        val greyB = 0xFFE0E0E0.toInt()
        val blue = 0xFF0000FF.toInt()
        val px = IntArray(100) { when { it < 30 -> greyA; it < 60 -> greyB; else -> blue } }
        val result = edgeColor(px)!!
        org.junit.Assert.assertNotEquals("40% 的蓝赢了被桶界撕开的 60% 近白", blue, result)
        // 结果必须是近白本身(两个相邻桶之一的均值),不是与蓝和稀泥出来的第三色
        org.junit.Assert.assertTrue(
            "返回了 %06X,不是近白".format(result and 0xFFFFFF),
            result == greyA || result == greyB,
        )
    }

    @Test fun neighbourMergeDoesNotBlendVisiblyDifferentGreys() {
        // 邻桶合并只用来**选**赢家,不用来**算**颜色:55% 纯白(桶 7)+ 45% 浅灰 0xC8(桶 6)
        // 是两个相邻桶,合并后一起赢过其它颜色,但返回的必须是核心桶(白)自己的均值,
        // 不能是白与浅灰的混合——否则又回到「算出原图里不存在的颜色」。
        val white = 0xFFFFFFFF.toInt()
        val grey = 0xFFC8C8C8.toInt()
        val px = IntArray(100) { if (it < 55) white else grey }
        assertEquals(white, edgeColor(px))
    }

    @Test fun alphaIsAlwaysFullOnDominantResult() {
        // 即便入参的不透明像素 alpha 只有 128(边界值),返回值也必须钉死 0xFF——
        // 这是 2026-09-16 网易云那次「回落底透明」事故的回归防线,继续覆盖。
        val translucentGreen = (128 shl 24) or 0x00FF00
        val result = edgeColor(IntArray(40) { translucentGreen })
        assertEquals(0xFF, (result!! ushr 24) and 0xFF)
    }
}

class OpaqueFractionTest {
    @Test fun fullBleedBannerIsMostlyOpaque() {
        val px = IntArray(100) { 0xFF123456.toInt() }
        org.junit.Assert.assertTrue(opaqueFraction(px) >= 0.8f)
    }

    @Test fun transparentEdgedLogoIsMostlyClear() {
        // 只有中间不透明,四边透明 → 低占比
        val px = IntArray(100) { if (it in 40..59) 0xFFFF0000.toInt() else 0 }
        org.junit.Assert.assertTrue(opaqueFraction(px) < 0.8f)
    }

    @Test fun emptyIsZero() {
        org.junit.Assert.assertEquals(0f, opaqueFraction(IntArray(0)), 1e-6f)
    }
}
