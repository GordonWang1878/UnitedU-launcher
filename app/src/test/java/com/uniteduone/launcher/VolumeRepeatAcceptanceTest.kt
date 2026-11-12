package com.uniteduone.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import kotlin.random.Random

/**
 * Acceptance tests for issue #1, criteria v2 AC-I2 (hold volume to repeat) + baseline + addendum.
 * Written by 02 Delta, 2026-11-10, against PR #7 commit 7cd9a62.
 * 01 commits this file verbatim: do not change assertions, fixtures, test names, or drop tests.
 *
 * Public API only: VolumeRepeatController, consumeVolumeDown, ManualVolumeClock, ManualVolumeScheduler,
 * VolumeStepper / VolumeDirection / VolumeStepResult, VolumeKeyRecord, VolumeKeyLogStore, log helpers, constants.
 * No Android types. No real waiting.
 *
 * Design under test (7cd9a62): the first DOWN (repeatCount 0) and the UP go to the system, which makes
 * the short-press step itself. The controller steps ONLY on a repeat event (onHeartbeat). Timers only stop.
 * In these tests the fake stepper counts controller steps only; the system's own short-press step is not counted.
 *
 * Clause ids:
 * - AC-I2-1  hold repeats at a fixed interval; release stops (at most one extra step); short press = one step; mute as usual
 * - AC-I2-2  if the TV never reports release, auto stepping stops on its own
 * - AC-I2-3  stop at max / min, no error, not stuck
 * - AC-I2-4  press / release times recorded on the TV, exportable
 * - AC-I2-5  Home key unaffected (here: Home never enters volume repeat)
 * - BASE     baseline + addendum: once the hand is off, the volume never keeps moving; at least as good as today
 */
class VolumeRepeatAcceptanceTest {

    /** Fake volume with rails. Records every controller call: time, direction, result. */
    private class FakeVolume(private val clock: ManualVolumeClock, var level: Int = 50, val min: Int = 0, val max: Int = 1000) :
        VolumeStepper {
        data class Call(val at: Long, val direction: VolumeDirection, val result: VolumeStepResult)

        val calls = mutableListOf<Call>()

        val moveTimes: List<Long> get() = calls.filter { it.result == VolumeStepResult.MOVED }.map { it.at }

        override fun step(direction: VolumeDirection): VolumeStepResult {
            val result = when (direction) {
                VolumeDirection.UP -> if (level >= max) VolumeStepResult.AT_BOUND else { level += 1; VolumeStepResult.MOVED }
                VolumeDirection.DOWN -> if (level <= min) VolumeStepResult.AT_BOUND else { level -= 1; VolumeStepResult.MOVED }
            }
            calls += Call(clock.now, direction, result)
            return result
        }
    }

    private class Rig(level: Int = 50, min: Int = 0, max: Int = 1000) {
        val clock = ManualVolumeClock()
        val scheduler = ManualVolumeScheduler(clock)
        val volume = FakeVolume(clock, level, min, max)
        val records = mutableListOf<VolumeKeyRecord>()
        val c = VolumeRepeatController(clock, scheduler, volume) { records += it }

        /** Move to t (due timers run), then the first DOWN. Returns what the service would return for it. */
        fun down(t: Long, key: Int, downTime: Long): Boolean {
            scheduler.advanceTo(t)
            c.onKeyDown(key, downTime)
            return consumeVolumeDown(c.openedNewPress)
        }

        fun beat(t: Long, key: Int, downTime: Long) { scheduler.advanceTo(t); c.onHeartbeat(key, downTime) }
        fun up(t: Long, key: Int) { scheduler.advanceTo(t); c.onKeyUp(key) }

        /** Repeat events from `from` to `to` inclusive, every `every` ms. */
        fun beats(from: Long, to: Long, every: Long, key: Int, downTime: Long) {
            var t = from
            while (t <= to) { beat(t, key, downTime); t += every }
        }

