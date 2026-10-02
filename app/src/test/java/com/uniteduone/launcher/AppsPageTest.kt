package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // R121:小档改 86 之后这一页改借中档,卡宽仍是 122(版式逐像素不变)
        assertEquals(122f, GtvLayout.cardWidth(AppsPageLayout.CARD_SIZE), 0f)
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

    @Test fun addToRowAppendsOnce() {
        // R163:行没有名字,不再核对行名——只认下标;这一行里已经有它 / 越界 → 同一个 list,不写盘
        val rows = listOf(LayoutRow("movie", apps = listOf("a", "b")), LayoutRow("music"))
        assertEquals(
            listOf(LayoutRow("movie", apps = listOf("a", "b")), LayoutRow("music", apps = listOf("c"))),
            addToRow(rows, 1, "c"),
        )
        assertEquals(listOf("a", "b", "c"), addToRow(rows, 0, "c")[0].apps)
        assertSame(rows, addToRow(rows, 0, "a"))
        assertSame(rows, addToRow(rows, 5, "c"))
        assertSame(rows, addToRow(rows, -1, "c"))
        // 在别的行里有它不影响:一个包可以在两行各有一张
        assertEquals(listOf("a"), addToRow(rows, 1, "a")[1].apps)
    }

    // ---- R163 「加到桌面…」第二层药丸的标签(只列前两个) ----

    private val sep = "、"
    private fun summary(names: List<String>) =
        rowNamesSummary(names, sep, empty = "空") { joined, total -> "$joined 等 $total 个" }

    @Test fun rowSummaryEmptyRowSaysEmpty() {
        assertEquals("空", summary(emptyList()))
    }

    @Test fun rowSummaryListsUpToTwoNamesJoinedBySeparator() {
        assertEquals("YouTube", summary(listOf("YouTube")))
        assertEquals("YouTube、Play Store", summary(listOf("YouTube", "Play Store")))
    }

    @Test fun rowSummaryMoreThanTwoKeepsTheFirstTwoAndCountsAll() {
        assertEquals("A、B 等 3 个", summary(listOf("A", "B", "C")))
        assertEquals("A、B 等 7 个", summary(listOf("A", "B", "C", "D", "E", "F", "G")))
        assertEquals(2, ROW_SUMMARY_MAX_NAMES)
    }

    @Test fun rowSummaryTakesTheSeparatorFromTheCaller() {
        // 英文用「, 」:分隔符与「等 N 个」的措辞都由界面按语言给
        assertEquals("A, B", rowNamesSummary(listOf("A", "B"), ", ", "Empty") { j, n -> "$j ($n apps)" })
        assertEquals("A, B (5 apps)", rowNamesSummary(listOf("A", "B", "C", "D", "E"), ", ", "Empty") { j, n -> "$j ($n apps)" })
    }

    // ---- R105 缓存刷新合并 + 菜单 ----

    private fun data(apps: List<String>, tools: List<String> = emptyList()) = AppsPageData(
        apps.map { cand(it, it) } + tools.map { cand(it, it, PickerGroup.SYSTEM_TOOLS) },
        apps.size,
    )

    @Test fun sameContentIsNotReplaced() {
        val d = data(listOf("a", "b", "c"), listOf("t"))
        // 内容相同(另一份相等的对象也一样)→ 不替换,不重组、焦点不动
        assertNull(appsPageSwap(d, data(listOf("a", "b", "c"), listOf("t")), target = 2))
        assertNull(appsPageSwap(d, null, target = 2))
    }

    @Test fun renameOnlyReplacesWithoutMovingFocus() {
        val shown = data(listOf("a", "b", "c"))
        val renamed = AppsPageData(shown.items.map { if (it.app.packageName == "b") it.copy(app = it.app.copy(label = "B2")) else it }, 3)
        val swap = appsPageSwap(shown, renamed, target = 1)!!
        assertEquals(1, swap.target)
        assertFalse(swap.reposition)
        assertSame(renamed, swap.data)
    }

    @Test fun firstDataArrivalRepositionsAndClampsTarget() {
        val swap = appsPageSwap(null, data(listOf("a", "b")), target = 5)!!
        assertEquals(1, swap.target)
        assertTrue(swap.reposition)
    }

    @Test fun focusFollowsThePackageWhenOthersAreAddedOrRemoved() {
        // 焦点在 c(下标 2);前面装了一个 aa、b 被卸了 → c 仍是目标,下标按新表算
        val swap = appsPageSwap(data(listOf("a", "b", "c", "d")), data(listOf("a", "aa", "c", "d")), target = 2)!!
        assertEquals(2, swap.target)
        assertTrue(swap.reposition)
        assertEquals(1, appsPageSwap(data(listOf("a", "b", "c", "d")), data(listOf("a", "c", "d")), target = 2)!!.target)
        assertEquals(4, appsPageSwap(data(listOf("a", "b", "c")), data(listOf("0", "1", "a", "b", "c")), target = 2)!!.target)
    }

    @Test fun uninstalledTargetLandsOnTheNextCardInItsSlot() {
        // 卸掉 b(下标 1)→ 补进那一格的 c(新下标 1)
        assertEquals(1, appsPageRetarget(data(listOf("a", "b", "c", "d")), data(listOf("a", "c", "d")), target = 1))
        // 下一张也同时没了 → 再往后找
        assertEquals(1, appsPageRetarget(data(listOf("a", "b", "c", "d")), data(listOf("a", "d")), target = 1))
    }

    @Test fun uninstalledLastAppFallsBackToPreviousInSameGroupNotToSystemTools() {
        // 「应用」组最后一张 c 被卸:平铺表下一张是系统工具 t,但网格上那一格空了 → 落上一张 b
        val old = data(listOf("a", "b", "c"), listOf("t", "u"))
        val new = data(listOf("a", "b"), listOf("t", "u"))
        assertEquals(1, appsPageRetarget(old, new, target = 2))
        // 系统工具组里同理:卸掉 u(组内最后)→ t
        assertEquals(3, appsPageRetarget(old, data(listOf("a", "b", "c"), listOf("t")), target = 4))
    }

    @Test fun uninstalledOnlyAppInGroupFallsBackToPageStart() {
        assertEquals(0, appsPageRetarget(data(listOf("a"), listOf("t")), data(emptyList(), listOf("t")), target = 0))
        assertEquals(0, appsPageRetarget(data(listOf("a")), data(emptyList()), target = 0))
        // 目标下标本身越界(旧表比目标短)→ 夹到新表里
        assertEquals(1, appsPageRetarget(data(listOf("a")), data(listOf("a", "b")), target = 7))
    }

    @Test fun menuListsUninstallUnderOpenOnlyWhenUninstallable() {
        assertEquals(
            listOf(AppsMenuAction.OPEN, AppsMenuAction.UNINSTALL, AppsMenuAction.ADD_TO_HOME),
            appsMenuActions(canUninstall = true),
        )
        assertEquals(listOf(AppsMenuAction.OPEN, AppsMenuAction.ADD_TO_HOME), appsMenuActions(canUninstall = false))
    }

    @Test fun systemAppsAreNotUninstallableUnlessUpdated() {
        assertTrue(canUninstall(isSystem = false, isUpdatedSystem = false))
        assertTrue(canUninstall(isSystem = true, isUpdatedSystem = true))   // 系统卸载页给「卸载更新」
        assertFalse(canUninstall(isSystem = true, isUpdatedSystem = false))
    }
}
