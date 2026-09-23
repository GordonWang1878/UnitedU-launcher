package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun `行溢出时只移动刚好够用的距离,不多移(R20,Round4 起用含缩放+描边的视觉右缘)`() {
        // 8 张卡的 MEDIUM 行,聚焦第 8 张(index 7):
        // 视觉 focusRight = 58 + 153×8 + 20×7 + overflow(153) = 1422 + 11.65 = 1433.65
        // overflow(153) = 153×0.10/2(缩放溢出的一半,APP_FOCUS_SCALE=1.10,整枝审查 C 起)
        //               + 2(APP_FOCUS_GAP) + 2(APP_FOCUS_STROKE) = 7.65 + 4 = 11.65
        // overRight = 1433.65 + 58 - 960 = 531.65
        assertEquals(-531.65f, GtvLayout.rowShiftX(7, GtvCardSize.MEDIUM, 960f), 0.01f)
        // 位移之后焦点卡的**视觉**右缘(含缩放+描边)= 1433.65 - 531.65 = 902 =
        // 960 - CONTENT_KEYLINE(58)——刚好贴右基准线,不多不少;owner 反馈 Round 4 之前这里断言的
        // 是布局右缘,现在必须是视觉右缘,否则最右那张完全可见的卡的描边会被屏幕边缘裁掉(§5)。
        assertEquals(960f - GtvLayout.CONTENT_KEYLINE, 1433.65f - 531.65f, 0.01f)
    }

    @Test fun `临界点连续,不会跳变(R20)`() {
        // 屏宽正好等于「视觉 focusRight(index 3,含 Round4 缩放+描边溢出) + 右留白」时位移为 0;
        // 屏宽再窄 1dp,位移就恰好是 1dp
        val focusRightAt3 = GtvLayout.CONTENT_KEYLINE +
            GtvLayout.cardWidth(GtvCardSize.MEDIUM) * 4 + GtvLayout.CARD_GAP * 3 +
            GtvLayout.appFocusOverflow(GtvLayout.cardWidth(GtvCardSize.MEDIUM))
        val exactFitScreen = focusRightAt3 + GtvLayout.CONTENT_KEYLINE
        assertEquals(0f, GtvLayout.rowShiftX(3, GtvCardSize.MEDIUM, exactFitScreen), 0.01f)
        assertEquals(-1f, GtvLayout.rowShiftX(3, GtvCardSize.MEDIUM, exactFitScreen - 1f), 0.01f)
    }

    @Test fun `负索引夹到 0`() {
        assertEquals(0f, GtvLayout.rowShiftX(-3, GtvCardSize.MEDIUM, 960f), 0.01f)
    }

    // Fix round 1(R15,2026-09-20):这个值**不再是** Google 实测的 125.5——那是用 Latin 标题
    // (`Top picks for you`)量出来的行距,套用到中文标题上会裁字(见 ROW_TITLE_LINE/ROW_GAP 的
    // KDoc 与 task-9b-report.md)。140.5625 是 CJK 不裁切的前提下,同一条公式重新算出来的值,
    // 断言这个新值,不是要把它凑回 125.5。
    // owner 反馈 Round 5(R25):ROW_TITLE_LINE 从 23(16sp 实测)改为 20(14sp 重新实测),
    // 143.5625 随之变成 140.5625——同一条公式换了正确输入之后的正确结果,数值出处见
    // ROW_TITLE_LINE 的 KDoc,不是这里另外调整的。
    // Ruling R48(2026-09-22):首页取消行标题,行距去掉标题行盒 20 + 标题到卡 12.5 = 32.5 dp,
    // 140.5625 → 108.0625(效果图 A2「行距收紧 32dp」)。
    // Ruling R51(2026-09-23):ROW_GAP 8 → 40,108.0625 → 140.0625。
    @Test fun `中档行间距 = R48 无行标题 + R51 行距 40`() {
        // 14(焦点描边留白) + 86.0625(中档卡高) + 40(ROW_GAP) = 140.0625
        assertEquals(140.0625f, GtvLayout.rowPitch(GtvCardSize.MEDIUM, showTitles = false), 0.01f)
    }

    @Test fun `纵向位移按行数累加`() {
        // -2 × 140.0625 = -280.125
        assertEquals(-280.125f, GtvLayout.rowShiftY(2, GtvCardSize.MEDIUM, showTitles = false), 0.3f)
    }

    // R48 效果图 A2:行距逐项;行图标在左边距里,水平中心 = CONTENT_KEYLINE / 2。
    // (R52 起行 0 静止卡顶不再钉 301.5,改由焦点线决定,见下面 R52 的几个测试。)
    @Test fun `R48 无行标题几何——行距逐项、图标在左边距里不碰焦点卡`() {
        for (size in GtvCardSize.values()) for (titles in listOf(false, true)) {
            // 行距逐项,每项写字面量、不拿 rowPitch 去比 rowPitch:上下描边留白 2 × 7 + 卡高
            // + 卡片标题(开着时 4 + 20)+ ROW_GAP(R48 时 8,R51 起 40)。R48 前还有行标题行盒 20 + 标题到卡
            // 12.5 两项。
            val perItem = 2f * 7f + GtvLayout.cardHeight(size) + (if (titles) 4f + 20f else 0f) + 40f
            assertEquals("$size titles=$titles 行距", perItem, GtvLayout.rowPitch(size, titles), 0.001f)
        }
        assertEquals(7f, GtvLayout.ROW_CARD_TOP, 0f)
        // R50(2026-09-23):26 → 22,owner「偏大了,改小一点点」
        assertEquals(22f, GtvLayout.ROW_ICON_SIZE, 0f)
        val iconRight = GtvLayout.CONTENT_KEYLINE / 2f + GtvLayout.ROW_ICON_SIZE / 2f
        assertEquals(40f, iconRight, 0f)
        for (size in GtvCardSize.values()) {
            // 焦点卡放大 + 描边后的视觉左缘仍在图标右边:LARGE 58 − 13.6 = 44.4 > 40
            val focusLeft = GtvLayout.CONTENT_KEYLINE - GtvLayout.appFocusOverflow(GtvLayout.cardWidth(size))
            assertTrue("$size 焦点卡左缘 $focusLeft 压到图标右缘 $iconRight", focusLeft > iconRight)
            // 与卡片纵向居中:图标比最矮的卡还矮,不伸出本行
            assertTrue(GtvLayout.ROW_ICON_SIZE < GtvLayout.cardHeight(size))
        }
    }

    // Ruling R52(owner 2026-09-23,推翻 R42 最小位移):固定焦点线。owner 原话:「首页一上来默认只显示一行,
    // 而且刚好一行,整体位置要往下调。往下滑动的时候,再出动效向上滑并显示第二行,再滑一次出第三行。」
    private val M = GtvCardSize.MEDIUM
    private val SIZES_TITLES = GtvCardSize.values().flatMap { s -> listOf(false, true).map { s to it } }
    private val HEIGHTS = listOf(540f, 720f, 1080f)

    @Test fun `R52 焦点线——中档无标题 540 屏 413点6,行 0 视觉下沿离屏底恰好 32`() {
        assertEquals(32f, GtvLayout.HOME_BOTTOM_MARGIN, 0f)
        // 540 − 32 − (86.0625 + 86.0625 × 0.05 + 2 + 2) = 540 − 32 − 94.366 = 413.634
        assertEquals(413.634f, GtvLayout.focusLineCardTop(M, false, 540f), 0.001f)
        assertEquals(413.634f - 7f, GtvLayout.rowsTop(M, false, 540f), 0.001f)
        for ((size, titles) in SIZES_TITLES) for (h in HEIGHTS) {
            assertEquals("$size titles=$titles h=$h 行 0 静止卡顶 = 焦点线",
                GtvLayout.focusLineCardTop(size, titles, h), GtvLayout.restCardTop(0, size, titles, h), 0f)
            assertEquals("$size titles=$titles h=$h 行 0 视觉下沿",
                h - GtvLayout.HOME_BOTTOM_MARGIN, GtvLayout.restRowVisibleBottom(0, size, titles, h), 0.001f)
        }
    }

    @Test fun `R52 静止恰好只露一行——三档 × 标题开关,行 1 卡顶在屏外、行 0 底边在屏底 32 之内`() {
        for ((size, titles) in SIZES_TITLES) for (h in HEIGHTS) {
            val row1Top = GtvLayout.restCardTop(1, size, titles, h)
            assertTrue("$size titles=$titles h=$h 行 1 卡顶 $row1Top 露在屏内", row1Top >= h)
            // 行 1 视觉上沿(卡顶 − 聚焦溢出;静止时行 1 不聚焦,这是更严的一侧)同样在屏外
            assertTrue("$size titles=$titles h=$h 行 1 视觉上沿露在屏内", GtvLayout.restVisibleTop(1, size, titles, h) >= h)
            val row0Bottom = GtvLayout.restRowVisibleBottom(0, size, titles, h)
            assertTrue("$size titles=$titles h=$h 行 0 底边 $row0Bottom 超出屏底 − 32",
                row0Bottom <= h - GtvLayout.HOME_BOTTOM_MARGIN + 0.001f)
            // 行 0 整行在顶栏之下(焦点行不会被 R53 的顶栏淡出碰到)
            assertTrue(GtvLayout.restVisibleTop(0, size, titles, h) > GtvLayout.TOP_BAR_TOP + GtvLayout.TOP_BAR_HEIGHT)
        }
        // 540 屏中档无标题:行 1 卡顶 = 413.634 + 140.0625 = 553.70
        assertEquals(553.70f, GtvLayout.restCardTop(1, M, false, 540f), 0.01f)
    }

    @Test fun `R52 每步一整行、焦点行卡顶恒在焦点线、顶栏与行 0 同为静止`() {
        for ((size, titles) in SIZES_TITLES) for (h in HEIGHTS) {
            val line = GtvLayout.focusLineCardTop(size, titles, h)
            val pitch = GtvLayout.rowPitch(size, titles)
            assertEquals("顶栏(activeRow 负值)与行 0 同为静止", 0f, GtvLayout.rowShiftY(-1, size, titles), 0f)
            assertEquals(0f, GtvLayout.rowShiftY(0, size, titles), 0f)
            for (n in 0 until MAX_ROWS + 1) {   // 输入源行 + 5 条应用行 = 最多 6 行
                val s = GtvLayout.rowShiftY(n, size, titles)
                assertEquals("$size titles=$titles h=$h 行 $n 焦点行卡顶", line, GtvLayout.restCardTop(n, size, titles, h) + s, 0.001f)
                if (n > 0) assertEquals("行 ${n - 1} → $n 走一整行", -pitch, s - GtvLayout.rowShiftY(n - 1, size, titles), 0.001f)
            }
        }
    }

    @Test fun `R52 上下对称——下到底再上回顶栏,每一行的位移与下来时逐位相同`() {
        val rows = 5
        val down = (-1 until rows).map { GtvLayout.rowShiftY(it, M, false) }
        val up = (rows - 1 downTo -1).map { GtvLayout.rowShiftY(it, M, false) }
        assertEquals(down, up.reversed())
        // 540 屏中档无标题:0, 0, −140.06, −280.13, −420.19, −560.25
        assertEquals(-560.25f, down.last(), 0.01f)
        // 壁纸 alpha 随位移(R45 单层,不改):静止全亮,行 1 起开始变暗,行 2 起到底 0.2
        assertEquals(1f, GtvLayout.wallpaperAlpha(down[1]), 0f)
        assertEquals(1f - 0.8f * 140.0625f / 192f, GtvLayout.wallpaperAlpha(down[2]), 1e-4f)
        assertEquals(0.2f, GtvLayout.wallpaperAlpha(down[3]), 1e-6f)
    }

    @Test fun `R45 单层壁纸——alpha 随位移从 1 线性降到 20%,不位移`() {
        assertEquals(GtvLayout.HERO_HEIGHT, GtvLayout.WALLPAPER_FADE_OVER_DP, 0f)
        assertEquals(0.20f, GtvLayout.WALLPAPER_BROWSE_ALPHA, 0f)
        val d = GtvLayout.WALLPAPER_FADE_OVER_DP
        // 0 → 1(静止态全亮)、FADE_OVER → 0.2、一半 → 0.6;越界夹紧;按绝对值算。
        assertEquals(1f, GtvLayout.wallpaperAlpha(0f), 0f)
        assertEquals(0.2f, GtvLayout.wallpaperAlpha(-d), 1e-6f)
        assertEquals(0.6f, GtvLayout.wallpaperAlpha(-d / 2f), 1e-6f)
        assertEquals(0.2f, GtvLayout.wallpaperAlpha(-1000f), 1e-6f)
        assertEquals(0.6f, GtvLayout.wallpaperAlpha(d / 2f), 1e-6f)
        assertEquals(1f, GtvLayout.wallpaperAlpha(1e-9f), 1e-6f)
        // 单调不增,且永远不低于 0.2(浏览态保留两成壁纸)。
        val samples = (0..20).map { GtvLayout.wallpaperAlpha(-it * 20f) }
        assertTrue(samples.zipWithNext().all { (x, y) -> y <= x })
        assertTrue(samples.all { it >= GtvLayout.WALLPAPER_BROWSE_ALPHA - 1e-6f })
    }

    // Ruling R53(owner 2026-09-23 同轮):R52 焦点线靠下,焦点行上面的行会升到顶栏下——按当前卡顶淡出。
    @Test fun `R53 顶栏下淡出——卡顶 110 以下全亮、70 处为 0、中间线性`() {
        assertEquals(40f, GtvLayout.TOP_FADE_BAND, 0f)
        val barBottom = GtvLayout.TOP_BAR_TOP + GtvLayout.TOP_BAR_HEIGHT
        assertEquals(70f, barBottom, 0f)
        assertEquals(1f, GtvLayout.topFadeAlpha(110f), 0f)
        assertEquals(1f, GtvLayout.topFadeAlpha(413f), 0f)
        assertEquals(0f, GtvLayout.topFadeAlpha(70f), 0f)
        assertEquals(0f, GtvLayout.topFadeAlpha(-7f), 0f)
        assertEquals(0.5f, GtvLayout.topFadeAlpha(90f), 1e-6f)
        // 单调不减
        val a = (0..60).map { GtvLayout.topFadeAlpha(40f + it * 2f) }
        assertTrue(a.zipWithNext().all { (x, y) -> y >= x })
    }

    @Test fun `R53 焦点行永远全亮,焦点行上两行在 540 屏中档无标题时仍全亮、上三行全透明`() {
        for ((size, titles) in SIZES_TITLES) for (h in HEIGHTS) {
            assertEquals("$size titles=$titles h=$h 焦点行", 1f, GtvLayout.topFadeAlpha(GtvLayout.focusLineCardTop(size, titles, h)), 0f)
        }
        val line = GtvLayout.focusLineCardTop(M, false, 540f)
        val pitch = GtvLayout.rowPitch(M, false)
        assertEquals(133.51f, line - 2 * pitch, 0.01f)   // 裁定里的「约 133」
        assertEquals(1f, GtvLayout.topFadeAlpha(line - 2 * pitch), 0f)
        assertEquals(-6.55f, line - 3 * pitch, 0.01f)    // 裁定里的「约 −7」
        assertEquals(0f, GtvLayout.topFadeAlpha(line - 3 * pitch), 0f)
    }

    // R48 的可见区间:不复述 restVisibleTop 的公式,钉它在 KDoc 里承诺的两条性质——
    // ①上下对称:卡顶之上与卡底之下(不算卡片标题)各留一份同样的聚焦溢出;
    // ②行图标(与卡片纵向居中)整个落在区间里,所以它不必单独进区间。
    @Test fun `R48 可见区间——上下各一份相同的聚焦溢出,行图标落在区间内`() {
        for (size in GtvCardSize.values()) for (titles in listOf(false, true)) for (r in 0 until MAX_ROWS) {
            val cardTop = GtvLayout.restCardTop(r, size, titles, 540f)
            val cardBottom = cardTop + GtvLayout.cardHeight(size)
            val top = GtvLayout.restVisibleTop(r, size, titles, 540f)
            val bottom = GtvLayout.restRowVisibleBottom(r, size, titles, 540f)
            val above = cardTop - top
            val below = bottom - GtvLayout.titleHeight(titles) - cardBottom
            assertTrue("$size titles=$titles 行 $r:卡顶之上要留出聚焦溢出", above > 0f)
            assertEquals("$size titles=$titles 行 $r:上下溢出对称", above, below, 0.001f)
            val iconTop = cardTop + GtvLayout.cardHeight(size) / 2f - GtvLayout.ROW_ICON_SIZE / 2f
            assertTrue("$size 行 $r 图标顶 $iconTop 高出可见上沿 $top", iconTop >= top)
            assertTrue("$size 行 $r 图标底高出可见下沿 $bottom", iconTop + GtvLayout.ROW_ICON_SIZE <= bottom)
        }
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
        // R48:首页没有行标题,标题行盒与标题到卡两项已去掉。
        val expected = 2f * (GtvLayout.FOCUS_OUTSET + GtvLayout.FOCUS_STROKE) + // 焦点描边留白:上下各一份
            GtvLayout.cardHeight(GtvCardSize.MEDIUM) +
            GtvLayout.ROW_GAP
        assertEquals(expected, GtvLayout.rowPitch(GtvCardSize.MEDIUM, showTitles = false), 0.01f)
        // Fix round 1:不再对齐 Google 的 Latin 125.5——对齐的是「CJK 不裁切」这个新前提下的值,
        // 数值出处见 ROW_TITLE_LINE/ROW_GAP 各自的 KDoc,不是这里随手写的。
        // owner 反馈 Round 5(R25):140.5625 是 ROW_TITLE_LINE 改成 20(14sp)之后的新值,取代
        // 之前 16sp 下测得的 143.5625。R48 去掉行标题带(32.5)后是 108.0625;R51 ROW_GAP 8 → 40 后是 140.0625。
        assertEquals(140.0625f, expected, 0.01f)
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
        // 164.0625 = 140.0625(showTitles=false,R51 之后的新值)+ 24(CARD_TITLE_GAP 4 + CARD_TITLE_LINE 20)
        assertEquals(164.0625f, withTitles, 0.01f)
    }

    // owner 反馈 Round 4(2026-09-21)§5:「验证,不要假设」——app 卡片聚焦缩放
    // (GtvLayout.APP_FOCUS_SCALE)+ 贴边描边比原来的静态外扩(FOCUS_OUTSET+FOCUS_STROKE)更往外
    // 探,必须确认这份新的视觉溢出不会碰到下一行。可用的纵向余量是
    // rowVerticalPad(= FOCUS_OUTSET+FOCUS_STROKE,任务明确要求不改 rowPitch,这个量因此维持
    // 原值)+ ROW_GAP——这是「本行卡片内容结束」到「下一行布局块开始」之间的物理间距。R48 起首页
    // 没有行标题,下一行布局块一开头就是它卡片上方的 7dp 描边留白带(R48 前是下一行的标题行盒),
    // 与 rowPitch() 的推导一致(见该函数 KDoc)。SMALL/MEDIUM/LARGE 三档都测,任务特别点名
    // LARGE(108dp 高、溢出最大)。
    @Test fun `app 卡片聚焦缩放溢出不会碰到下一行卡片的描边留白带(owner 反馈 Round4 §5)`() {
        for (size in GtvCardSize.values()) {
            // 逐档从 Theme.gtvCardMetrics 取 rowVerticalPad——现在各档数值相同(常量不随 size 变),
            // 但这里不假设"以后也一定相同",按各自档位实际配置的值算,以后有人改了也不会漏测。
            val available = Theme.gtvCardMetrics(size).rowVerticalPad.value + GtvLayout.ROW_GAP
            val overflowY = GtvLayout.appFocusOverflow(GtvLayout.cardHeight(size))
            assertTrue(
                "$size 的纵向溢出 ${overflowY}dp 超过了可用余量 ${available}dp,会压进下一行卡片的描边留白带",
                overflowY < available,
            )
        }
        // LARGE 档的具体数字留痕(108 × 0.10 / 2 + 2 + 2 = 5.4 + 4 = 9.4dp;整枝审查 C 把缩放
        // 1.105 → 1.10 之前是 9.67),对照任务原话「108 dp 高 → 5.7 dp overflow」——那句话只算了
        // 缩放那一半,没有加上描边的 2dp gap + 2dp stroke;这里连描边一起算,是「贴到屏幕/下一行的
        // 实际视觉边界」,不是纯缩放量。
        assertEquals(9.4f, GtvLayout.appFocusOverflow(GtvLayout.cardHeight(GtvCardSize.LARGE)), 0.01f)
    }

    // Ruling R31(owner 反馈 Round 8):聚焦描边与缩放后的卡片同心——半径 = corner × scale + gap + stroke/2。
    // 此前 r = corner + (outX + outY)/2 把缩放长出的 growX/growY 也算进半径,MEDIUM 卡算出约 19dp,
    // 而缩放后卡片圆角只有 8.8dp,描边比卡片圆得多。数值钉死:8×1.10 + 2 + 2/2 = 11.8。
    @Test fun `聚焦描边圆角与缩放后卡片同心(R31)`() {
        assertEquals(
            11.8f,
            GtvLayout.focusRingRadius(GtvLayout.CARD_CORNER, GtvLayout.APP_FOCUS_SCALE, GtvLayout.APP_FOCUS_GAP, GtvLayout.APP_FOCUS_STROKE),
            0.0001f,
        )
        // 未缩放(scale = 1)时退化为 corner + 中心线偏移,与内容卡 gtvFocusStroke 的 r = corner + out 同一条不变量
        assertEquals(8f + 2f + 1f, GtvLayout.focusRingRadius(8f, 1f, 2f, 2f), 0.0001f)
        // 单位无关:px 进 px 出(density 2 的这台机型,8dp = 16px)
        assertEquals(23.6f, GtvLayout.focusRingRadius(16f, 1.1f, 4f, 4f), 0.0001f)
    }

    // Ruling R49(效果图 B4):gray = (R+G+B)/3,out = (gray + (c − gray) × 0.30) × 0.75。
    // 矩阵逐点对照效果图公式,灰度是三通道等权平均(不是 Rec.709)。
    @Test fun `R49 卡片淡化矩阵 = B4 公式(饱和度 30%、亮度 × 0点75、等权灰度)`() {
        assertEquals(0.30f, GtvLayout.CARD_FADE_SATURATION, 0f)
        assertEquals(0.75f, GtvLayout.CARD_FADE_BRIGHTNESS, 0f)
        val m = GtvLayout.cardFadeMatrix()
        assertEquals(20, m.size)
        fun apply(r: Float, g: Float, b: Float): FloatArray =
            FloatArray(3) { row -> m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4] }
        fun b4(r: Float, g: Float, b: Float): FloatArray {
            val gray = (r + g + b) / 3f
            return floatArrayOf(r, g, b).map { (gray + (it - gray) * 0.30f) * 0.75f }.toFloatArray()
        }
        val rnd = java.util.Random(49)
        repeat(500) {
            val c = FloatArray(3) { rnd.nextFloat() }
            val got = apply(c[0], c[1], c[2]); val want = b4(c[0], c[1], c[2])
            for (k in 0..2) assertEquals(want[k], got[k], 1e-5f)
        }
        // 具体数:白 → 0.75 灰;纯红 → (0.4, 0.175, 0.175);alpha 行不动。
        apply(1f, 1f, 1f).forEach { assertEquals(0.75f, it, 1e-6f) }
        val red = apply(1f, 0f, 0f)
        assertEquals(0.4f, red[0], 1e-6f); assertEquals(0.175f, red[1], 1e-6f); assertEquals(0.175f, red[2], 1e-6f)
        assertEquals(listOf(0f, 0f, 0f, 1f, 0f), m.slice(15..19))
    }
}