        /** Walk the clock in 10 ms slices and also call onTimerTick directly, so every timer path runs. */
        fun idleUntil(to: Long) {
            var t = clock.now
            while (t < to) {
                t = minOf(to, t + 10)
                scheduler.advanceTo(t)
                c.onTimerTick()
            }
        }
    }

    private val up = 24 // KeyEvent.KEYCODE_VOLUME_UP
    private val down = 25 // KeyEvent.KEYCODE_VOLUME_DOWN

    private fun everyHundred(from: Long, to: Long): List<Long> = (from..to step 100L).toList()

    // ------------------------------------------------------------ constants

    /** AC-I2-1 / AC-I2-2 / AC-I2-4: agreed values from the PR #7 description. */
    @Test fun constantsMatchAgreedValues() {
        assertEquals(400L, VOLUME_REPEAT_INITIAL_DELAY_MS)
        assertEquals(100L, VOLUME_REPEAT_INTERVAL_MS)
        assertEquals(180L, VOLUME_REPEAT_HEARTBEAT_TIMEOUT_MS)
        assertEquals(3_000L, VOLUME_REPEAT_HARD_CAP_MS)
        assertEquals(580L, volumeSilenceWindowMs(seenHeartbeat = false))
        assertEquals(180L, volumeSilenceWindowMs(seenHeartbeat = true))
        assertEquals(24, VOLUME_KEYCODE_UP)
        assertEquals(25, VOLUME_KEYCODE_DOWN)
        assertEquals(164, VOLUME_KEYCODE_MUTE)
        assertEquals(48, VOLUME_KEY_LOG_CAPACITY)
    }

    // ------------------------------------------------------------ AC-I2-1 short press stays with the system

    /** AC-I2-1 short press: first DOWN opens a press, is given back to the system (consume = false), controller 0 steps. */
    @Test fun shortPressIsLeftToTheSystem() {
        val r = Rig()
        assertFalse("first DOWN must go to the system", r.down(0, up, 1))
        assertTrue(r.c.openedNewPress)
        assertEquals(up, r.c.activeKeyCode)
        assertTrue(r.volume.calls.isEmpty())
        r.up(120, up)
        r.idleUntil(10_000)
        assertTrue("controller must not step on a short press", r.volume.calls.isEmpty())
        assertEquals(0, r.c.stepsTaken)
        assertFalse(r.c.lastAutoStop)
        assertNull(r.c.activeKeyCode)
    }

    /** consumeVolumeDown rule: new press -> false (system), not a new press -> true (eat it). */
    @Test fun consumeRuleIsExactlyNotNewPress() {
        assertFalse(consumeVolumeDown(true))
        assertTrue(consumeVolumeDown(false))
    }

    /** AC-I2-1: released before 400 ms, even with early repeat events -> controller 0 steps. */
    @Test fun pressShorterThanInitialDelayHasNoControllerStep() {
        val r = Rig()
        r.down(0, up, 1)
        r.beats(from = 50, to = 350, every = 50, key = up, downTime = 1)
        r.up(380, up)
        r.idleUntil(10_000)
        assertTrue(r.volume.calls.isEmpty())
    }

    // ------------------------------------------------------------ AC-I2-1 hold to repeat

    /** AC-I2-1: hold with 50 ms repeats -> controller steps exactly at 400, 500, ... 1000 ms. */
    @Test fun holdStepsAt400ThenEvery100() {
        val r = Rig()
        r.down(0, up, 1)
        r.beats(from = 50, to = 1_000, every = 50, key = up, downTime = 1)
        r.up(1_020, up)
        assertEquals(everyHundred(400L, 1_000L), r.volume.moveTimes)
        assertEquals(57, r.volume.level)
    }

    /** AC-I2-1: repeats with unknown downTime (0) are accepted for the open press of the same key. */
    @Test fun holdWithUnknownDownTimeStillRepeats() {
        val r = Rig()
        r.down(0, down, 1)
        r.beats(from = 50, to = 700, every = 50, key = down, downTime = 0)
        r.up(710, down)
        assertEquals(everyHundred(400L, 700L), r.volume.moveTimes)
    }

