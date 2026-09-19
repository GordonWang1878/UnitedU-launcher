package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class InputPrefsTest {
    private fun e(id: String, label: String = id, parent: String? = null) =
        InputEntry(id = id, label = label, isPassthrough = true, parentId = parent)

    @Test fun aPortWithACecDeviceShowsOnlyTheDevice() {
        val hdmi1 = e("HW2", "HDMI 1")
        val ps5 = e("HDMI4008", "PlayStation 5", parent = "HW2")
        val hdmi2 = e("HW3", "HDMI 2")
        assertEquals(listOf(ps5, hdmi2), dedupeCec(listOf(hdmi1, ps5, hdmi2)))
    }

    @Test fun twoDevicesOnOnePortBothStay() {
        val port = e("HW4")
        val avr = e("HDMI1", "AV Receiver", parent = "HW4")
        val player = e("HDMI2", "Player", parent = "HW4")
        assertEquals(listOf(avr, player), dedupeCec(listOf(port, avr, player)))
    }

    @Test fun withoutCecNothingChanges() {
        // A95L 2026-09-19 实测形态:只有端口,没有子输入
        val list = listOf(e("HW0", "TV"), e("HW1", "AV"), e("HW2", "HDMI 1"), e("HW3", "HDMI 2"))
        assertEquals(list, dedupeCec(list))
    }

    @Test fun hiddenGoAndNamesReplaceLabels() {
        val list = listOf(e("a", "HDMI 1"), e("b", "HDMI 2"), e("c", "HDMI 3"))
        val out = applyInputPrefs(list, hidden = setOf("b"), names = mapOf("c" to "游戏机", "zzz" to "无关"))
        assertEquals(listOf("a" to "HDMI 1", "c" to "游戏机"), out.map { it.id to it.label })
    }

    @Test fun hiddenListRoundTripsThroughTheTitlesFormat() {
        val ids = setOf("com.mediatek.external/.HdmiInputService/HW3", "HW0")
        assertEquals(ids, parseHiddenInputs(hiddenInputsToJson(ids)))
        assertEquals(emptySet<String>(), parseHiddenInputs("{}"))
        assertEquals(emptySet<String>(), parseHiddenInputs("not json"))
    }
}
