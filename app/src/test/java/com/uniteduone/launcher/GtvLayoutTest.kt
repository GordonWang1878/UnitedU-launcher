package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class GtvLayoutTest {
    @Test fun `中档等于 Google 实测的 153x86`() {
        assertEquals(153f, GtvLayout.cardWidth(GtvCardSize.MEDIUM), 0.01f)
        // 153 × 9/16 = 86.0625;实测量到的 86 是取整值,容差放到 0.1
        assertEquals(86f, GtvLayout.cardHeight(GtvCardSize.MEDIUM), 0.1f)
    }

    @Test fun `三档都是 16比9`() {
        for (s in GtvCardSize.values()) {
            assertEquals(16f / 9f, GtvLayout.cardWidth(s) / GtvLayout.cardHeight(s), 0.02f)
        }
    }

    @Test fun `pitch 等于卡宽加间距`() {
        assertEquals(173f, GtvLayout.cardPitch(GtvCardSize.MEDIUM), 0.01f)
    }

    // Ruling R20(终审 2026-09-20,owner 真机走查后推翻):下面几个测试断言的不再是「焦点卡永远
    // 钉在左基准线」——那条规则照搬自 Google 无边界的推荐流,对我们「常见 5 张卡、一行本来就
    // 装得下」的有限应用列表不成立,会把第一次按右键就整行左移一个 pitch,右边空出约 230dp
    // 死白(真机走查复现)。改回 pre-Task-7 的规则:行放得下就不动,放不下才移够用的距离。
    // 数值出处与推导过程见 GtvLayout.rowShiftX 的 KDoc。960f 是这台机型(1920×1080/320dpi)的
    // screenWidthDp,与 HomeLayout.span() 默认值同一个数,不是随手挑的。
    @Test fun `行完全放得下时,任何一张卡聚焦都不位移(R20)`() {
        // 3 张卡的 MEDIUM 行:58(左基准线) + 153×3 + 20×2 + 58(右留白) = 615dp,960dp 屏宽绰绰有余
        assertEquals(0f, GtvLayout.rowShiftX(0, GtvCardSize.MEDIUM, 960f), 0.01f)
        assertEquals(0f, GtvLayout.rowShiftX(1, GtvCardSize.MEDIUM, 960f), 0.01f)
        assertEquals(0f, GtvLayout.rowShiftX(2, GtvCardSize.MEDIUM, 960f), 0.01f)
    }

    @Test fun `行溢出时只移动刚好够用的距离,不多移(R20)`() {
        // 8 张卡的 MEDIUM 行,聚焦第 8 张(index 7):
        // focusRight = 58 + 153×8 + 20×7 = 1422;overRight = 1422 + 58 - 960 = 520
        assertEquals(-520f, GtvLayout.rowShiftX(7, GtvCardSize.MEDIUM, 960f), 0.01f)
        // 位移之后焦点卡右缘 = 1422 - 520 = 902 = 960 - CONTENT_KEYLINE(58)——刚好贴右基准线,不多不少
        assertEquals(960f - GtvLayout.CONTENT_KEYLINE, 1422f - 520f, 0.01f)
    }

    @Test fun `临界点连续,不会跳变(R20)`() {
        // 屏宽正好等于「focusRight(index 3) + 右留白」时位移为 0;屏宽再窄 1dp,位移就恰好是 1dp
        val focusRightAt3 = GtvLayout.CONTENT_KEYLINE +
            GtvLayout.cardWidth(GtvCardSize.MEDIUM) * 4 + GtvLayout.CARD_GAP * 3
        val exactFitScreen = focusRightAt3 + GtvLayout.CONTENT_KEYLINE
        assertEquals(0f, GtvLayout.rowShiftX(3, GtvCardSize.MEDIUM, exactFitScreen), 0.01f)
        assertEquals(-1f, GtvLayout.rowShiftX(3, GtvCardSize.MEDIUM, exactFitScreen - 1f), 0.01f)
    }

    @Test fun `负索引夹到 0`() {
        assertEquals(0f, GtvLayout.rowShiftX(-3, GtvCardSize.MEDIUM, 960f), 0.01f)
    }

    // Fix round 1(R15,2026-09-20):这个值**不再是** Google 实测的 125.5——那是用 Latin 标题
    // (`Top picks for you`)量出来的行距,套用到中文标题上会裁字(见 ROW_TITLE_LINE/ROW_GAP 的
    // KDoc 与 task-9b-report.md)。143.5625 是 CJK 不裁切的前提下,同一条公式重新算出来的值,
    // 断言这个新值,不是要把它凑回 125.5。
    @Test fun `中档行间距 = CJK 不裁切前提下的值,不是 Google 的 Latin 125点5`() {
        // 23(CJK 实测行盒) + 12.5 + 14(焦点描边留白) + 86.0625(中档卡高) + 8(ROW_GAP) = 143.5625
        assertEquals(143.5625f, GtvLayout.rowPitch(GtvCardSize.MEDIUM, showTitles = false), 0.01f)
    }

    @Test fun `纵向位移按行数累加`() {
        // -2 × 143.5625 = -287.125
        assertEquals(-287.125f, GtvLayout.rowShiftY(2, GtvCardSize.MEDIUM, showTitles = false), 0.3f)
    }

    // Task 9b:CategoryRow 实际渲染的纵向每一项(标题行盒、标题到卡间距、焦点描边留白、卡高、
    // 行外间距)曾经各自散落在 HomeLayout 字面量与 GtvLayout 公式两处,互相对不上,累积成每行
    // 26.5dp 的漂移。这里刻意把 rowPitch() 该覆盖的每一项摊开重算一遍、不直接调 rowPitch() 本身
    // 去比 rowPitch() ——公式漏项或常数被悄悄改回旧值,这个测试才会跟着报错。
    //
    // **这个测试覆盖不到什么**(review 指出,如实记录):它只断言 GtvLayout 内部的常量与公式互相
    // 一致,是纯 JVM 测试,不渲染 Compose——如果 `HomeScreen.kt` 的 `CategoryRow` 某天又悄悄改回
    // 读 `HomeLayout.ROW_TITLE_LINE`/`ROW_TITLE_GAP`/`ROW_GAP`(本任务修的四个漂移来源里的三个),
    // 这个测试依然会通过,因为它根本不知道 `CategoryRow` 读的是哪个常量。这一类回归目前只能靠
    // Step 5 那样的装机 uiautomator 量测发现,没有自动化测试能兜底。
    @Test fun `rowPitch 等于纵向每一项之和,不允许再漏项`() {
        val expected = GtvLayout.ROW_TITLE_LINE +
            GtvLayout.ROW_TITLE_TO_CARD +
            2f * (GtvLayout.FOCUS_OUTSET + GtvLayout.FOCUS_STROKE) + // 焦点描边留白:上下各一份
            GtvLayout.cardHeight(GtvCardSize.MEDIUM) +
            GtvLayout.ROW_GAP
        assertEquals(expected, GtvLayout.rowPitch(GtvCardSize.MEDIUM, showTitles = false), 0.01f)
        // Fix round 1:不再对齐 Google 的 Latin 125.5——对齐的是「CJK 不裁切」这个新前提下的 143.5625,
        // 数值出处见 ROW_TITLE_LINE/ROW_GAP 各自的 KDoc,不是这里随手写的。
        assertEquals(143.5625f, expected, 0.01f)
    }

    // Fix 1(owner 反馈 R2,2026-09-20):showTitles = true 这个分支此前**没有任何断言覆盖**——
    // 上面所有 rowPitch 测试都只测 showTitles = false,`CategoryRow` 读错 CARD_TITLE_LINE / 漏加
    // titleHeight 这类回归全部测不出来。CARD_TITLE_LINE 从 16 改到 20(CJK 卡片标题不裁字,见该
    // 常量的 KDoc)让 titleHeight(true) 从 20 涨到 24,rowPitch(true) 应该跟着涨,不多不少正是
    // 这一份标题高度——这不是需要吸收的偏差,是显示标题时行间距该有的样子。
    @Test fun `显示标题时 rowPitch 比不显示恰好多出一份标题高度(showTitles=true 覆盖)`() {
        val withTitles = GtvLayout.rowPitch(GtvCardSize.MEDIUM, showTitles = true)
        val withoutTitles = GtvLayout.rowPitch(GtvCardSize.MEDIUM, showTitles = false)
        assertEquals(GtvLayout.titleHeight(true), withTitles - withoutTitles, 0.01f)
        // 167.5625 = 143.5625(showTitles=false)+ 24(CARD_TITLE_GAP 4 + CARD_TITLE_LINE 20)
        assertEquals(167.5625f, withTitles, 0.01f)
    }
}