    /** AC-I2-1 "fixed interval": irregular 30 ms repeats -> gaps always in [100, 130). */
    @Test fun repeatIntervalStaysFixedWithIrregularHeartbeats() {
        val r = Rig()
        r.down(0, down, 1)
        r.beats(from = 30, to = 2_000, every = 30, key = down, downTime = 1)
        r.up(2_010, down)
        val times = r.volume.moveTimes
        assertTrue("should repeat many times: $times", times.size >= 10)
        assertTrue("first step not before 400 ms: $times", times.first() >= 400L)
        times.zipWithNext().forEach { (a, b) ->
            assertTrue("gap ${b - a} ms not in [100,130): $times", b - a >= 100L && b - a < 130L)
        }
    }

    /** AC-I2-1 (Gordon's baseline): holding 10 s keeps stepping every 100 ms; no 3 s cut-off for a matched hold. */
    @Test fun holdLongerThanThreeSecondsKeepsStepping() {
        val r = Rig(level = 50, max = 1000)
        r.down(0, up, 1)
        r.beats(from = 50, to = 10_000, every = 50, key = up, downTime = 1)
        assertEquals(up, r.c.activeKeyCode)
        r.up(10_010, up)
        assertEquals(everyHundred(400L, 10_000L), r.volume.moveTimes)
        assertTrue(r.volume.moveTimes.count { it > 3_000L } >= 70)
        assertEquals(50 + 97, r.volume.level)
    }

    /** AC-I2-1 + AC-I2-3: hold 10 s from near the top -> steps every 100 ms until AT_BOUND, then no more calls. */
    @Test fun holdStepsUntilAtBoundThenStops() {
        val r = Rig(level = 90, max = 100)
        r.down(0, up, 1)
        r.beats(from = 50, to = 10_000, every = 50, key = up, downTime = 1)
        assertEquals(100, r.volume.level)
        assertEquals(everyHundred(400L, 1_300L), r.volume.moveTimes)
        assertEquals(VolumeStepResult.AT_BOUND, r.volume.calls.last().result)
        assertEquals(1_400L, r.volume.calls.last().at)
        assertEquals(11, r.volume.calls.size)
        r.up(10_010, up)
        // not stuck: the other way works on the next press
        r.down(11_000, down, 11_000)
        r.beats(from = 11_050, to = 11_600, every = 50, key = down, downTime = 11_000)
        r.up(11_610, down)
        assertEquals(97, r.volume.level)
    }

    /** AC-I2-3: already at min and hold down -> one AT_BOUND call, no error, no more calls; up still works. */
    @Test fun atMinHoldDownIsHarmlessAndRecovers() {
        val r = Rig(level = 0, max = 10)
        r.down(0, down, 1)
        r.beats(from = 50, to = 1_500, every = 50, key = down, downTime = 1)
        r.up(1_510, down)
        assertEquals(0, r.volume.level)
        assertEquals(1, r.volume.calls.size)
        assertEquals(VolumeStepResult.AT_BOUND, r.volume.calls.single().result)
        r.down(2_000, up, 2_000)
        r.beats(from = 2_050, to = 2_600, every = 50, key = up, downTime = 2_000)
        r.up(2_610, up)
        assertEquals(3, r.volume.level)
    }

    /** AC-I2-1: up only moves up, down only moves down. */
    @Test fun directionsAreCorrect() {
        val r = Rig()
        r.down(0, up, 1)
        r.beats(from = 50, to = 600, every = 50, key = up, downTime = 1)
        r.up(620, up)
        assertTrue(r.volume.calls.all { it.direction == VolumeDirection.UP })
        assertEquals(53, r.volume.level)
        r.down(1_000, down, 1_000)
        r.beats(from = 1_050, to = 1_600, every = 50, key = down, downTime = 1_000)
        r.up(1_620, down)
        assertTrue(r.volume.calls.filter { it.at >= 1_000L }.all { it.direction == VolumeDirection.DOWN })
        assertEquals(50, r.volume.level)
    }

