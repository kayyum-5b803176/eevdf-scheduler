package com.eevdf.kernel.crashguard

import com.eevdf.kernel.supervisor.CapabilityHealth
import com.eevdf.kernel.supervisor.Supervisor
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunIsolatedTest {

    @Test
    fun `a successful block records success and returns normally`() = runTest {
        val supervisor = Supervisor()
        var ran = false

        runIsolated("task-storage", supervisor) { ran = true }

        assertTrue(ran)
        assertTrue(supervisor.isAvailable("task-storage"))
    }

    @Test
    fun `a thrown exception is contained and reported, never propagated`() = runTest {
        val supervisor = Supervisor()

        runIsolated("reminder-notifier", supervisor) { throw RuntimeException("boom") }

        // No exception escaped this test — that's the assertion.
        val status = supervisor.status().getValue("reminder-notifier")
        assertEquals(CapabilityHealth.AVAILABLE, status.health) // single failure, not yet quarantined
        assertEquals(1, status.consecutiveFailures)
    }

    @Test
    fun `a hang past the timeout is recorded, not propagated`() = runTest {
        val supervisor = Supervisor()

        runIsolated("alarm-ringer", supervisor, timeoutMillis = 10) {
            delay(10_000)
        }

        assertEquals(1, supervisor.status().getValue("alarm-ringer").consecutiveFailures)
    }

    @Test
    fun `one capability's crash does not affect another's recorded health`() = runTest {
        val supervisor = Supervisor()

        runIsolated("feedback-cues", supervisor) { throw RuntimeException("boom") }
        runIsolated("task-list-screen", supervisor) { /* succeeds */ }

        assertTrue(supervisor.isAvailable("task-list-screen"))
    }
}
