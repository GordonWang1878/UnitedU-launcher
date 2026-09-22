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
    @Test fun `中档行间距 = CJK 不裁切前提下的值,不是 Google 的 Latin 125点5`() {
        // 20(CJK 实测行盒,14sp) + 12.5 + 14(焦点描边留白) + 86.0625(中档卡高) + 8(ROW_GAP) = 140.5625
        assertEquals(140.5625f, GtvLayout.rowPitch(GtvCardSize.MEDIUM, showTitles = false), 0.01f)
    }

    @Test fun `纵向位移按行数累加`() {
        // -2 × 140.5625 = -281.125
        assertEquals(-281.125f, GtvLayout.rowShiftY(2, GtvCardSize.MEDIUM, showTitles = false), 0.3f)
    }

    // Ruling R32(owner 反馈 Round 9):整页位移——行 1 及以下的卡顶钉到 BROWSE_ROW_ANCHOR(120dp,
    // §8b 的 y=240px 是卡片 a11y bounds 顶边),行 0 静止态不动、hero 露出。三个行号逐个钉住。
    @Test fun `R32 整页位移——行 0 为 0,行 1 = 行 1 静止卡顶 − 锚点,行 2 再加一个 pitch`() {
        val pitch = GtvLayout.rowPitch(GtvCardSize.MEDIUM, showTitles = false)
        assertEquals(0f, GtvLayout.pageShiftY(0, GtvCardSize.MEDIUM, showTitles = false), 0f)
        assertEquals("顶栏(activeRow 负值)与行 0 同为静止态", 0f, GtvLayout.pageShiftY(-1, GtvCardSize.MEDIUM, false), 0f)
        // 行 1 静止卡顶 = 顶栏 34+36 + hero 192 + 行内 20+12.5+7 + 1 × pitch = 301.5 + 140.5625 = 442.0625
        val rest1 = GtvLayout.restCardTop(1, GtvCardSize.MEDIUM, showTitles = false)
        assertEquals(442.0625f, rest1, 0.01f)
        val shift1 = GtvLayout.pageShiftY(1, GtvCardSize.MEDIUM, showTitles = false)
        assertEquals(GtvLayout.BROWSE_ROW_ANCHOR - rest1, shift1, 0.01f)
        assertEquals("行 1 一次走 hero+顶栏+行内卡顶−锚 + 1 pitch ≈ 322dp(量级 300+,不再是一格 143)", -322.0625f, shift1, 0.01f)
        val shift2 = GtvLayout.pageShiftY(2, GtvCardSize.MEDIUM, showTitles = false)
        assertEquals(shift1 - pitch, shift2, 0.01f)
        // 不变量:位移后焦点行的卡顶恒在锚点上(与横向「焦点卡钉基准线」同构,§8b)
        for (r in 1..5) {
            val top = GtvLayout.restCardTop(r, GtvCardSize.MEDIUM, false) + GtvLayout.pageShiftY(r, GtvCardSize.MEDIUM, false)
            assertEquals("行 $r 位移后卡顶", GtvLayout.BROWSE_ROW_ANCHOR, top, 0.01f)
        }
    }

    // Ruling R42(owner 真机反馈 2026-09-22,覆盖 R32 锚点):最小位移 + 粘性,纵向版 R20。
    private val M = GtvCardSize.MEDIUM
    private fun next(prev: Float, row: Int, rows: Int = 4, h: Float = 540f, size: GtvCardSize = M, titles: Boolean = false) =
        GtvLayout.nextPageShiftY(prev, row, rows, size, titles, h)
    private fun align(h: Float) = h - GtvLayout.BOTTOM_SAFE
    private fun bot(r: Int, titles: Boolean = false, size: GtvCardSize = M) = GtvLayout.restRowVisibleBottom(r, size, titles)
    private fun top(r: Int, titles: Boolean = false, size: GtvCardSize = M) = GtvLayout.restTitleTop(r, size, titles)

    @Test fun `R42 窗口边界——顶栏下 16dp、对齐线留 CONTENT_KEYLINE、下沿含聚焦溢出与卡片标题`() {
        assertEquals(86f, GtvLayout.TOP_SAFE, 0f)
        assertEquals(58f, GtvLayout.BOTTOM_SAFE, 0f)
        val ch = GtvLayout.cardHeight(M)
        assertEquals(GtvLayout.restCardTop(1, M, false) + ch + GtvLayout.appFocusOverflow(ch), bot(1), 0.01f)
        assertEquals(GtvLayout.restCardTop(1, M, true) + ch + GtvLayout.appFocusOverflow(ch) + GtvLayout.titleHeight(true),
            bot(1, titles = true), 0.01f)
        // 960×540 屏、中档无标题:行 1 下沿 536.43 在画幅内(owner 眼里「两行完整可见」)
        assertEquals(536.43f, bot(1), 0.01f)
    }

    @Test fun `R42 owner 硬验收——两行都在画幅内时下移、上移页面都不动`() {
        // 隐藏输入源行后只剩两行应用(960×540,中档,无标题)
        val down = next(0f, 1, rows = 2)
        assertEquals(0f, down, 0f)
        assertEquals(0f, next(down, 0, rows = 2), 0f)
        assertEquals(0f, next(0f, 1, rows = 2), 0f)
        // 壁纸上层 alpha 随位移:位移 0 → 全亮,不淡
        assertEquals(1f, GtvLayout.wallpaperAlpha(down), 0f)
    }

    @Test fun `R42 不变量——内容总高放得下时,任意行任意焦点序列位移恒为 0`() {
        val rnd = java.util.Random(42)
        for (size in GtvCardSize.values()) for (titles in listOf(false, true)) for (h in listOf(540f, 720f, 1080f)) {
            for (rows in 1..8) {
                val fits = bot(rows - 1, titles, size) <= h
                if (!fits) continue
                var s = 0f
                repeat(200) {
                    val r = rnd.nextInt(rows + 1) - 1   // -1 = 顶栏
                    s = GtvLayout.nextPageShiftY(s, r, rows, size, titles, h)
                    assertEquals("size=$size titles=$titles h=$h rows=$rows row=$r", 0f, s, 0f)
                }
            }
        }
    }

    @Test fun `R42 行 1 完整可见时下键不动,放不下的行只上移到对齐线,不再钉锚点`() {
        // 4 行、960×540:行 1 在画幅内 → 0(R32 这里是 −322)
        val s1 = next(0f, 1)
        assertEquals(0f, s1, 0f)
        // 行 2 出了底边 → 下沿贴 540 − 58 = 482
        val s2 = next(s1, 2); val s3 = next(s2, 3)
        assertEquals(align(540f), bot(2) + s2, 0.01f)
        assertEquals(align(540f), bot(3) + s3, 0.01f)
        assertEquals(-195.0f, s2, 0.1f)
        assertEquals(-335.6f, s3, 0.1f)
        assertTrue(s2 > GtvLayout.pageShiftY(2, M, false))
        for ((r, s) in listOf(2 to s2, 3 to s3)) assertTrue("行 $r 标题露全", top(r) + s >= GtvLayout.TOP_SAFE)
    }

    @Test fun `R42 往上走只在出顶边时回移,且只移到上沿贴上界,回行 0 归 0`() {
        val s3 = next(next(next(0f, 1), 2), 3)
        val up2 = next(s3, 2)
        assertEquals("行 3 → 行 2:行 2 仍在窗口内,不回跳", s3, up2, 0f)
        assertTrue(top(1) + up2 < GtvLayout.TOP_SAFE)
        val up1 = next(up2, 1)
        assertEquals(GtvLayout.TOP_SAFE, top(1) + up1, 0.01f)
        assertEquals(0f, next(up1, 0), 0f)
        assertEquals(0f, next(up1, -1), 0f)
    }

    @Test fun `R42 粘性——同一行重算不改位移,行数变少夹回,区间比窗口高时上沿优先`() {
        val s2 = next(next(0f, 1), 2)
        assertEquals(s2, next(s2, 2), 0f)
        assertEquals(align(540f) - bot(2), next(-1000f, 2, rows = 3), 0.01f)
        assertEquals(GtvLayout.TOP_SAFE, top(1) + next(0f, 1, h = 200f), 0.01f)
    }

    @Test fun `R35+R36 两层壁纸——上层随整页淡到 0,底层常驻 20% 影子`() {
        assertEquals(GtvLayout.HERO_HEIGHT, GtvLayout.WALLPAPER_FADE_OVER_DP, 0f)
        // 底层常量:owner 给的 20%。浏览态看到的影子就是它(上层已淡完)。
        assertEquals(0.20f, GtvLayout.WALLPAPER_BROWSE_ALPHA, 0f)
        // 上层:0 全亮,一个 hero 高度淡完,一半 0.5,越界夹紧,按绝对值算。
        assertEquals(1f, GtvLayout.wallpaperAlpha(0f), 0f)
        assertEquals(0f, GtvLayout.wallpaperAlpha(-GtvLayout.HERO_HEIGHT), 1e-6f)
        assertEquals(0.5f, GtvLayout.wallpaperAlpha(-GtvLayout.HERO_HEIGHT / 2f), 1e-6f)
        assertEquals(0f, GtvLayout.wallpaperAlpha(-1000f), 1e-6f)
        assertEquals(0.5f, GtvLayout.wallpaperAlpha(GtvLayout.HERO_HEIGHT / 2f), 1e-6f)
        val shift1 = GtvLayout.pageShiftY(1, GtvCardSize.MEDIUM, showTitles = false)
        assertTrue(-shift1 > GtvLayout.HERO_HEIGHT)
        assertEquals(0f, GtvLayout.wallpaperAlpha(shift1), 1e-6f)
        val samples = (0..20).map { GtvLayout.wallpaperAlpha(-it * 20f) }
        assertTrue(samples.zipWithNext().all { (x, y) -> y <= x })
    }

    @Test fun `R43 行标题焦点态常量 + restTitleTop 与 restCardTop 同源`() {
        val size = GtvCardSize.MEDIUM; val titles = false
        val pitch = GtvLayout.rowPitch(size, titles)
        // 静止标题顶 = ROWS_TOP + row × pitch;卡顶比它多 ROW_CARD_TOP(R42 的最小位移仍读它)。
        assertEquals(GtvLayout.ROWS_TOP, GtvLayout.restTitleTop(0, size, titles), 0.01f)
        assertEquals(GtvLayout.ROWS_TOP + 2 * pitch, GtvLayout.restTitleTop(2, size, titles), 0.01f)
        assertEquals(GtvLayout.restCardTop(2, size, titles) - GtvLayout.ROW_CARD_TOP, GtvLayout.restTitleTop(2, size, titles), 0.01f)
        // Google 实测:焦点 270 px / 非焦点 154 px ≈ 1.75;灰 ≈ 0.7 白;≈ 300 ms。
        assertEquals(1.75f, GtvLayout.ROW_TITLE_FOCUS_SCALE, 1e-6f)
        assertEquals(0.7f, GtvLayout.ROW_TITLE_UNFOCUSED_ALPHA, 1e-6f)
        assertEquals(300, GtvLayout.ROW_TITLE_FOCUS_MS)
    }

    @Test fun `R32 锚点在顶栏之下——浏览态焦点行的标题不与顶栏重叠`() {
        // 焦点行标题顶 = 锚 − ROW_CARD_TOP = 120 − 39.5 = 80.5,顶栏底 = 34 + 36 = 70
        val titleTop = GtvLayout.BROWSE_ROW_ANCHOR - GtvLayout.ROW_CARD_TOP
        assertTrue("标题顶 $titleTop 应在顶栏底 ${GtvLayout.TOP_BAR_TOP + GtvLayout.TOP_BAR_HEIGHT} 之下",
            titleTop > GtvLayout.TOP_BAR_TOP + GtvLayout.TOP_BAR_HEIGHT)
        assertEquals(39.5f, GtvLayout.ROW_CARD_TOP, 0.01f)
        assertEquals(262f, GtvLayout.ROWS_TOP, 0.01f)
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
        // Fix round 1:不再对齐 Google 的 Latin 125.5——对齐的是「CJK 不裁切」这个新前提下的值,
        // 数值出处见 ROW_TITLE_LINE/ROW_GAP 各自的 KDoc,不是这里随手写的。
        // owner 反馈 Round 5(R25):140.5625 是 ROW_TITLE_LINE 改成 20(14sp)之后的新值,取代
        // 之前 16sp 下测得的 143.5625。
        assertEquals(140.5625f, expected, 0.01f)
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
        // 164.5625 = 140.5625(showTitles=false,R25 之后的新值)+ 24(CARD_TITLE_GAP 4 + CARD_TITLE_LINE 20)
        assertEquals(164.5625f, withTitles, 0.01f)
    }

    // owner 反馈 Round 4(2026-09-21)§5:「验证,不要假设」——app 卡片聚焦缩放
    // (GtvLayout.APP_FOCUS_SCALE)+ 贴边描边比原来的静态外扩(FOCUS_OUTSET+FOCUS_STROKE)更往外
    // 探,必须确认这份新的视觉溢出不会碰到下一行的标题字形。可用的纵向余量是
    // rowVerticalPad(= FOCUS_OUTSET+FOCUS_STROKE,任务明确要求不改 rowPitch,这个量因此维持
    // 原值)+ ROW_GAP——这是「本行卡片内容结束」到「下一行标题行盒开始」之间的物理间距,
    // 与 rowPitch() 的推导一致(见该函数 KDoc)。SMALL/MEDIUM/LARGE 三档都测,任务特别点名
    // LARGE(108dp 高、溢出最大)。
    @Test fun `app 卡片聚焦缩放溢出不会碰到下一行标题(owner 反馈 Round4 §5)`() {
        for (size in GtvCardSize.values()) {
            // 逐档从 Theme.gtvCardMetrics 取 rowVerticalPad——现在各档数值相同(常量不随 size 变),
            // 但这里不假设"以后也一定相同",按各自档位实际配置的值算,以后有人改了也不会漏测。
            val available = Theme.gtvCardMetrics(size).rowVerticalPad.value + GtvLayout.ROW_GAP
            val overflowY = GtvLayout.appFocusOverflow(GtvLayout.cardHeight(size))
            assertTrue(
                "$size 的纵向溢出 ${overflowY}dp 超过了可用余量 ${available}dp,会碰到下一行标题",
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
}
