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

        bus.publish(timerExpired, "task-42", "test-publisher")

        assertEquals(setOf("alarm:task-42", "notifier:task-42"), received.toSet())
    }

    @Test
    fun `a subscriber that throws does not block delivery to another subscriber`() = runTest {
        val supervisor = Supervisor()
        val bus = EventBus(supervisor)
        var otherRan = false

        bus.subscribe(timerExpired, "flaky-capability") { throw RuntimeException("boom") }
        bus.subscribe(timerExpired, "healthy-capability") { otherRan = true }

        bus.publish(timerExpired, "task-1", "test-publisher")

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

        bus.publish(timerExpired, "task-1", "test-publisher")

        assertFalse(wasCalled)
    }

    @Test
    fun `unsubscribeAll removes every subscription owned by that capability`() = runTest {
        val supervisor = Supervisor()
        val bus = EventBus(supervisor)
        var wasCalled = false

        bus.subscribe(timerExpired, "detachable-capability") { wasCalled = true }
        bus.unsubscribeAll("detachable-capability")

        bus.publish(timerExpired, "task-1", "test-publisher")

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

        repeat(3) { bus.publish(timerExpired, "task-1", "test-publisher") }

        assertFalse(supervisor.isAvailable("flaky-capability"))
        assertTrue(supervisor.isAvailable("healthy-capability"))
        assertEquals(3, healthyCallCount)
    }

    @Test
    fun `getLast returns null until a retained topic has been published`() = runTest {
        val supervisor = Supervisor()
        val bus = EventBus(supervisor)
        val retained = Topic<String>("test.retained", retained = true)

        assertEquals(null, bus.getLast(retained))
    }

    @Test
    fun `getLast returns the most recent payload of a retained topic`() = runTest {
        val supervisor = Supervisor()
        val bus = EventBus(supervisor)
        val retained = Topic<String>("test.retained", retained = true)

        bus.publish(retained, "first", "test-publisher")
        bus.publish(retained, "second", "test-publisher")

        assertEquals("second", bus.getLast(retained))
    }

    @Test
    fun `getLast still delivers to live subscribers as normal`() = runTest {
        val supervisor = Supervisor()
        val bus = EventBus(supervisor)
        val retained = Topic<String>("test.retained", retained = true)
        var received: String? = null

        bus.subscribe(retained, "late-joiner") { payload -> received = payload }
        bus.publish(retained, "task-9", "test-publisher")

        assertEquals("task-9", received)
        assertEquals("task-9", bus.getLast(retained))
    }

    @Test
    fun `getLast returns null for a non-retained topic even after publish`() = runTest {
        val supervisor = Supervisor()
        val bus = EventBus(supervisor)

        bus.publish(timerExpired, "task-1", "test-publisher")

        assertEquals(null, bus.getLast(timerExpired))
    }

    @Test
    fun `publish records every event in the log, regardless of subscribers`() = runTest {
        val supervisor = Supervisor()
        val bus = EventBus(supervisor)

        bus.publish(timerExpired, "task-1", "test-publisher")

        val records = bus.log.value
        assertEquals(1, records.size)
        assertEquals("test.timer.expired", records[0].topicName)
        assertEquals("task-1", records[0].payload)
    }

    @Test
    fun `publish records a Unit payload as an empty string, not kotlin Unit`() = runTest {
        val supervisor = Supervisor()
        val bus = EventBus(supervisor)
        val signal = Topic<Unit>("test.signal")

        bus.publish(signal, Unit, "test-publisher")

        assertEquals("", bus.log.value.single().payload)
    }

    @Test
    fun `publish records the given publisherId`() = runTest {
        val supervisor = Supervisor()
        val bus = EventBus(supervisor)

        bus.publish(timerExpired, "task-1", "alarm-ringer")

        assertEquals("alarm-ringer", bus.log.value.single().publisherId)
    }

    @Test
    fun `request returns the responder's answer`() = runTest {
        val supervisor = Supervisor()
        val bus = EventBus(supervisor)
        val ask = RequestTopic<String, Int>("test.ask")

        bus.respondTo(ask, "answerer") { req -> req.length }

        assertEquals(5, bus.request(ask, "hello", "asker"))
    }

    @Test
    fun `request returns null when no responder is registered`() = runTest {
        val supervisor = Supervisor()
        val bus = EventBus(supervisor)
        val ask = RequestTopic<String, Int>("test.ask")

        assertEquals(null, bus.request(ask, "hello", "asker"))
    }

    @Test
    fun `request returns null when the responder is unavailable`() = runTest {
        val healthMonitor = HealthMonitor(quarantineThreshold = 1)
        val supervisor = Supervisor(healthMonitor)
        val bus = EventBus(supervisor)
        val ask = RequestTopic<String, Int>("test.ask")
        healthMonitor.markUnavailable("answerer")

        bus.respondTo(ask, "answerer") { req -> req.length }

        assertEquals(null, bus.request(ask, "hello", "asker"))
    }

    @Test
    fun `respondTo rejects a second responder for the same RequestTopic`() = runTest {
        val supervisor = Supervisor()
        val bus = EventBus(supervisor)
        val ask = RequestTopic<String, Int>("test.ask")

        bus.respondTo(ask, "first-answerer") { req -> req.length }

        try {
            bus.respondTo(ask, "second-answerer") { req -> req.length }
            org.junit.Assert.fail("expected an IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("first-answerer"))
        }
    }

    @Test
    fun `request records both the outbound request and the returned answer`() = runTest {
        val supervisor = Supervisor()
        val bus = EventBus(supervisor)
        val ask = RequestTopic<String, Int>("test.ask")
        bus.respondTo(ask, "answerer") { req -> req.length }

        bus.request(ask, "hello", "asker")

        val records = bus.log.value.sortedBy { it.id }
        assertEquals(2, records.size)
        assertEquals("asker", records[0].publisherId)
        assertEquals("→ hello", records[0].payload)
        assertEquals("answerer", records[1].publisherId)
        assertEquals("← 5", records[1].payload)
    }
}