    /** AC-I2-1 mute / AC-I2-5 Home: mute, Home, Back, Center, Enter never enter repeat, never step, never record. */
    @Test fun nonVolumeKeysNeverEnterRepeat() {
        val r = Rig()
        for (key in listOf(164, 3, 4, 23, 66)) {
            r.clock.now += 10
            assertFalse("key=$key onKeyDown", r.c.onKeyDown(key, r.clock.now))
            assertFalse("key=$key openedNewPress", r.c.openedNewPress)
            assertFalse("key=$key onHeartbeat", r.c.onHeartbeat(key, r.clock.now))
            assertFalse("key=$key onKeyUp", r.c.onKeyUp(key))
            assertNull("key=$key", r.c.activeKeyCode)
        }
        r.idleUntil(10_000)
        assertTrue(r.volume.calls.isEmpty())
        assertTrue(r.records.isEmpty())
    }

    /** AC-I2-1 mute: pressing mute during a volume hold neither steps nor ends the hold. */
    @Test fun muteDuringHoldNeitherStepsNorEndsHold() {
        val r = Rig()
        r.down(0, up, 1)
        r.beats(from = 50, to = 450, every = 50, key = up, downTime = 1)
        val before = r.volume.calls.size
        assertFalse(r.c.onKeyDown(164, 460))
        assertFalse(r.c.onKeyUp(164))
        assertEquals(before, r.volume.calls.size)
        assertEquals(up, r.c.activeKeyCode)
    }

    /**
     * AC-I2-1 switch direction mid-hold: up is held and repeating, down is pressed (new downTime).
     * Up stops at once; late up repeats do nothing; down repeats on its own 400/100 rhythm.
     */
    @Test fun switchingDirectionMidHoldStopsTheFirstKey() {
        val r = Rig()
        r.down(0, up, 1)
        r.beats(from = 50, to = 800, every = 50, key = up, downTime = 1)
        assertEquals(everyHundred(400L, 800L), r.volume.moveTimes)
        assertFalse("new down press goes to the system", r.down(850, down, 851))
        assertEquals(down, r.c.activeKeyCode)
        r.beat(860, up, 1)
        r.beat(870, up, 0)
        r.beats(from = 900, to = 1_450, every = 50, key = down, downTime = 851)
        r.up(1_460, down)
        r.idleUntil(10_000)
        assertEquals(5, r.volume.calls.count { it.direction == VolumeDirection.UP })
        assertEquals(listOf(1_250L, 1_350L, 1_450L), r.volume.calls.filter { it.direction == VolumeDirection.DOWN }.map { it.at })
    }

    // ------------------------------------------------------------ release and BASE

    /** AC-I2-1 wording: after release at most one extra step. */
    @Test fun releaseStopsWithAtMostOneExtraStep() {
        val r = Rig()
        r.down(0, up, 1)
        r.beats(from = 50, to = 1_000, every = 50, key = up, downTime = 1)
        r.up(1_030, up)
        r.idleUntil(10_000)
        assertTrue(r.volume.calls.count { it.at > 1_030L } <= 1)
    }

    /** AC-I2-1 + PR promise: zero steps after release, even for late repeats (same downTime or unknown). */
    @Test fun releaseStopsImmediatelyAndLateRepeatsDoNothing() {
        val r = Rig()
        r.down(0, up, 1)
        r.beats(from = 50, to = 1_000, every = 50, key = up, downTime = 1)
        r.up(1_030, up)
        val n = r.volume.calls.size
        r.beat(1_050, up, 1)
        r.beat(1_100, up, 0)
        r.beats(from = 1_150, to = 5_000, every = 50, key = up, downTime = 1)
        r.idleUntil(10_000)
        assertEquals(n, r.volume.calls.size)
        assertNull(r.c.activeKeyCode)
    }

