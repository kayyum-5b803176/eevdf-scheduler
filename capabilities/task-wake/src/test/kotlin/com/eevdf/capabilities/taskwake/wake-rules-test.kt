package com.eevdf.capabilities.taskwake

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules of the scheduled wake: class ranking, what a due wake does, prompt numbers → time, text. */
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

    @Test fun `a higher class outranks a lower one`() {
        assertTrue(WakeRules.outranks(dl, 50, rt, 99))
        assertTrue(WakeRules.outranks(rt, 1, fair, 50))
        assertTrue(WakeRules.outranks(fair, 50, idle, 50))
        assertFalse(WakeRules.outranks(fair, 50, rt, 1))
        assertFalse(WakeRules.outranks(idle, 50, fair, 50))
    }

    @Test fun `inside realtime the higher priority outranks, equal does not`() {
        assertTrue(WakeRules.outranks(rt, 80, rt, 50))
        assertFalse(WakeRules.outranks(rt, 50, rt, 80))
        assertFalse(WakeRules.outranks(rt, 50, rt, 50))
    }

    @Test fun `equal fair idle or deadline tasks never preempt each other`() {
        assertFalse(WakeRules.outranks(fair, 99, fair, 1))
        assertFalse(WakeRules.outranks(idle, 99, idle, 1))
        assertFalse(WakeRules.outranks(dl, 99, dl, 1))
    }

    // ── What a due wake does ──────────────────────────────────────────────────

    @Test fun `a wake whose task is already current is simply cleared`() {
        assertEquals(WakeRules.Decision.CLEAR_SELF, WakeRules.decide(hasCurrent = true, currentIsWakeTask = true, outranksCurrent = false))
        assertEquals(WakeRules.Decision.CLEAR_SELF, WakeRules.decide(hasCurrent = true, currentIsWakeTask = true, outranksCurrent = true))
    }

    @Test fun `with nothing current the card is shown`() {
        assertEquals(WakeRules.Decision.SHOW_CARD, WakeRules.decide(hasCurrent = false, currentIsWakeTask = false, outranksCurrent = false))
    }

    @Test fun `a wake that outranks the current task shows the card`() {
        assertEquals(WakeRules.Decision.SHOW_CARD, WakeRules.decide(hasCurrent = true, currentIsWakeTask = false, outranksCurrent = true))
    }

    @Test fun `a wake that does not outrank the current task keeps waiting`() {
        assertEquals(WakeRules.Decision.BLOCKED, WakeRules.decide(hasCurrent = true, currentIsWakeTask = false, outranksCurrent = false))
    }

    @Test fun `a wake is missed once it is overdue by more than two minutes`() {
        assertFalse(WakeRules.isMissed(now, now + 119_000L))
        assertFalse(WakeRules.isMissed(now, now + WakeRules.MISSED_AFTER_MS))
        assertTrue(WakeRules.isMissed(now, now + WakeRules.MISSED_AFTER_MS + 1L))
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
