package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ruling R67:编辑页只画已装、可启动的包;可见列号 ↔ layout.json 下标的合回。 */
class EditVisibleTest {
    // R163:行没有名字,用图标 id 当行的记号(A / B / C = 三个不同的图标)
    private val A = "movie"; private val B = "tv"; private val C = "music"
    private fun row(icon: String, vararg apps: String) = LayoutRow(icon, apps = apps.toList())
    private val hidden = setOf("h1", "h2")
    private val shown: (String) -> Boolean = { it !in hidden }

    @Test fun cardShownOnlyHidesPackagesCheckedAndNotFound() {
        assertTrue(editCardShown("a", checked = null, found = emptySet()))           // 数据还没到:占位
        assertTrue(editCardShown("new", checked = setOf("a"), found = setOf("a")))    // 刚加的,还没查过:占位
        assertTrue(editCardShown("a", checked = setOf("a"), found = setOf("a")))      // 查到:真卡
        assertFalse(editCardShown("gone", checked = setOf("a", "gone"), found = setOf("a")))   // 查过没查到:不画
    }

    @Test fun visibleRowsKeepsEveryRowAndFiltersApps() {
        val full = listOf(row(A, "a", "h1", "b"), row(B), row(C, "h2"))
        val v = visibleRows(full, shown)
        assertEquals(listOf(A, B, C), v.map { it.icon })
        assertEquals(listOf(listOf("a", "b"), emptyList(), emptyList()), v.map { it.apps })
        assertSame("没有要藏的行原样复用", full[1], v[1])
    }

    @Test fun mergeWithNothingHiddenIsTheEditedList() {
        val full = listOf(row(A, "a", "b"))
        val edited = listOf(row(A, "b", "a"))
        assertEquals(edited, withVisibleEdits(full, edited, shown))
    }

    @Test fun unchangedViewReturnsSameFullInstance() {
        val full = listOf(row(A, "a", "h1", "b"), row(B, "h2"))
        assertSame(full, withVisibleEdits(full, visibleRows(full, shown), shown))
    }

    @Test fun hiddenPackageStaysAtItsIndexWhenVisibleNeighboursSwap() {
        val full = listOf(row(A, "a", "h1", "b", "c"))
        // 看得见的 [a, b, c] → 第 1、2 格换位 [a, c, b]
        val merged = withVisibleEdits(full, listOf(row(A, "a", "c", "b")), shown)
        assertEquals(listOf("a", "h1", "c", "b"), merged[0].apps)
    }

    @Test fun removingByVisibleColumnKeepsHiddenAndDropsTheRightPackage() {
        // 可见列 1 是 b(盘上下标 2);按可见那份移出后合回,h1 还在、b 没了
        val full = listOf(row(A, "a", "h1", "b", "c"))
        val v = visibleRows(full, shown)
        val edited = listOf(v[0].copy(apps = v[0].apps.toMutableList().also { it.removeAt(1) }))
        assertEquals(listOf("a", "h1", "c"), withVisibleEdits(full, edited, shown)[0].apps)
    }

    @Test fun rowShrinkingPastHiddenIndexPacksHiddenToTheEndInOrder() {
        val full = listOf(row(A, "a", "b", "h1", "c", "h2"), row(B, "x"))
        // a、b、c 全部搬走:只剩两个看不见的包,顺序不变
        val edited = listOf(row(A), row(B, "x", "a", "b", "c"))
        val merged = withVisibleEdits(full, edited, shown)
        assertEquals(listOf("h1", "h2"), merged[0].apps)
        assertEquals(listOf("x", "a", "b", "c"), merged[1].apps)
    }

    @Test fun carryRoundTripFromTheOriginalRestoresItExactly() {
        // 搬运以进入时的整份为底合回(EditScreen.stepCarry):搬出去再搬回来,整份原样
        val original = listOf(row(A, "a", "h1"), row(B, "x"))
        val shownRows = visibleRows(original, shown)
        val (out, pos) = moveInLayout(shownRows, MovePos(0, 0), MoveDir.DOWN)
        val away = withVisibleEdits(original, out, shown)
        assertEquals(listOf("h1"), away[0].apps)
        val (back, _) = moveInLayout(visibleRows(away, shown), pos, MoveDir.UP)
        assertEquals(original, withVisibleEdits(original, back, shown))
    }

    @Test fun addedPackageThatIsNotYetCheckedIsKept() {
        // 刚加的包不在整份里,也不在「看不见」里:照 edited 的位置放
        val full = listOf(row(A, "a", "h1"))
        val edited = listOf(row(A, "a", "new"))
        assertEquals(listOf("a", "h1", "new"), withVisibleEdits(full, edited, shown)[0].apps)
    }

    @Test fun rowCountMismatchFallsBackToEdited() {
        val full = listOf(row(A, "a", "h1"), row(B))
        val edited = listOf(row(A, "a"))
        assertEquals(edited, withVisibleEdits(full, edited, shown))
    }
}