    /** BASE core: no repeats and no release -> controller 0 steps, auto stop by 580 ms, still 0 after 10 s. */
    @Test fun noHeartbeatNoReleaseIsZeroControllerStepsForever() {
        val r = Rig()
        assertFalse(r.down(0, up, 1))
        r.idleUntil(580)
        assertNull("must auto stop at the 580 ms silence window", r.c.activeKeyCode)
        assertTrue(r.c.lastAutoStop)
        r.idleUntil(10_000)
        assertTrue(r.volume.calls.isEmpty())
        assertEquals(0, r.c.stepsTaken)
    }

    /** BASE: moving only the clock and calling onTimerTick never steps, for 10 s, with or without repeats seen. */
    @Test fun timerTickAloneNeverMovesVolume() {
        val r = Rig()
        r.down(0, down, 1)
        r.beats(from = 400, to = 600, every = 50, key = down, downTime = 1)
        val n = r.volume.calls.size
        assertEquals(3, n) // 400, 500, 600
        var t = 600L
        while (t <= 10_600L) {
            r.clock.now = t
            r.c.onTimerTick()
            t += 7
        }
        assertEquals(n, r.volume.calls.size)
        assertNull(r.c.activeKeyCode)
    }

    /** BASE / AC-I2-2 addendum: repeats stop without release -> no step after the last repeat, stopped within 180 ms. */
    @Test fun repeatsStopWithoutReleaseStopsWithin180msWithoutExtraStep() {
        val r = Rig()
        r.down(0, down, 1)
        r.beats(from = 50, to = 1_000, every = 50, key = down, downTime = 1)
        val n = r.volume.calls.size
        r.idleUntil(1_180L)
        assertNull("must stop 180 ms after the last repeat", r.c.activeKeyCode)
        assertTrue(r.c.lastAutoStop)
        r.idleUntil(10_000)
        assertEquals(n, r.volume.calls.size)
        assertTrue(r.volume.calls.none { it.at > 1_000L })
    }

    /** AC-I2-2: service interrupted (stop) ends the hold; later repeats do nothing. */
    @Test fun stopEndsHoldImmediately() {
        val r = Rig()
        r.down(0, up, 1)
        r.beats(from = 50, to = 600, every = 50, key = up, downTime = 1)
        val n = r.volume.calls.size
        r.c.stop()
        assertNull(r.c.activeKeyCode)
        assertTrue(r.c.lastAutoStop)
        r.beats(from = 650, to = 1_500, every = 50, key = up, downTime = 1)
        r.beats(from = 1_525, to = 2_000, every = 50, key = up, downTime = 0)
        r.idleUntil(10_000)
        assertEquals(n, r.volume.calls.size)
    }

    // ------------------------------------------------------------ downTime de-dup and unmatched repeats

    /** BASE: same DOWN re-sent while the press is open -> not a new press, eaten (consume = true), 0 steps. */
    @Test fun sameDownResentWhileOpenIsEaten() {
        val r = Rig()
        assertFalse(r.down(0, up, 7))
        assertTrue("re-sent DOWN must be eaten", r.down(50, up, 7))
        assertFalse(r.c.openedNewPress)
        assertEquals(up, r.c.activeKeyCode)
        assertTrue(r.volume.calls.isEmpty())
    }

    /** BASE: a finished press (released) whose DOWN arrives late -> no new press, 0 steps, consume = true. */
    @Test fun staleDownAfterReleaseIsEatenAndDoesNotStep() {
        val r = Rig()
        r.down(0, up, 1)
        r.up(100, up)
        assertTrue("stale DOWN must be eaten", r.down(200, up, 1))
        assertFalse(r.c.openedNewPress)
        assertNull(r.c.activeKeyCode)
        r.beats(from = 250, to = 3_000, every = 50, key = up, downTime = 1)
        r.idleUntil(10_000)
        assertTrue(r.volume.calls.isEmpty())
        assertNull(r.c.activeKeyCode)
    }

    /** BASE: a press that auto-stopped (no release seen) whose DOWN and repeats keep coming -> no new press, 0 steps. */
    @Test fun staleDownAfterAutoStopIsEatenAndDoesNotStep() {
        val r = Rig()
        r.down(0, up, 1)
        r.idleUntil(1_000)
        assertTrue(r.c.lastAutoStop)
        var t = 1_100L
        while (t <= 6_000L) {
            assertTrue("t=$t stale DOWN must be eaten", r.down(t, up, 1))
            assertNull(r.c.activeKeyCode)
            r.beat(t + 25, up, 1)
            t += 50
        }
        r.idleUntil(12_000)
        assertTrue(r.volume.calls.isEmpty())
    }

