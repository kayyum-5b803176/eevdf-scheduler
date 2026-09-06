package com.eevdf.kernel.eventbus

import com.eevdf.kernel.supervisor.HealthMonitor
import com.eevdf.kernel.supervisor.Supervisor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventBusTest {

    private val timerExpired = Topic<String>("test.timer.expired")

    @Test
    fun `publish delivers to every subscriber of the topic`() = runTest {
        val supervisor = Supervisor()
        val bus = EventBus(supervisor)
        val received = mutableListOf<String>()

        bus.subscribe(timerExpired, "alarm-ringer") { payload -> received += "alarm:$payload" }
        bus.subscribe(timerExpired, "reminder-notifier") { payload -> received += "notifier:$payload" }

        bus.publish(timerExpired, "task-42")

        assertEquals(setOf("alarm:task-42", "notifier:task-42"), received.toSet())
    }

    @Test
    fun `a subscriber that throws does not block delivery to another subscriber`() = runTest {
        val supervisor = Supervisor()
        val bus = EventBus(supervisor)
        var otherRan = false

        bus.subscribe(timerExpired, "flaky-capability") { throw RuntimeException("boom") }
        bus.subscribe(timerExpired, "healthy-capability") { otherRan = true }

        bus.publish(timerExpired, "task-1")

        assertTrue(otherRan)
    }

    @Test
    fun `an unavailable capability is skipped, not dispatched to`() = runTest {
        val healthMonitor = HealthMonitor(quarantineThreshold = 1)
        val supervisor = Supervisor(healthMonitor)
        val bus = EventBus(supervisor)
        var wasCalled = false

        healthMonitor.markUnavailable("quarantined-capability")
        bus.subscribe(timerExpired, "quarantined-capability") { wasCalled = true }

        bus.publish(timerExpired, "task-1")

        assertFalse(wasCalled)
    }

    @Test
    fun `unsubscribeAll removes every subscription owned by that capability`() = runTest {
        val supervisor = Supervisor()
        val bus = EventBus(supervisor)
        var wasCalled = false

        bus.subscribe(timerExpired, "detachable-capability") { wasCalled = true }
        bus.unsubscribeAll("detachable-capability")

        bus.publish(timerExpired, "task-1")

        assertFalse(wasCalled)
    }

    @Test
    fun `repeated failures from one subscriber eventually quarantine only that subscriber`() = runTest {
        val healthMonitor = HealthMonitor(quarantineThreshold = 2)
        val supervisor = Supervisor(healthMonitor)
        val bus = EventBus(supervisor)
        var healthyCallCount = 0

        bus.subscribe(timerExpired, "flaky-capability") { throw RuntimeException("boom") }
        bus.subscribe(timerExpired, "healthy-capability") { healthyCallCount++ }

        repeat(3) { bus.publish(timerExpired, "task-1") }

        assertFalse(supervisor.isAvailable("flaky-capability"))
        assertTrue(supervisor.isAvailable("healthy-capability"))
        assertEquals(3, healthyCallCount)
    }
}
