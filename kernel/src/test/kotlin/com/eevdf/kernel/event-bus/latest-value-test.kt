package com.eevdf.kernel.eventbus

import com.eevdf.kernel.supervisor.Supervisor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestValueTest {

    @Test
    fun `starts at its initial value`() {
        val cache = LatestValue(TimerRunningState())
        assertEquals(TimerRunningState(), cache.value)
    }

    @Test
    fun `tracks published values for the topic it subscribes to`() = runTest {
        val bus = EventBus(Supervisor())
        val cache = LatestValue(TimerRunningState()).trackedOn(
            bus, Topics.TIMER_RUNNING_CHANGED, "call-autoswitch",
        )

        bus.publish(Topics.TIMER_RUNNING_CHANGED, TimerRunningState(anyTimerRunning = true))

        assertTrue(cache.value.anyTimerRunning)
    }

    @Test
    fun `setAndPublish updates the local copy synchronously before dispatching`() = runTest {
        val bus = EventBus(Supervisor())
        val publisher = LatestValue(TimerRunningState())
        val subscriber = LatestValue(TimerRunningState()).trackedOn(
            bus, Topics.TIMER_RUNNING_CHANGED, "call-autoswitch",
        )

        publisher.setAndPublish(
            bus, Topics.TIMER_RUNNING_CHANGED, TimerRunningState(callTaskRunning = true),
        )

        // Publisher sees it immediately; subscriber sees it after dispatch.
        assertTrue(publisher.value.callTaskRunning)
        assertTrue(subscriber.value.callTaskRunning)
    }

    @Test
    fun `a crashing subscriber does not stop another subscriber's cache updating`() = runTest {
        val bus = EventBus(Supervisor())
        bus.subscribe(Topics.TIMER_RUNNING_CHANGED, "flaky") { throw RuntimeException("boom") }
        val healthy = LatestValue(TimerRunningState()).trackedOn(
            bus, Topics.TIMER_RUNNING_CHANGED, "task-list-screen",
        )

        bus.publish(Topics.TIMER_RUNNING_CHANGED, TimerRunningState(timerRunning = true))

        assertTrue(healthy.value.timerRunning)
    }

    @Test
    fun `timer running state snapshot cannot be observed torn`() = runTest {
        val bus = EventBus(Supervisor())
        val cache = LatestValue(TimerRunningState()).trackedOn(
            bus, Topics.TIMER_RUNNING_CHANGED, "call-autoswitch",
        )

        val bothSet = TimerRunningState(
            anyTimerRunning = true, callTaskRunning = true, timerRunning = true,
        )
        bus.publish(Topics.TIMER_RUNNING_CHANGED, bothSet)

        // All three flags flip together — the whole point of one snapshot payload.
        assertEquals(bothSet, cache.value)
    }
}