    /** Not stuck: after a stale DOWN was eaten, a genuinely new press still opens and repeats. */
    @Test fun newPressAfterStaleDownStillWorks() {
        val r = Rig()
        r.down(0, up, 1)
        r.up(100, up)
        assertTrue(r.down(200, up, 1))
        assertFalse(r.down(1_000, up, 1_000))
        assertTrue(r.c.openedNewPress)
        r.beats(from = 1_050, to = 1_600, every = 50, key = up, downTime = 1_000)
        r.up(1_610, up)
        assertEquals(listOf(1_400L, 1_500L, 1_600L), r.volume.moveTimes)
    }

    /** BASE: repeats that match no live press (no press, wrong downTime, unknown downTime with no press) never step, for 10 s. */
    @Test fun unmatchedRepeatsNeverStep() {
        val r = Rig()
        var t = 0L
        while (t <= 10_000L) {
            r.beat(t, up, 0)
            r.beat(t + 10, up, 99)
            r.beat(t + 20, down, 0)
            assertNull("t=$t an unmatched repeat must not open a press", r.c.activeKeyCode)
            t += 50
        }
        assertTrue(r.volume.calls.isEmpty())
        assertTrue(r.records.isEmpty())
    }

    /** BASE: while a press is open, repeats with a different downTime or the other key never step and do not keep it alive. */
    @Test fun mismatchedRepeatsDuringOpenPressNeverStep() {
        val r = Rig()
        r.down(0, up, 5)
        r.beats(from = 50, to = 2_000, every = 50, key = up, downTime = 6)
        assertTrue(r.volume.calls.isEmpty())
        assertNull("wrong-downTime repeats must not keep the press alive", r.c.activeKeyCode)
        r.down(3_000, up, 3_000)
        r.beats(from = 3_050, to = 4_000, every = 50, key = down, downTime = 0)
        assertTrue(r.volume.calls.isEmpty())
        assertNull(r.c.activeKeyCode)
    }

    /**
     * BASE property test (fixed seed, reproducible). Random downs / repeats / ups / ticks / silences.
     * 1. Timers never step: nothing moves during advanceTo, onTimerTick, onKeyDown or onKeyUp.
     * 2. Only a repeat event can step, at most one step per event, and only if it matches the open press
     *    (same key; downTime 0 or equal), at least 400 ms after that press opened and 100 ms after its last step.
     * 3. After the events stop, 10 s of idle time moves nothing.
     */
    @Test fun volumeOnlyMovesOnMatchedRepeatEvents() {
        val rnd = Random(20261110)
        repeat(300) { round ->
            val r = Rig(level = 500)
            var openKey = 0
            var openDt = 0L
            var openAt = 0L
            var lastStep = 0L
            var t = 0L
            repeat(80) {
                t += rnd.nextLong(1, 250)
                val before = r.volume.calls.size
                r.scheduler.advanceTo(t)
                assertEquals("round=$round timer stepped at t=$t", before, r.volume.calls.size)
                val key = if (rnd.nextInt(4) == 0) down else up
                when (rnd.nextInt(12)) {
                    0, 1 -> {
                        val dt = if (rnd.nextBoolean() && openDt > 0L) openDt else t
                        r.c.onKeyDown(key, dt)
                        assertEquals("round=$round onKeyDown stepped", before, r.volume.calls.size)
                        if (r.c.openedNewPress) { openKey = key; openDt = dt; openAt = t; lastStep = t }
                    }
                    2 -> { r.c.onKeyUp(key); assertEquals("round=$round onKeyUp stepped", before, r.volume.calls.size) }
                    3 -> { r.c.onTimerTick(); assertEquals("round=$round tick stepped", before, r.volume.calls.size) }
                    else -> {
                        val beatKey = if (rnd.nextInt(6) == 0) key else (r.c.activeKeyCode ?: key)
                        val dt = when (rnd.nextInt(4)) { 0 -> 0L; 1 -> openDt + 1; else -> openDt }
                        val active = r.c.activeKeyCode
                        r.c.onHeartbeat(beatKey, dt)
                        val made = r.volume.calls.size - before
                        assertTrue("round=$round one repeat made $made calls", made <= 1)
                        if (made == 1) {
                            assertEquals("round=$round stepped without a live press", openKey, active)
                            assertEquals("round=$round stepped for the wrong key", openKey, beatKey)
                            assertTrue("round=$round stepped on a mismatched downTime", dt == 0L || dt == openDt)
                            assertTrue("round=$round stepped before 400 ms (t=$t open=$openAt)", t - openAt >= 400L)
                            assertTrue("round=$round gap < 100 ms (t=$t last=$lastStep)", t - lastStep >= 100L)
                            lastStep = t
                        }
                    }
                }
            }
            val n = r.volume.calls.size
            r.idleUntil(t + 10_000)
            assertEquals("round=$round volume moved after events stopped", n, r.volume.calls.size)
            assertNull("round=$round press still open after 10 s idle", r.c.activeKeyCode)
        }
    }

