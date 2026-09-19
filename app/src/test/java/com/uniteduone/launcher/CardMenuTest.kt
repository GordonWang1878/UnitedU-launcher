package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardMenuTest {
    @Test fun appCardsGetSixActionsInDesignOrder() {
        assertEquals(
            listOf(CardAction.OPEN, CardAction.UNINSTALL, CardAction.RENAME, CardAction.CHANGE_ICON, CardAction.MOVE, CardAction.REMOVE),
            cardMenuActions(RowKind.APPS),
        )
    }

    @Test fun inputCardsGetThreeActions() {
        assertEquals(
            listOf(CardAction.OPEN, CardAction.RENAME, CardAction.HIDE),
            cardMenuActions(RowKind.INPUTS),
        )
    }

    @Test fun newAppPredicate() {
        assertTrue(isNewApp(firstInstallTime = 200L, seenAt = 100L, onLayout = false))
        assertFalse(isNewApp(firstInstallTime = 100L, seenAt = 100L, onLayout = false))   // 同一刻不算新
        assertFalse(isNewApp(firstInstallTime = 50L, seenAt = 100L, onLayout = false))
        assertFalse(isNewApp(firstInstallTime = 200L, seenAt = 100L, onLayout = true))    // 已在桌面上
    }
}
