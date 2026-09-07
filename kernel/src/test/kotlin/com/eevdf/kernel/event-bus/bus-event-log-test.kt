package com.eevdf.kernel.eventbus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusEventLogTest {

    @Test
    fun `events is empty before anything is recorded`() {
        val log = BusEventLog()
        assertTrue(log.events.value.isEmpty())
    }

    @Test
    fun `record prepends newest first`() {
        val log = BusEventLog()
        log.record("topic.a", "first")
        log.record("topic.b", "second")

        val values = log.events.value
        assertEquals(2, values.size)
        assertEquals("topic.b", values[0].topicName)
        assertEquals("topic.a", values[1].topicName)
    }

    @Test
    fun `record trims to capacity, dropping the oldest`() {
        val log = BusEventLog(capacity = 3)
        repeat(5) { i -> log.record("topic.$i", "payload-$i") }

        val values = log.events.value
        assertEquals(3, values.size)
        // Newest first: topic.4, topic.3, topic.2 — topic.0 and topic.1 dropped.
        assertEquals(listOf("topic.4", "topic.3", "topic.2"), values.map { it.topicName })
    }

    @Test
    fun `each record gets a unique, increasing id`() {
        val log = BusEventLog()
        log.record("topic.a", "x")
        log.record("topic.b", "y")

        val values = log.events.value
        assertTrue(values[1].id < values[0].id)
    }

    @Test
    fun `clear empties the log`() {
        val log = BusEventLog()
        log.record("topic.a", "x")
        log.record("topic.b", "y")

        log.clear()

        assertTrue(log.events.value.isEmpty())
    }

    @Test
    fun `clear does not stop future recording`() {
        val log = BusEventLog()
        log.record("topic.a", "x")
        log.clear()
        log.record("topic.b", "y")

        assertEquals(listOf("topic.b"), log.events.value.map { it.topicName })
    }
}