    // ------------------------------------------------------------ AC-I2-4 key log

    /** AC-I2-4: one record for the down and one for the up, with uptime, wall time, key and step count. */
    @Test fun recordsDownAndUpWithUptimeAndWallTime() {
        val r = Rig()
        val wallBefore = System.currentTimeMillis()
        r.down(100, up, 101)
        r.beats(from = 150, to = 700, every = 50, key = up, downTime = 101)
        r.up(720, up)
        val wallAfter = System.currentTimeMillis()
        assertEquals(2, r.records.size)
        val (d, u) = r.records
        assertEquals(VOLUME_LOG_DOWN, d.action)
        assertEquals(100L, d.timeMs)
        assertEquals(up, d.keyCode)
        assertEquals(0, d.steps)
        assertEquals(VOLUME_LOG_UP, u.action)
        assertEquals(720L, u.timeMs)
        assertEquals(3, u.steps) // 500, 600, 700
        assertFalse(u.autoStop)
        for (rec in r.records) {
            assertTrue("wall time ${rec.wallTimeMs} not in [$wallBefore, $wallAfter]", rec.wallTimeMs in wallBefore..wallAfter)
        }
    }

    /** AC-I2-4 + AC-I2-7: when release never arrives, the record says autostop. */
    @Test fun recordShowsAutoStopWhenReleaseNeverArrives() {
        val r = Rig()
        r.down(0, down, 1)
        r.idleUntil(10_000)
        val last = r.records.last()
        assertEquals(VOLUME_LOG_AUTOSTOP, last.action)
        assertTrue(last.autoStop)
        assertEquals(0, last.steps)
        assertEquals(580L, last.timeMs)
    }

    /** AC-I2-4: a recorder that throws must not break the volume key. */
    @Test fun failingRecorderDoesNotBreakVolume() {
        val clock = ManualVolumeClock()
        val scheduler = ManualVolumeScheduler(clock)
        val volume = FakeVolume(clock)
        val c = VolumeRepeatController(clock, scheduler, volume) { throw IllegalStateException("disk full") }
        c.onKeyDown(up, 1)
        assertTrue(c.openedNewPress)
        var t = 50L
        while (t <= 600L) { scheduler.advanceTo(t); c.onHeartbeat(up, 1); t += 50 }
        c.onKeyUp(up)
        assertEquals(listOf(400L, 500L, 600L), volume.moveTimes)
    }

