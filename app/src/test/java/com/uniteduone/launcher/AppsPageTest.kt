package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** R90 所有应用页的纯逻辑:分组 + 排序、网格行、自算纵向位移、「加到桌面」写盘变换。 */
class AppsPageTest {
    private fun cand(pkg: String, label: String, g: PickerGroup = PickerGroup.APPS) =
        PickerCandidate(AppEntry(pkg, label, card = null, isWide = false), g)

    @Test fun appsFirstThenSystemToolsEachByDisplayName() {
        val d = appsPageData(
            listOf(
                cand("t.settings", "Settings", PickerGroup.SYSTEM_TOOLS),
                cand("a.youku", "优酷"),
                cand("a.bili", "bilibili"),
                cand("t.store", "Play Store", PickerGroup.SYSTEM_TOOLS),
                cand("a.qq", "QQ音乐"),
            ),
            // 改过名的按改过的名字排、显示;空白标题不算改名
            titles = mapOf("a.youku" to "Aaa 优酷", "a.qq" to "  "),
        )
        assertEquals(listOf("a.youku", "a.bili", "a.qq", "t.store", "t.settings"), d.items.map { it.app.packageName })
        assertEquals("Aaa 优酷", d.items[0].app.label)
        assertEquals("QQ音乐", d.items[2].app.label)
        assertEquals(3, d.appCount)
    }

    @Test fun linesChunkEachGroupAndHeaderOnlyWithTools() {
        assertEquals(
            listOf(AppsLine.Title, AppsLine.Cards(0, 6), AppsLine.Cards(6, 1), AppsLine.ToolsHeader, AppsLine.Cards(7, 2)),
            appsPageLines(apps = 7, tools = 2, columns = 6),
        )
        // 没有系统工具:不画分组标题
        assertEquals(listOf(AppsLine.Title, AppsLine.Cards(0, 3)), appsPageLines(apps = 3, tools = 0, columns = 6))
        // 只有系统工具(极端:应用组空)
        assertEquals(listOf(AppsLine.Title, AppsLine.ToolsHeader, AppsLine.Cards(0, 2)), appsPageLines(0, 2, 6))
    }

    @Test fun lineOfItem() {
        val lines = appsPageLines(apps = 7, tools = 2, columns = 6)
        assertEquals(1, appsLineOf(lines, 0))
        assertEquals(1, appsLineOf(lines, 5))
        assertEquals(2, appsLineOf(lines, 6))
        assertEquals(4, appsLineOf(lines, 7))
        assertEquals(-1, appsLineOf(lines, 99))
    }

    @Test fun sixSmallCardsFitTheScreenWithFocusOverflow() {
        val w = AppsPageLayout.COLUMNS * GtvLayout.cardWidth(AppsPageLayout.CARD_SIZE) +
            (AppsPageLayout.COLUMNS - 1) * GtvLayout.CARD_GAP
        val right = GtvLayout.CONTENT_KEYLINE + w + GtvLayout.appFocusOverflow(GtvLayout.cardWidth(AppsPageLayout.CARD_SIZE))
        assertTrue("right edge $right", right <= 960f - 20f)
    }

    /** 屏高 540:从上往下走,焦点行出下边才翻;走回第一行回到顶(连标题一起露出)。 */
    @Test fun scrollKeepsTheFocusedRowInViewAndShowsTitles() {
        val lines = appsPageLines(apps = 30, tools = 6, columns = 6)   // 标题 + 5 行应用 + 分组标题 + 1 行工具
        val h = 540f
        assertEquals(0f, appsPageScroll(lines, 1, 0f, h), 0f)
        assertEquals(0f, appsPageScroll(lines, 2, 0f, h), 0f)
        val s3 = appsPageScroll(lines, 4, 0f, h)
        assertTrue("row 4 must scroll", s3 > 0f)
        val tops = appsLineTops(lines)
        // 焦点行完整在屏内(含边距)
        assertTrue(tops[4] - s3 >= 0f && tops[4] + AppsPageLayout.cardRowHeight() - s3 <= h)
        // 往回走一行不动(还在屏内)
        assertEquals(s3, appsPageScroll(lines, 3, s3, h), 0.001f)
        // 回到第一行:回到顶
        assertEquals(0f, appsPageScroll(lines, 1, s3, h), 0f)
        // 系统工具第一行:分组标题一起露出(分组标题顶在屏内)
        val sTools = appsPageScroll(lines, 7, 0f, h)
        assertTrue(tops[6] - sTools >= 0f)
        assertTrue(tops[7] + AppsPageLayout.cardRowHeight() - sTools <= h)
    }

    @Test fun scrollNeverPassesTheEnd() {
        val lines = appsPageLines(apps = 60, tools = 0, columns = 6)
        val h = 540f
        val last = appsPageScroll(lines, lines.lastIndex, 0f, h)
        val tops = appsLineTops(lines)
        val total = tops.last() + AppsPageLayout.cardRowHeight() + AppsPageLayout.EDGE_MARGIN
        assertEquals(total - h, last, 0.001f)
        // 放得下一屏时永远不动
        val few = appsPageLines(apps = 6, tools = 0, columns = 6)
        assertEquals(0f, appsPageScroll(few, 1, 0f, h), 0f)
    }

    @Test fun addToRowAppendsOnceAndChecksTheRowName() {
        val rows = listOf(LayoutRow("VIDEO", apps = listOf("a", "b")), LayoutRow("MUSIC", icon = "music"))
        assertEquals(
            listOf(LayoutRow("VIDEO", apps = listOf("a", "b")), LayoutRow("MUSIC", icon = "music", apps = listOf("c"))),
            addToRow(rows, 1, "MUSIC", "c"),
        )
        assertEquals(listOf("a", "b", "c"), addToRow(rows, 0, "VIDEO", "c")[0].apps)
        // 已在这一行 / 行名对不上(菜单打开后别处改过)/ 越界 → 同一个 list,不写盘
        assertSame(rows, addToRow(rows, 0, "VIDEO", "a"))
        assertSame(rows, addToRow(rows, 0, "MUSIC", "c"))
        assertSame(rows, addToRow(rows, 5, "VIDEO", "c"))
        // 在别的行里有它不影响:一个包可以在两行各有一张
        assertEquals(listOf("a"), addToRow(rows, 1, "MUSIC", "a")[1].apps)
    }
}
