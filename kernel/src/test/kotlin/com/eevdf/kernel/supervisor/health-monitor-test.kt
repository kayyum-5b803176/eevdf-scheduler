package com.eevdf.kernel.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthMonitorTest {

    @Test
    fun `unknown capability is available by default`() {
        val monitor = HealthMonitor()
        assertTrue(monitor.isAvailable("never-seen"))
    }

    @Test
    fun `a single failure does not quarantine`() {
        val monitor = HealthMonitor(quarantineThreshold = 3)
        monitor.recordFailure("alarm-ringer", RuntimeException("boom"))
        assertTrue(monitor.isAvailable("alarm-ringer"))
        assertEquals(1, monitor.statusOf("alarm-ringer").consecutiveFailures)
    }

    @Test
    fun `reaching the quarantine threshold marks unavailable`() {
        val monitor = HealthMonitor(quarantineThreshold = 3)
        repeat(3) { monitor.recordFailure("alarm-ringer", RuntimeException("boom")) }
        assertFalse(monitor.isAvailable("alarm-ringer"))
    }

    @Test
    fun `a success resets the failure count and restores availability`() {
        val monitor = HealthMonitor(quarantineThreshold = 3)
        repeat(3) { monitor.recordFailure("alarm-ringer", RuntimeException("boom")) }
        assertFalse(monitor.isAvailable("alarm-ringer"))

        monitor.recordSuccess("alarm-ringer")

        assertTrue(monitor.isAvailable("alarm-ringer"))
        assertEquals(0, monitor.statusOf("alarm-ringer").consecutiveFailures)
    }

    @Test
    fun `a hang is recorded as a failure with a hang-specific error`() {
        val monitor = HealthMonitor(quarantineThreshold = 1)
        monitor.recordHang("reminder-notifier")
        assertFalse(monitor.isAvailable("reminder-notifier"))
        assertTrue(monitor.statusOf("reminder-notifier").lastError is CapabilityHangException)
    }

    @Test
    fun `markUnavailable bypasses the failure-count ramp immediately`() {
        val monitor = HealthMonitor(quarantineThreshold = 100)
        monitor.markUnavailable("feedback-cues")
        assertFalse(monitor.isAvailable("feedback-cues"))
    }

    @Test
    fun `snapshot reflects every capability seen so far`() {
        val monitor = HealthMonitor()
        monitor.recordFailure("a", RuntimeException())
        monitor.recordSuccess("b")
        val snapshot = monitor.snapshot()
        assertEquals(setOf("a", "b"), snapshot.keys)
    }
}
