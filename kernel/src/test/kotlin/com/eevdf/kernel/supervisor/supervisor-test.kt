package com.eevdf.kernel.supervisor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorTest {

    @Test
    fun `restart brings a quarantined capability back online`() {
        val supervisor = Supervisor(HealthMonitor(quarantineThreshold = 1))
        supervisor.recordFailure("call-autoswitch", RuntimeException("boom"))
        assertFalse(supervisor.isAvailable("call-autoswitch"))

        supervisor.restart("call-autoswitch")

        assertTrue(supervisor.isAvailable("call-autoswitch"))
    }

    @Test
    fun `restarting a never-quarantined capability is a harmless no-op`() {
        val supervisor = Supervisor()
        supervisor.restart("task-storage")
        assertTrue(supervisor.isAvailable("task-storage"))
    }

    @Test
    fun `status exposes a live snapshot across capabilities`() {
        val supervisor = Supervisor(HealthMonitor(quarantineThreshold = 1))
        supervisor.recordFailure("alarm-ringer", RuntimeException("boom"))
        supervisor.recordSuccess("task-storage")

        val status = supervisor.status()

        assertFalse(status.getValue("alarm-ringer").health == CapabilityHealth.AVAILABLE)
        assertTrue(status.getValue("task-storage").health == CapabilityHealth.AVAILABLE)
    }
}
