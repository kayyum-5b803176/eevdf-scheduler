package com.eevdf.core.scheduler

import com.eevdf.core.scheduler.timer.TimerEffect
import com.eevdf.core.scheduler.timer.TimerEngine
import com.eevdf.core.scheduler.timer.TimerEvent
import com.eevdf.core.scheduler.timer.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHARACTERIZATION tests for the timer FSM.
 *
 * These lock in the *current* behaviour of [TimerEngine.reduce] so that the
 * upcoming refactors (module split, MainActivity teardown) cannot change it
 * silently. Where current behaviour looks wrong, the test asserts what the code
 * does today and the KNOWN-BUG comment says what it should do. Fix the bug and
 * the test turns red on purpose — that is the signal to update it deliberately.
 */
class TimerEngineCharacterizationTest {

    @Test fun `start seeds the slice and marks running`() {
        val (state, effects) = TimerEngine.reduce(TimerState(), TimerEvent.Start("t1", 90L))
        assertEquals("t1", state.taskId)
        assertTrue(state.running)
        assertEquals(90L, state.remainingSeconds)
        assertEquals(0L, state.accumulatedMs)
        assertTrue(effects.isEmpty())
    }

    @Test fun `tick below one second accumulates without decrementing`() {
        val start = TimerState("t1", running = true, remainingSeconds = 10L)
        val (state, effects) = TimerEngine.reduce(start, TimerEvent.Tick(400L))
        assertEquals(10L, state.remainingSeconds)
        assertEquals(400L, state.accumulatedMs)
        assertTrue(effects.isEmpty())
    }

    @Test fun `sub-second remainders carry across ticks and do not drift`() {
        var state = TimerState("t1", running = true, remainingSeconds = 10L)
        // Ten 300ms ticks == 3000ms == exactly 3 seconds consumed.
        repeat(10) { state = TimerEngine.reduce(state, TimerEvent.Tick(300L)).first }
        assertEquals("300ms x10 must consume exactly 3s", 7L, state.remainingSeconds)
        assertEquals(0L, state.accumulatedMs)
    }

    @Test fun `tick while paused is ignored`() {
        val paused = TimerState("t1", running = false, remainingSeconds = 10L)
        val (state, effects) = TimerEngine.reduce(paused, TimerEvent.Tick(5_000L))
        assertEquals(paused, state)
        assertTrue(effects.isEmpty())
    }

    @Test fun `remaining clamps at zero and never goes negative`() {
        val start = TimerState("t1", running = true, remainingSeconds = 2L)
        val (state, _) = TimerEngine.reduce(start, TimerEvent.Tick(60_000L))
        assertEquals(0L, state.remainingSeconds)
    }

    @Test fun `expiry stops the timer and emits exactly one Expired effect`() {
        val start = TimerState("t1", running = true, remainingSeconds = 1L)
        val (state, effects) = TimerEngine.reduce(start, TimerEvent.Tick(1_000L))
        assertFalse(state.running)
        assertEquals(1, effects.size)
        assertEquals("t1", (effects.single() as TimerEffect.Expired).taskId)
    }

    @Test fun `expiry does not re-fire once already at zero`() {
        val expired = TimerState("t1", running = false, remainingSeconds = 0L)
        val (_, effects) = TimerEngine.reduce(expired, TimerEvent.Tick(1_000L))
        assertTrue("a stopped timer must not emit a second Expired", effects.isEmpty())
    }

    /**
     * KNOWN BUG (locked in deliberately): [TimerEffect.Expired.ranSeconds] is
     * hardcoded to 0L in TimerEngine.reduce. Anything downstream that credits
     * run time from this effect — stats, totalRunTime, vruntime advance — will
     * record zero. Fix by threading the slice length through TimerState, then
     * update this assertion.
     */
    @Test fun `KNOWN BUG expired effect reports zero ranSeconds`() {
        val start = TimerState("t1", running = true, remainingSeconds = 1L)
        val (_, effects) = TimerEngine.reduce(start, TimerEvent.Tick(1_000L))
        assertEquals(0L, (effects.single() as TimerEffect.Expired).ranSeconds)
    }

    @Test fun `pause preserves remaining and accumulated`() {
        val running = TimerState("t1", running = true, remainingSeconds = 42L, accumulatedMs = 750L)
        val (state, _) = TimerEngine.reduce(running, TimerEvent.Pause)
        assertFalse(state.running)
        assertEquals(42L, state.remainingSeconds)
        assertEquals(750L, state.accumulatedMs)
    }

    @Test fun `clear resets to the empty state`() {
        val running = TimerState("t1", running = true, remainingSeconds = 42L, accumulatedMs = 750L)
        assertEquals(TimerState(), TimerEngine.reduce(running, TimerEvent.Clear).first)
    }

    @Test fun `reduce never mutates the state it was given`() {
        val original = TimerState("t1", running = true, remainingSeconds = 10L, accumulatedMs = 100L)
        val snapshot = original.copy()
        TimerEngine.reduce(original, TimerEvent.Tick(2_500L))
        assertEquals("reducer must be pure", snapshot, original)
    }
}
