package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class InputPrefsTest {
    private fun e(id: String, label: String = id, parent: String? = null) =
        InputEntry(id = id, label = label, isPassthrough = true, parentId = parent)

    private fun tuner(id: String) = InputEntry(id = id, label = "电视", isPassthrough = false)

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

    @Test fun twoTunersBecomeOneAndTheDigitalOneStays() {
        // A95L 2026-09-20 实测:数字 DVB(HW0)+ 模拟(HW1)两个调谐器,系统都叫「电视」
        val dvb = tuner("com.sony.dtv.tvinput.dvbtuner/.DvbTvInputService/HW0")
        val atv = tuner("com.mediatek.tis/.AnalogInputService/HW1")
        val hdmi = e("HW2", "HDMI 1")
        assertEquals(listOf(hdmi, dvb), mergeTuners(listOf(hdmi, atv, dvb)))
    }

    @Test fun withoutAnAnalogHintTheSmallestIdStays() {
        val a = tuner("a/T1")
        val b = tuner("b/T2")
        assertEquals(listOf(a), mergeTuners(listOf(b, a)))
    }

    @Test fun oneOrNoTunerLeavesTheListAlone() {
        val one = listOf(e("HW2", "HDMI 1"), tuner("x/HW0"))
        assertSame(one, mergeTuners(one))
        val none = listOf(e("HW2", "HDMI 1"), e("HW3", "HDMI 2"))
        assertSame(none, mergeTuners(none))
    }
}
