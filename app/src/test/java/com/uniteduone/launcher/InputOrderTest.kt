package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** R97:输入源按电视输入菜单的顺序排——A95L 上系统给的是哈希序 HDMI 3、4、1、2、电视。 */
class InputOrderTest {
    private fun hdmi(hw: Int, label: String, parent: String? = null) =
        InputEntry("com.mediatek.external/.HdmiInputService/HW$hw", label, isPassthrough = true, parentId = parent)
    private val tuner = InputEntry("com.sony.dtv.tvinput.dvbtuner/.DvbTvInputService/HW0", "电视", isPassthrough = false)

    @Test fun a95lHashOrderBecomesTunerThenHdmi1to4() {
        val sys = listOf(hdmi(4, "HDMI 3"), hdmi(5, "HDMI 4"), hdmi(2, "HDMI 1"), hdmi(3, "HDMI 2"), tuner)
        assertEquals(listOf("电视", "HDMI 1", "HDMI 2", "HDMI 3", "HDMI 4"), orderInputs(sys).map { it.label })
    }

    @Test fun cecChildTakesParentPortAndSurvivesDedupe() {
        val parent = hdmi(3, "HDMI 2")
        val ps5 = InputEntry("cec/ps5", "PlayStation 5", isPassthrough = true, parentId = parent.id)
        val sys = listOf(hdmi(4, "HDMI 3"), ps5, parent, hdmi(2, "HDMI 1"), tuner)
        val shown = dedupeCec(orderInputs(sys)).map { it.label }
        assertEquals(listOf("电视", "HDMI 1", "PlayStation 5", "HDMI 3"), shown)
    }

    @Test fun unknownPortGoesLastStably() {
        val odd = InputEntry("x/composite", "Composite", isPassthrough = true)
        val sys = listOf(odd, hdmi(2, "HDMI 1"), tuner)
        assertEquals(listOf("电视", "HDMI 1", "Composite"), orderInputs(sys).map { it.label })
    }

    @Test fun pageDataUsesOrder() {
        val sys = listOf(hdmi(4, "HDMI 3"), hdmi(2, "HDMI 1"), tuner)
        assertEquals(listOf("电视", "HDMI 1", "HDMI 3"), inputsPageData(sys, emptySet(), emptyMap()).visible.map { it.label })
    }
}
