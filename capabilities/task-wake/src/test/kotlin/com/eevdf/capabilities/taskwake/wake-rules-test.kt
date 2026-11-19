package com.eevdf.capabilities.taskwake

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules of the scheduled wake: who may preempt whom, what a due wake does, prompt numbers → time, text. */
class WakeRulesTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0): Long =
        Calendar.getInstance(utc).apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis

    private val now = at(2026, 11, 20, 10, 0, 0)

    private val fair = "fair_sched_class"
    private val rt   = "rt_sched_class"
    private val dl   = "dl_sched_class"
    private val idle = "idle_sched_class"

    // ── Class comparison ──────────────────────────────────────────────────────

    @Test fun `classes are ranked stop over deadline over realtime over fair over idle`() {
        assertTrue(WakeRules.classRank("stop_sched_class") > WakeRules.classRank(dl))
        assertTrue(WakeRules.classRank(dl) > WakeRules.classRank(rt))
        assertTrue(WakeRules.classRank(rt) > WakeRules.classRank(fair))
        assertTrue(WakeRules.classRank(fair) > WakeRules.classRank(idle))
        assertEquals(WakeRules.classRank(fair), WakeRules.classRank("something_else"))
    }

    @Test fun `a higher class may preempt a lower one, never the other way round`() {
        assertTrue(WakeRules.canPreempt(dl, 1, rt, 99))
        assertTrue(WakeRules.canPreempt(rt, 1, fair, 50))
        assertTrue(WakeRules.canPreempt(fair, 1, idle, 50))
        assertFalse(WakeRules.canPreempt(fair, 99, rt, 1))      // a lower class never preempts, whatever its priority
        assertFalse(WakeRules.canPreempt(idle, 99, fair, 1))
    }

    @Test fun `the same class and the same priority may preempt`() {
        assertTrue(WakeRules.canPreempt(fair, 4, fair, 4))
        assertTrue(WakeRules.canPreempt(rt, 50, rt, 50))
        assertTrue(WakeRules.canPreempt(dl, 4, dl, 4))
        assertTrue(WakeRules.canPreempt(idle, 4, idle, 4))
    }

    @Test fun `the same class and a higher priority may preempt`() {
        assertTrue(WakeRules.canPreempt(fair, 6, fair, 4))
        assertTrue(WakeRules.canPreempt(rt, 80, rt, 50))
    }

    @Test fun `the same class and a lower priority may not - the current task is protected`() {
        assertFalse(WakeRules.canPreempt(fair, 3, fair, 4))
        assertFalse(WakeRules.canPreempt(rt, 50, rt, 80))
        assertFalse(WakeRules.canPreempt(dl, 1, dl, 9))
    }

    @Test fun `priority is rtPriority for realtime and the task priority otherwise`() {
        assertEquals(80, WakeRules.effectivePriority(rt, priority = 4, rtPriority = 80))
        assertEquals(4, WakeRules.effectivePriority(fair, priority = 4, rtPriority = 80))
        assertEquals(4, WakeRules.effectivePriority(dl, priority = 4, rtPriority = 80))
        assertEquals(4, WakeRules.effectivePriority(idle, priority = 4, rtPriority = 80))
    }

    // ── What a due wake does ──────────────────────────────────────────────────

    @Test fun `a wake whose task is already current is simply cleared`() {
        assertEquals(WakeRules.Decision.CLEAR_SELF, WakeRules.decide(hasCurrent = true, currentIsWakeTask = true, canPreemptCurrent = false))
        assertEquals(WakeRules.Decision.CLEAR_SELF, WakeRules.decide(hasCurrent = true, currentIsWakeTask = true, canPreemptCurrent = true))
    }

    @Test fun `with nothing current the card is shown`() {
        assertEquals(WakeRules.Decision.SHOW_CARD, WakeRules.decide(hasCurrent = false, currentIsWakeTask = false, canPreemptCurrent = false))
    }

    @Test fun `a wake that may preempt the current task shows the card`() {
        assertEquals(WakeRules.Decision.SHOW_CARD, WakeRules.decide(hasCurrent = true, currentIsWakeTask = false, canPreemptCurrent = true))
    }

    @Test fun `a wake that may not preempt the current task keeps waiting`() {
        assertEquals(WakeRules.Decision.BLOCKED, WakeRules.decide(hasCurrent = true, currentIsWakeTask = false, canPreemptCurrent = false))
    }

    @Test fun `a wake is missed once it is overdue by more than two minutes`() {
        assertFalse(WakeRules.isMissed(now, now + 119_000L))
        assertFalse(WakeRules.isMissed(now, now + WakeRules.MISSED_AFTER_MS))
        assertTrue(WakeRules.isMissed(now, now + WakeRules.MISSED_AFTER_MS + 1L))
    }

    // ── The input popup ───────────────────────────────────────────────────────

    @Test fun `the prompt opens in the mode the task was last set with`() {
        assertEquals("TIMER", WakeRules.initialMode("TIMER"))
        assertEquals("ALARM", WakeRules.initialMode("ALARM"))
    }

    @Test fun `a task that never had a wake opens in Alarm`() {
        assertEquals("ALARM", WakeRules.initialMode(null))
    }

    @Test fun `an unrecognised stored mode falls back to Alarm`() {
        assertEquals("ALARM", WakeRules.initialMode(""))
        assertEquals("ALARM", WakeRules.initialMode("timer"))
        assertEquals("ALARM", WakeRules.initialMode("whatever"))
    }

    @Test fun `each mode has its own explanation for the info icon`() {
        assertEquals("Fires on this date and time (24 h)", WakeRules.modeInfo("ALARM"))
        assertEquals("Fires this long from now — Day, Month, Year are how many, not a date", WakeRules.modeInfo("TIMER"))
        assertTrue(WakeRules.modeInfo("ALARM") != WakeRules.modeInfo("TIMER"))
        assertEquals(WakeRules.modeInfo("ALARM"), WakeRules.modeInfo("unknown"))     // same fallback as initialMode
    }

    // ── Alarm mode ────────────────────────────────────────────────────────────

    @Test fun `an alarm is the instant of that date and time`() {
        val f = WakeRules.Fields(day = 21, month = 11, year = 2026, hour = 14, minute = 30, second = 5)
        assertEquals(at(2026, 11, 21, 14, 30, 5), WakeRules.alarmTarget(f, now, utc))
    }

    @Test fun `an alarm in the past is rejected`() {
        assertEquals(null, WakeRules.alarmTarget(WakeRules.Fields(20, 11, 2026, 9, 59, 59), now, utc))
        assertEquals(null, WakeRules.alarmTarget(WakeRules.Fields(20, 11, 2026, 10, 0, 0), now, utc))   // exactly now is not the future
    }

    @Test fun `a date that does not exist is rejected`() {
        assertEquals(null, WakeRules.alarmTarget(WakeRules.Fields(31, 2, 2027, 12, 0, 0), now, utc))
        assertEquals(null, WakeRules.alarmTarget(WakeRules.Fields(30, 2, 2028, 12, 0, 0), now, utc))
        assertTrue(WakeRules.alarmTarget(WakeRules.Fields(29, 2, 2028, 12, 0, 0), now, utc) != null)   // 2028 is a leap year
    }

    @Test fun `a box out of range is rejected`() {
        assertEquals(null, WakeRules.alarmTarget(WakeRules.Fields(1, 13, 2027, 0, 0, 0), now, utc))
        assertEquals(null, WakeRules.alarmTarget(WakeRules.Fields(0, 1, 2027, 0, 0, 0), now, utc))
        assertEquals(null, WakeRules.alarmTarget(WakeRules.Fields(1, 1, 2027, 24, 0, 0), now, utc))
        assertEquals(null, WakeRules.alarmTarget(WakeRules.Fields(1, 1, 2027, 0, 60, 0), now, utc))
        assertEquals(null, WakeRules.alarmTarget(WakeRules.Fields(1, 1, 2027, 0, 0, 60), now, utc))
        assertEquals(null, WakeRules.alarmTarget(WakeRules.Fields(1, 1, 1999, 0, 0, 0), now, utc))
    }

    @Test fun `fieldsOf is the inverse of alarmTarget`() {
        val target = at(2027, 3, 9, 7, 8, 9)
        val f = WakeRules.fieldsOf(target, utc)
        assertEquals(WakeRules.Fields(9, 3, 2027, 7, 8, 9), f)
        assertEquals(target, WakeRules.alarmTarget(f, now, utc))
    }

    // ── Timer mode ────────────────────────────────────────────────────────────

    @Test fun `two days and eleven seconds from now`() {
        val f = WakeRules.Fields(day = 2, month = 0, year = 0, hour = 0, minute = 0, second = 11)
        assertEquals(now + 2 * 86_400_000L + 11_000L, WakeRules.timerTarget(f, now, utc))
    }

    @Test fun `a timer adds every box`() {
        val f = WakeRules.Fields(day = 1, month = 0, year = 0, hour = 2, minute = 3, second = 4)
        assertEquals(now + 86_400_000L + 2 * 3_600_000L + 3 * 60_000L + 4_000L, WakeRules.timerTarget(f, now, utc))
    }

    @Test fun `months and years are calendar months and years`() {
        val jan31 = at(2027, 1, 31, 8, 0, 0)
        assertEquals(at(2027, 2, 28, 8, 0, 0), WakeRules.timerTarget(WakeRules.Fields(0, 1, 0, 0, 0, 0), jan31, utc))
        assertEquals(at(2028, 11, 20, 10, 0, 0), WakeRules.timerTarget(WakeRules.Fields(0, 0, 2, 0, 0, 0), now, utc))
    }

    @Test fun `an empty or negative timer is rejected`() {
        assertEquals(null, WakeRules.timerTarget(WakeRules.Fields(0, 0, 0, 0, 0, 0), now, utc))
        assertEquals(null, WakeRules.timerTarget(WakeRules.Fields(-1, 0, 0, 5, 0, 0), now, utc))
    }

    @Test fun `an absurdly long timer is rejected`() {
        assertEquals(null, WakeRules.timerTarget(WakeRules.Fields(0, 0, 500, 0, 0, 0), now, utc))
        assertEquals(null, WakeRules.timerTarget(WakeRules.Fields(0, 0, 0, 0, 0, Int.MAX_VALUE), now, utc))
    }

    // ── Text ──────────────────────────────────────────────────────────────────

    @Test fun `remaining time reads as a clock, with days when there are some`() {
        assertEquals("now", WakeRules.formatRemaining(0L))
        assertEquals("now", WakeRules.formatRemaining(-5L))
        assertEquals("00:00:05", WakeRules.formatRemaining(5_000L))
        assertEquals("01:02:03", WakeRules.formatRemaining((3_600L + 120L + 3L) * 1_000L))
        assertEquals("2d 00:00:11", WakeRules.formatRemaining((2 * 86_400L + 11L) * 1_000L))
    }

    @Test fun `a target is formatted in the given zone`() {
        assertEquals("Fri 20 Nov 2026 10:00:00", WakeRules.formatTarget(now, utc, Locale.US))
    }
}
