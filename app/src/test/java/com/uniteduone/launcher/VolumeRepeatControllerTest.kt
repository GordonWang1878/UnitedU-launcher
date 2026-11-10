package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * 音量连发(issue #1)。时钟和调度器都是手动的,不睡真实时间。
 * 控制器自己的步数只来自心跳。按下那一格由系统走,短按时这里是 0 步。
 * [onTimerTick] / [ManualVolumeScheduler.advanceTo] 到点只停,不加步。
 */
class VolumeRepeatControllerTest {
    private class Rig {
        val clock = ManualVolumeClock()
        val scheduler = ManualVolumeScheduler(clock)
        val stepper = RecordingStepper()
        val records = mutableListOf<VolumeKeyRecord>()
        val controller = VolumeRepeatController(clock, scheduler, stepper) { records += it }
    }

    private class RecordingStepper : VolumeStepper {
        var upLimit = Int.MAX_VALUE
        var downLimit = Int.MAX_VALUE
        var up = 0
        var down = 0
        val calls = mutableListOf<VolumeDirection>()
        override fun step(direction: VolumeDirection): VolumeStepResult {
            calls += direction
            if (direction == VolumeDirection.UP) {
                if (up >= upLimit) return VolumeStepResult.AT_BOUND
                up += 1
            } else {
                if (down >= downLimit) return VolumeStepResult.AT_BOUND
                down += 1
            }
            return VolumeStepResult.MOVED
        }
    }

    /** 短按:控制器 0 步(系统走那一格)。松开之后时间再走也不由我们连发。 */
    @Test fun shortPressStepsOnce() {
        val rig = Rig()
        val wall = System.currentTimeMillis()
        assertTrue(rig.controller.onKeyDown(VOLUME_KEYCODE_UP))
        assertTrue(rig.controller.openedNewPress)
        assertFalse(consumeVolumeDown(rig.controller.openedNewPress))
        rig.scheduler.advanceTo(50)
        assertTrue(rig.controller.onKeyUp(VOLUME_KEYCODE_UP))
        rig.scheduler.advanceTo(10_000)
        assertEquals(0, rig.stepper.up)
        assertEquals(0, rig.stepper.down)
        assertFalse(rig.controller.lastAutoStop)
        assertNull(rig.controller.activeKeyCode)
        assertEquals(listOf(VOLUME_LOG_DOWN, VOLUME_LOG_UP), rig.records.map { it.action })
        assertEquals(0, rig.records.last().steps)
        assertFalse(rig.records.last().autoStop)
        assertTrue(rig.records.all { kotlin.math.abs(it.wallTimeMs - wall) < 5_000 })
        assertTrue(rig.records.first().formatLine().contains("T"))
    }

    /**
     * 有连发也有松开。400 ms 之前的心跳不加步;之后每满 100 ms 一步。
     * 50 ms 一跳的心跳在 400/500/600 各加一步,中间那次丢掉,不会一步走两次。
     */
    @Test fun holdWithRepeatsAndUp() {
        val rig = Rig()
        rig.controller.onKeyDown(VOLUME_KEYCODE_UP, downTimeMs = 10)
        repeatEvery(rig, from = 50, to = 600, every = 50)
        assertEquals(3, rig.stepper.up)
        rig.controller.onKeyUp(VOLUME_KEYCODE_UP)
        rig.controller.onHeartbeat(VOLUME_KEYCODE_UP)
        rig.scheduler.advanceTo(10_000)
        assertEquals(3, rig.stepper.up)
        assertFalse(rig.controller.lastAutoStop)
        assertEquals(VOLUME_LOG_UP, rig.records.last().action)
        assertEquals(3, rig.records.last().steps)
    }

    /** 有连发、没有松开:最后一次心跳之后一个心跳超时内停下,计时器不再加步。 */
    @Test fun repeatsWithoutUpStopWithinHeartbeatTimeout() {
        val rig = Rig()
        rig.controller.onKeyDown(VOLUME_KEYCODE_DOWN, downTimeMs = 7)
        repeatEvery(rig, from = 50, to = 600, every = 50)
        val atLastBeat = rig.stepper.down
        assertEquals(3, atLastBeat)
        rig.scheduler.advanceTo(600 + VOLUME_REPEAT_HEARTBEAT_TIMEOUT_MS)
        assertEquals(atLastBeat, rig.stepper.down)
        assertTrue(rig.controller.lastAutoStop)
        assertNull(rig.controller.activeKeyCode)
        assertTrue(rig.records.last().autoStop)
        rig.scheduler.advanceTo(10_000)
        rig.controller.onHeartbeat(VOLUME_KEYCODE_DOWN)
        rig.controller.onTimerTick()
        assertEquals("停下之后不再走", atLastBeat, rig.stepper.down)
    }

    /** 既没有连发也没有松开:控制器 0 步,静默窗口到了就停,之后怎么拨时钟都不连发。 */
    @Test fun neitherRepeatsNorUpIsOneStep() {
        val rig = Rig()
        rig.controller.onKeyDown(VOLUME_KEYCODE_UP)
        rig.scheduler.advanceTo(VOLUME_REPEAT_HEARTBEAT_TIMEOUT_MS)
        assertEquals("心跳超时还没到首次静默窗口,不能提前连发", 0, rig.stepper.up)
        assertEquals(VOLUME_KEYCODE_UP, rig.controller.activeKeyCode)
        rig.scheduler.advanceTo(volumeSilenceWindowMs(seenHeartbeat = false))
        assertEquals(0, rig.stepper.up)
        assertTrue(rig.controller.lastAutoStop)
        assertNull(rig.controller.activeKeyCode)
        rig.scheduler.advanceTo(VOLUME_REPEAT_HARD_CAP_MS + 5_000)
        assertEquals(0, rig.stepper.up)
        assertEquals(VOLUME_LOG_AUTOSTOP, rig.records.last().action)
    }

    /** 不经过调度器,直接拨时钟再 [VolumeRepeatController.onTimerTick],同样不加步。 */
    @Test fun timerTickAloneNeverSteps() {
        val rig = Rig()
        rig.controller.onKeyDown(VOLUME_KEYCODE_UP)
        rig.clock.now = 400
        rig.controller.onTimerTick()
        assertEquals(0, rig.stepper.up)
        assertEquals(VOLUME_KEYCODE_UP, rig.controller.activeKeyCode)
        rig.clock.now = volumeSilenceWindowMs(seenHeartbeat = false)
        rig.controller.onTimerTick()
        assertEquals(0, rig.stepper.up)
        assertNull(rig.controller.activeKeyCode)
        rig.clock.now = 9_000
        rig.controller.onTimerTick()
        assertEquals(0, rig.stepper.up)
    }

    /** 心跳对得上这次按下时,过了 3 秒仍继续走,直到松开。 */
    @Test fun matchedHeartbeatsContinuePastThreeSeconds() {
        val rig = Rig()
        rig.controller.onKeyDown(VOLUME_KEYCODE_UP, downTimeMs = 1)
        var t = 100L
        var expected = 0
        while (t <= VOLUME_REPEAT_HARD_CAP_MS + 1_000) {
            rig.scheduler.advanceTo(t)
            rig.controller.onHeartbeat(VOLUME_KEYCODE_UP, downTimeMs = 1)
            if (t >= VOLUME_REPEAT_INITIAL_DELAY_MS) expected += 1
            assertEquals("t=$t", expected, rig.stepper.up)
            t += VOLUME_REPEAT_INTERVAL_MS
        }
        assertEquals(VOLUME_KEYCODE_UP, rig.controller.activeKeyCode)
        assertFalse(rig.controller.lastAutoStop)
        rig.controller.onKeyUp(VOLUME_KEYCODE_UP)
        val stopped = rig.stepper.up
        rig.scheduler.advanceTo(t + 1_000)
        assertEquals(stopped, rig.stepper.up)
    }

    /** 没有对得上的按下时,心跳不走步;过了 3 秒的异常窗口也不把它们变成一次新的按下。 */
    @Test fun unmatchedHeartbeatsNeverStep() {
        val rig = Rig()
        assertFalse(rig.controller.onHeartbeat(VOLUME_KEYCODE_UP, downTimeMs = 99))
        rig.scheduler.advanceTo(VOLUME_REPEAT_HARD_CAP_MS + 500)
        assertFalse(rig.controller.onHeartbeat(VOLUME_KEYCODE_UP, downTimeMs = 99))
        assertEquals(0, rig.stepper.up)
        assertNull(rig.controller.activeKeyCode)
        assertTrue(rig.controller.onKeyDown(VOLUME_KEYCODE_UP, downTimeMs = 100))
        assertTrue(rig.controller.openedNewPress)
        assertEquals(0, rig.stepper.up)
    }

    @Test fun clampStopsFiringAtMaxAndMin() {
        val rig = Rig()
        rig.stepper.upLimit = 2
        rig.controller.onKeyDown(VOLUME_KEYCODE_UP, downTimeMs = 1)
        repeatEvery(rig, from = 400, to = 800, every = 100)
        assertEquals(2, rig.stepper.up)
        assertEquals(3, rig.stepper.calls.size)
        rig.controller.onKeyUp(VOLUME_KEYCODE_UP)

        rig.stepper.downLimit = 1
        val downAt = rig.clock.now
        rig.controller.onKeyDown(VOLUME_KEYCODE_DOWN, downTimeMs = 2)
        repeatEvery(rig, from = downAt + 400, to = downAt + 700, every = 100)
        assertEquals(1, rig.stepper.down)
        assertEquals(2, rig.stepper.calls.count { it == VolumeDirection.DOWN })
    }

    /** 同一个 downTime 的重复 DOWN 不再开一次。结束后迟到的同一 downTime(按下或心跳)也不开、不走步。 */
    @Test fun duplicateDownOfSamePressIsIgnored() {
        val rig = Rig()
        rig.controller.onKeyDown(VOLUME_KEYCODE_UP, downTimeMs = 10)
        assertTrue(rig.controller.openedNewPress)
        rig.controller.onKeyDown(VOLUME_KEYCODE_UP, downTimeMs = 10)
        assertFalse(rig.controller.openedNewPress)
        assertTrue(consumeVolumeDown(rig.controller.openedNewPress))
        assertEquals(0, rig.stepper.up)
        rig.controller.onKeyDown(VOLUME_KEYCODE_UP, downTimeMs = 11)
        assertTrue(rig.controller.openedNewPress)
        assertEquals(0, rig.stepper.up)
        rig.scheduler.advanceTo(volumeSilenceWindowMs(seenHeartbeat = false))
        assertTrue(rig.controller.lastAutoStop)
        rig.controller.onKeyDown(VOLUME_KEYCODE_UP, downTimeMs = 11)
        rig.controller.onHeartbeat(VOLUME_KEYCODE_UP, downTimeMs = 11)
        assertFalse(rig.controller.openedNewPress)
        assertNull(rig.controller.activeKeyCode)
        assertEquals(0, rig.stepper.up)
    }

    @Test fun oppositeKeyCancelsAndStartsTheOtherWay() {
        val rig = Rig()
        rig.controller.onKeyDown(VOLUME_KEYCODE_UP, downTimeMs = 1)
        repeatEvery(rig, from = 400, to = 400, every = 100)
        assertEquals(1, rig.stepper.up)
        rig.controller.onKeyDown(VOLUME_KEYCODE_DOWN, downTimeMs = 2)
        assertTrue(rig.controller.openedNewPress)
        rig.controller.onHeartbeat(VOLUME_KEYCODE_UP, downTimeMs = 1)
        assertEquals(1, rig.stepper.up)
        assertEquals(0, rig.stepper.down)
        assertEquals(VOLUME_KEYCODE_DOWN, rig.controller.activeKeyCode)
    }

    @Test fun muteAndOtherKeysAreUntouched() {
        val rig = Rig()
        assertFalse(rig.controller.onKeyDown(VOLUME_KEYCODE_MUTE))
        assertFalse(rig.controller.onHeartbeat(VOLUME_KEYCODE_MUTE))
        assertFalse(rig.controller.onKeyUp(VOLUME_KEYCODE_MUTE))
        assertFalse(rig.controller.onKeyDown(KeyEventHome))
        assertFalse(rig.controller.onHeartbeat(KeyEventHome))
        assertTrue(rig.stepper.calls.isEmpty())
        rig.controller.onKeyDown(VOLUME_KEYCODE_UP)
        assertFalse(rig.controller.onKeyUp(VOLUME_KEYCODE_MUTE))
        assertEquals(VOLUME_KEYCODE_UP, rig.controller.activeKeyCode)
        assertEquals(0, rig.stepper.up)
    }

    @Test fun stopOnServiceEndAutoStops() {
        val rig = Rig()
        rig.controller.onKeyDown(VOLUME_KEYCODE_UP)
        rig.controller.stop()
        rig.scheduler.advanceTo(5_000)
        assertEquals(0, rig.stepper.up)
        assertTrue(rig.controller.lastAutoStop)
        assertEquals(VOLUME_LOG_AUTOSTOP, rig.records.last().action)
    }

    @Test fun logRingDropsOldestAndRoundTrips() {
        val file = File.createTempFile("volume-key", ".log")
        file.deleteOnExit()
        val store = VolumeKeyLogStore(file, capacity = 3)
        repeat(5) { i ->
            assertTrue(store.append(VolumeKeyRecord(i.toLong(), VOLUME_KEYCODE_UP, VOLUME_LOG_DOWN, i, false)))
        }
        val read = store.read()
        assertEquals(listOf(2L, 3L, 4L), read.map { it.timeMs })
        assertEquals(read, parseVolumeKeyLog(store.exportText()))
        assertEquals(read, parseVolumeKeyLog(formatVolumeKeyLog(read) + "\ngarbage\n# comment\n"))
        assertTrue(volumeLogTail(store.exportText(), maxLines = 2).lines().size == 2)
        val zone = ZoneId.of("Asia/Shanghai")
        val stamped = VolumeKeyRecord(9, VOLUME_KEYCODE_DOWN, VOLUME_LOG_UP, 2, true, 1_700_000_000_123L)
        val parsed = parseVolumeKeyLog(stamped.formatLine(zone)).single()
        assertEquals(stamped.timeMs, parsed.timeMs)
        assertEquals(stamped.wallTimeMs, parsed.wallTimeMs)
        assertEquals(stamped.steps, parsed.steps)
        assertTrue(stamped.formatLine(zone).contains(formatVolumeWallTime(stamped.wallTimeMs, zone)))
        assertEquals(0L, parseVolumeKeyLog("9 25 up 2 true").single().wallTimeMs)
    }

    private fun repeatEvery(rig: Rig, from: Long, to: Long, every: Long) {
        var t = from
        while (t <= to) {
            rig.scheduler.advanceTo(t)
            rig.controller.onHeartbeat(rig.controller.activeKeyCode ?: error("hold ended at $t"))
            t += every
        }
    }

    private companion object {
        /** `KeyEvent.KEYCODE_HOME`,不引用 Android。 */
        const val KeyEventHome = 3
    }
}
