package com.eevdf.kernel.clock

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTest {

    @Test
    fun `fixed clock does not advance on its own`() {
        val clock = FixedClock(1_000L)
        assertEquals(1_000L, clock.nowEpochMillis())
        assertEquals(1_000L, clock.nowEpochMillis())
    }

    @Test
    fun `fixed clock advances explicitly`() {
        val clock = FixedClock(0L)
        clock.advanceMillis(5_000L)
        assertEquals(5_000L, clock.nowEpochMillis())
        assertEquals(5L, clock.nowEpochSeconds())
    }

    @Test
    fun `fixed clock can be set directly`() {
        val clock = FixedClock()
        clock.set(42_000L)
        assertEquals(42_000L, clock.nowEpochMillis())
    }

    @Test
    fun `system clock tracks real wall time`() {
        val before = System.currentTimeMillis()
        val now = SystemClock().nowEpochMillis()
        val after = System.currentTimeMillis()
        assertTrue(now in before..after)
    }

    @Test
    fun `wall clock localizes a known instant`() {
        // 2024-01-07T12:00:00Z is a Sunday.
        val epochMillis = 1_704_628_800_000L
        val local = WallClock.localize(epochMillis, ZoneId.of("UTC"))
        assertEquals(0, local.dayOfWeekIndex) // Sunday = 0
        assertEquals(12 * 3600L, local.secondOfDay)
    }

    @Test
    fun `previous day index wraps from sunday to saturday`() {
        assertEquals(6, WallClock.previousDayIndex(0))
    }
}
