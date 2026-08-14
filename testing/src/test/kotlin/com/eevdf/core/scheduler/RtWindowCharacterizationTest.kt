package com.eevdf.core.scheduler

import com.eevdf.core.scheduler.model.RtConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHARACTERIZATION tests for the RT activation window, including the
 * midnight-crossing case. This is the single most breakage-prone piece of pure
 * logic in the app: it is date-dependent, it has an overflow branch, and no
 * manual test session will reliably exercise 23:50-crossing-into-tomorrow.
 *
 * Day index: 0 = Sunday .. 6 = Saturday. Bit N of activeDaysMask enables day N.
 */
class RtWindowCharacterizationTest {

    private fun cfg(
        days: Int,
        hour: Int = 0,
        minute: Int = 0,
        second: Int = 0,
        timeout: Long = 0L,
        policy: RtConfig.Policy = RtConfig.Policy.RR,
    ) = RtConfig(
        priority = 50, policy = policy, activeDaysMask = days,
        activationHour = hour, activationMinute = minute, activationSecond = second,
        sliceTimeoutSeconds = timeout,
    )

    private fun day(n: Int) = 1 shl n
    private val MONDAY = 1
    private val TUESDAY = 2
    private val SUNDAY = 0

    @Test fun `not configured when no days selected`() {
        assertFalse(cfg(days = 0, timeout = 600L).isConfigured)
    }

    @Test fun `not configured when timeout is zero`() {
        assertFalse(cfg(days = day(MONDAY), timeout = 0L).isConfigured)
    }

    @Test fun `activationSecondOfDay composes hours minutes seconds`() {
        assertEquals(9 * 3600L + 30 * 60L + 15L, cfg(day(MONDAY), 9, 30, 15, 600L).activationSecondOfDay)
    }

    @Test fun `unconfigured window is never active`() {
        val c = cfg(days = 0, hour = 9, timeout = 3600L)
        assertFalse(c.isWindowActive(MONDAY, 9 * 3600L, SUNDAY))
    }

    // ── Normal, same-day window: Monday 09:00 for 1 hour ─────────────────────

    @Test fun `active at the exact activation second`() {
        val c = cfg(day(MONDAY), 9, 0, 0, 3600L)
        assertTrue(c.isWindowActive(MONDAY, 9 * 3600L, SUNDAY))
    }

    @Test fun `active one second before close`() {
        val c = cfg(day(MONDAY), 9, 0, 0, 3600L)
        assertTrue(c.isWindowActive(MONDAY, 10 * 3600L - 1, SUNDAY))
    }

    @Test fun `inactive at the exact close second - window end is exclusive`() {
        val c = cfg(day(MONDAY), 9, 0, 0, 3600L)
        assertFalse(c.isWindowActive(MONDAY, 10 * 3600L, SUNDAY))
    }

    @Test fun `inactive one second before activation`() {
        val c = cfg(day(MONDAY), 9, 0, 0, 3600L)
        assertFalse(c.isWindowActive(MONDAY, 9 * 3600L - 1, SUNDAY))
    }

    @Test fun `inactive on a day whose bit is not set`() {
        val c = cfg(day(MONDAY), 9, 0, 0, 3600L)
        assertFalse(c.isWindowActive(TUESDAY, 9 * 3600L, MONDAY))
    }

    // ── Midnight crossing: Monday 23:00 for 3 hours -> ends Tuesday 02:00 ────

    @Test fun `midnight crossing active late on the enabled day`() {
        val c = cfg(day(MONDAY), 23, 0, 0, 3 * 3600L)
        assertTrue(c.isWindowActive(MONDAY, 23 * 3600L + 30 * 60L, SUNDAY))
    }

    @Test fun `midnight crossing active early on the following day`() {
        val c = cfg(day(MONDAY), 23, 0, 0, 3 * 3600L)
        // Tuesday 01:00 - Tuesday's own bit is NOT set; it is active because
        // the previous day (Monday) is enabled and the window overflowed.
        assertTrue(c.isWindowActive(TUESDAY, 1 * 3600L, prevDayIndex = MONDAY))
    }

    @Test fun `midnight crossing inactive after the overflow ends`() {
        val c = cfg(day(MONDAY), 23, 0, 0, 3 * 3600L)
        assertFalse(c.isWindowActive(TUESDAY, 2 * 3600L, prevDayIndex = MONDAY))
    }

    @Test fun `midnight crossing inactive when previous day is not enabled`() {
        val c = cfg(day(MONDAY), 23, 0, 0, 3 * 3600L)
        // Sunday 01:00, previous day Saturday(6) is not enabled.
        assertFalse(c.isWindowActive(SUNDAY, 1 * 3600L, prevDayIndex = 6))
    }

    @Test fun `saturday to sunday wrap is handled`() {
        val c = cfg(day(6), 23, 30, 0, 2 * 3600L) // Sat 23:30 + 2h -> Sun 01:30
        assertTrue(c.isWindowActive(6, 23 * 3600L + 45 * 60L, prevDayIndex = 5))
        assertTrue(c.isWindowActive(SUNDAY, 3600L, prevDayIndex = 6))
        assertFalse(c.isWindowActive(SUNDAY, 2 * 3600L, prevDayIndex = 6))
    }

    // ── secondsUntilClose ────────────────────────────────────────────────────

    @Test fun `secondsUntilClose within a same-day window`() {
        val c = cfg(day(MONDAY), 9, 0, 0, 3600L)
        assertEquals(1800L, c.secondsUntilClose(9 * 3600L + 1800L))
    }

    @Test fun `secondsUntilClose after a crossing window rolls past midnight`() {
        val c = cfg(day(MONDAY), 23, 0, 0, 3 * 3600L)
        // At 01:00 on the following day, 1 hour remains until 02:00.
        assertEquals(3600L, c.secondsUntilClose(1 * 3600L))
    }

    @Test fun `secondsUntilClose never returns negative`() {
        val c = cfg(day(MONDAY), 9, 0, 0, 3600L)
        assertEquals(0L, c.secondsUntilClose(23 * 3600L))
    }

    /**
     * KNOWN GAP (locked in deliberately): the KDoc on secondsUntilClose says
     * "0 if not active", but the method never checks isConfigured or window
     * membership - it does pure arithmetic. An unconfigured task therefore
     * reports a nonzero countdown. Callers must gate on isWindowActive first.
     */
    @Test fun `KNOWN GAP secondsUntilClose ignores configuration`() {
        val unconfigured = cfg(days = 0, hour = 9, timeout = 3600L)
        assertEquals(1800L, unconfigured.secondsUntilClose(9 * 3600L + 1800L))
    }
}