    /** AC-I2-4: 7-field line `uptime wallTimeMs isoLocal key action steps autoStop`; no wall time -> "-". */
    @Test fun logLineHasSevenFieldsWithLocalWallTime() {
        val taipei = ZoneId.of("Asia/Taipei")
        val rec = VolumeKeyRecord(1234L, up, VOLUME_LOG_DOWN, 0, false, 1_700_000_000_000L)
        assertEquals("1234 1700000000000 2023-11-15T06:13:20.000 24 down 0 false", rec.formatLine(taipei))
        val old = VolumeKeyRecord(5L, down, VOLUME_LOG_UP, 3, false)
        assertEquals(0L, old.wallTimeMs)
        assertEquals("5 0 - 25 up 3 false", old.formatLine(taipei))
        assertEquals(7, rec.formatLine().split(' ').size)
        assertEquals("-", formatVolumeWallTime(0L, taipei))
    }

    /** AC-I2-4: parser reads new 7-field lines and old 5-field lines (wall time 0), skips comments and garbage. */
    @Test fun parserReadsSevenAndLegacyFiveFieldLines() {
        val text = listOf(
            "# local-only volume key log; not uploaded",
            "1234 1700000000000 2023-11-15T06:13:20.000 24 down 0 false",
            "1500 24 up 4 false",
            "garbage line",
            "1600 1700000000600 - 25 autostop 0 true",
            "1 2 3",
            "",
        ).joinToString("\n")
        assertEquals(
            listOf(
                VolumeKeyRecord(1234L, 24, VOLUME_LOG_DOWN, 0, false, 1_700_000_000_000L),
                VolumeKeyRecord(1500L, 24, VOLUME_LOG_UP, 4, false, 0L),
                VolumeKeyRecord(1600L, 25, VOLUME_LOG_AUTOSTOP, 0, true, 1_700_000_000_600L),
            ),
            parseVolumeKeyLog(text),
        )
    }

    /** AC-I2-4: ring keeps the last 48 records (oldest dropped); export text reads back identically, wall time included. */
    @Test fun logStoreKeepsLast48AndExportRoundTrips() {
        val dir = java.nio.file.Files.createTempDirectory("issue1-volume-log").toFile()
        dir.deleteOnExit()
        val store = VolumeKeyLogStore(volumeKeyLogFile(dir))
        repeat(60) { i ->
            val rec = VolumeKeyRecord(i.toLong(), if (i % 2 == 0) up else down, VOLUME_LOG_DOWN, i, false, 1_700_000_000_000L + i)
            assertTrue(store.append(rec))
        }
        val kept = store.read()
        assertEquals(48, kept.size)
        assertEquals((12L until 60L).toList(), kept.map { it.timeMs })
        assertEquals((12L until 60L).map { 1_700_000_000_000L + it }, kept.map { it.wallTimeMs })
        assertEquals(kept, parseVolumeKeyLog(store.exportText()))
        assertEquals(File(dir, VOLUME_KEY_LOG_FILE), volumeKeyLogFile(dir))
    }

    /** AC-I2-4: real controller records survive a write + export round trip. */
    @Test fun controllerRecordsSurviveExport() {
        val dir = java.nio.file.Files.createTempDirectory("issue1-volume-log2").toFile()
        dir.deleteOnExit()
        val store = VolumeKeyLogStore(volumeKeyLogFile(dir))
        val clock = ManualVolumeClock()
        val scheduler = ManualVolumeScheduler(clock)
        val c = VolumeRepeatController(clock, scheduler, FakeVolume(clock)) { store.append(it) }
        clock.now = 5
        c.onKeyDown(up, 6)
        scheduler.advanceTo(80)
        c.onKeyUp(up)
        scheduler.advanceTo(200)
        c.onKeyDown(down, 201)
        scheduler.advanceTo(10_000)
        val read = parseVolumeKeyLog(store.exportText())
        assertEquals(listOf(VOLUME_LOG_DOWN, VOLUME_LOG_UP, VOLUME_LOG_DOWN, VOLUME_LOG_AUTOSTOP), read.map { it.action })
        assertEquals(listOf(5L, 80L, 200L, 780L), read.map { it.timeMs })
        assertEquals(listOf(up, up, down, down), read.map { it.keyCode })
        assertTrue(read.all { it.wallTimeMs > 0L })
    }
}
