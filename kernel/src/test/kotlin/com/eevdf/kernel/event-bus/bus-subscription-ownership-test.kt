package com.eevdf.kernel.eventbus

import com.eevdf.kernel.supervisor.HealthMonitor
import com.eevdf.kernel.supervisor.Supervisor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who owns a subscription, and what takes it away. Written for a real bug: a screen's
 * `unsubscribeAll(<its capability id>)` on stop also removed subscriptions its ViewModel had made
 * under the same id, and the scheduled-wake icon and Select button stopped working after the first
 * time the screen stopped. These pin the contract the fix relies on.
 */
class BusSubscriptionOwnershipTest {

    private val armed  = Topic<Set<String>>("test.wake.armed-changed", retained = true)
    private val select = Topic<String>("test.scheduler.task-select-requested")

    @Test
    fun `unsubscribeAll removes only the subscriptions of that id`() = runTest {
        val bus = EventBus(Supervisor())
        val a = mutableListOf<String>(); val b = mutableListOf<String>()
        bus.subscribe(select, "owner-a") { a += it }
        bus.subscribe(select, "owner-b") { b += it }

        bus.unsubscribeAll("owner-a")
        bus.publish(select, "x", "test")

        assertEquals(emptyList<String>(), a)
        assertEquals(listOf("x"), b)
    }

    @Test
    fun `a subscription under an id of its own survives the screen tearing down its capability id`() = runTest {
        val bus = EventBus(Supervisor())
        val screen = "task-list-screen"
        val received = mutableListOf<String>()
        bus.subscribe(select, "task-list-screen.wake") { received += it }   // owned by the screen, own id
        bus.subscribe(select, screen) { received += "screen:$it" }          // registered under the screen's id

        bus.unsubscribeAll(screen)                                           // MainActivity.onStop()
        bus.publish(select, "t1", "task-wake")

        assertEquals(listOf("t1"), received)                                 // the screen-id one is gone, the own-id one is not
    }

    @Test
    fun `sharing an id shares fate - one teardown removes every subscription under it`() = runTest {
        val bus = EventBus(Supervisor())
        val received = mutableListOf<String>()
        bus.subscribe(select, "shared") { received += "activity:$it" }
        bus.subscribe(armed, "shared") { received += "viewmodel:$it" }       // the ViewModel, under the Activity's id

        bus.unsubscribeAll("shared")
        bus.publish(select, "a", "test")
        bus.publish(armed, setOf("t"), "test")

        assertTrue("the ViewModel's subscription went with the Activity's", received.isEmpty())
    }

    @Test
    fun `two instances with their own ids do not remove each other's subscriptions`() = runTest {
        // The app runs two ViewModel instances (main screen, Add screen). Clearing one must not touch the other.
        val bus = EventBus(Supervisor())
        val main = mutableListOf<String>(); val addScreen = mutableListOf<String>()
        bus.subscribe(select, "task-list-screen.vm.main") { main += it }
        bus.subscribe(select, "task-list-screen.vm.add") { addScreen += it }

        bus.unsubscribeAll("task-list-screen.vm.add")                        // the Add screen's ViewModel is cleared
        bus.publish(select, "x", "test")

        assertEquals(listOf("x"), main)
        assertEquals(emptyList<String>(), addScreen)
    }

    @Test
    fun `re-registering under the same id after unsubscribeAll leaves exactly one subscription`() = runTest {
        // What WakeLinks.start() does each time a screen is created.
        val bus = EventBus(Supervisor())
        val received = mutableListOf<String>()
        repeat(3) {
            bus.unsubscribeAll("task-list-screen.wake")
            bus.subscribe(select, "task-list-screen.wake") { received += it }
        }

        bus.publish(select, "x", "test")

        assertEquals(listOf("x"), received)
    }

    @Test
    fun `a retained topic lets a late subscriber read what it missed`() = runTest {
        val bus = EventBus(Supervisor())
        bus.publish(armed, setOf("task-1"), "task-wake")                     // armed before any screen existed
        bus.publish(armed, setOf("task-1", "task-2"), "task-wake")

        assertEquals(setOf("task-1", "task-2"), bus.getLast(armed))          // WakeLinks.start() reads this
        assertNull(bus.getLast(select))                                      // a non-retained topic reads back null
    }

    @Test
    fun `a failing subscriber is quarantined under its own id without silencing a neighbour`() = runTest {
        // Own ids also isolate failures: one id's crash loop must not switch off another's subscriptions.
        val bus = EventBus(Supervisor(HealthMonitor(quarantineThreshold = 1)))
        val received = mutableListOf<String>()
        bus.subscribe(select, "task-list-screen.other") { throw RuntimeException("boom") }
        bus.subscribe(select, "task-list-screen.wake") { received += it }

        bus.publish(select, "first", "test")
        bus.publish(select, "second", "test")

        assertEquals(listOf("first", "second"), received)
    }
}
