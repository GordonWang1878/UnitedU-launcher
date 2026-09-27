package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R91 输入源页的纯逻辑:列表构建(去重 / 合并调谐器 / 隐藏 / 改名)、胶囊 id 清单、进页落点。 */
class InputsPageTest {
    private fun hdmi(n: Int, parent: String? = null) = InputEntry("hw/HDMI$n", "HDMI $n", isPassthrough = true, parentId = parent)
    private val dtv = InputEntry("sony/dtv", "TV", isPassthrough = false)
    private val atv = InputEntry("mtk/analog", "TV", isPassthrough = false)
    private val ps5 = InputEntry("cec/ps5", "PlayStation 5", isPassthrough = true, parentId = "hw/HDMI2")

    @Test fun listDedupesCecMergesTunersHidesAndRenames() {
        val all = listOf(atv, dtv, hdmi(1), hdmi(2), ps5, hdmi(3))
        val d = inputsPageData(all, hidden = setOf("hw/HDMI3"), names = mapOf("hw/HDMI1" to "机顶盒"))
        // HDMI2 被它的 CEC 子设备顶替;两个调谐器留数字那个;HDMI3 隐藏;HDMI1 换上改过的名字
        assertEquals(listOf("sony/dtv", "hw/HDMI1", "cec/ps5"), d.visible.map { it.id })
        assertEquals("机顶盒", d.visible[1].label)
        assertEquals(1, d.hiddenCount)
    }

    /** hidden-inputs.json 里残留的旧 id(这台电视现在列不出来)不算进「恢复隐藏的输入源 N 个」。 */
    @Test fun hiddenCountIgnoresStaleIds() {
        val d = inputsPageData(listOf(hdmi(1)), hidden = setOf("gone/HDMI9", "cec/old"), names = emptyMap())
        assertEquals(0, d.hiddenCount)
        assertEquals(listOf("hw/HDMI1"), inputsCapsuleIds(d))
    }

    @Test fun restoreCapsuleOnlyWhenSomethingIsHidden() {
        val none = inputsPageData(listOf(hdmi(1), hdmi(2)), emptySet(), emptyMap())
        assertEquals(listOf("hw/HDMI1", "hw/HDMI2"), inputsCapsuleIds(none))
        val one = inputsPageData(listOf(hdmi(1), hdmi(2)), setOf("hw/HDMI2"), emptyMap())
        assertEquals(listOf("hw/HDMI1", INPUTS_RESTORE_ID), inputsCapsuleIds(one))
        // 全部隐藏:只剩「恢复」那一颗,焦点仍有地方落
        val all = inputsPageData(listOf(hdmi(1)), setOf("hw/HDMI1"), emptyMap())
        assertEquals(listOf(INPUTS_RESTORE_ID), inputsCapsuleIds(all))
    }

    @Test fun initialFocusPrefersTheCurrentInput() {
        val ids = listOf("hw/HDMI1", "hw/HDMI2", INPUTS_RESTORE_ID)
        assertEquals("hw/HDMI2", inputsInitialFocus(ids, current = "hw/HDMI2"))
        // 不知道当前(null)或当前那个已被隐藏 / 不在了 → 第一颗
        assertEquals("hw/HDMI1", inputsInitialFocus(ids, current = null))
        assertEquals("hw/HDMI1", inputsInitialFocus(ids, current = "hw/HDMI9"))
        assertNull(inputsInitialFocus(emptyList(), current = "hw/HDMI1"))
    }
}
