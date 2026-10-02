package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 测试轮 B-06(2026-09-30):编辑页卡片菜单按包名认卡。菜单开着时这张卡的应用被卸载,
 * 同一行后面的卡左移一格——按坐标认会把菜单换成下一张卡,「移出」就移错了应用。
 */
class EditActingTest {
    private fun row(vararg apps: String) = LayoutRow("games", apps = apps.toList())

    @Test fun columnFollowsThePackageWhenCardsBeforeItDisappear() {
        val a = EditActing(row = 0, col = 2, pkg = "c")
        assertEquals(2, editActingCol(a, listOf(row("a", "b", "c", "d"))))
        // 前面的 a 被卸载:c 左移到第 1 格,菜单跟着它走
        assertEquals(1, editActingCol(a, listOf(row("b", "c", "d"))))
    }

    @Test fun theCardItselfGoneClosesTheMenuInsteadOfPickingTheNextCard() {
        val a = EditActing(row = 0, col = 0, pkg = "a")
        // 旧写法 viewRows[0][0] 会得到 "b",菜单悄悄换成 b
        assertNull(editActingCol(a, listOf(row("b", "c"))))
    }

    @Test fun rowGoneOrOtherRowDoesNotMatch() {
        val a = EditActing(row = 1, col = 0, pkg = "x")
        assertNull(editActingCol(a, listOf(row("x"))))                  // 那一行没了
        assertNull(editActingCol(a, listOf(row("x"), row("y"))))        // x 在别的行,不算
        assertEquals(0, editActingCol(a, listOf(row("y"), row("x"))))
    }
}
