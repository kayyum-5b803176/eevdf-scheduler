package com.eevdf.kernel.supervisor

/**
 * The kernel's one authority on "is this capability allowed to receive bus
 * dispatches right now, and can it be brought back." [EventBus][com.eevdf
 * .kernel.eventbus.EventBus] consults [isAvailable] before every dispatch;
 * [com.eevdf.kernel.crashguard.runIsolated] reports outcomes back here.
 */
class Supervisor(private val healthMonitor: HealthMonitor = HealthMonitor()) {

    fun isAvailable(capabilityId: String): Boolean = healthMonitor.isAvailable(capabilityId)

    fun recordSuccess(capabilityId: String) = healthMonitor.recordSuccess(capabilityId)

    fun recordFailure(capabilityId: String, error: Throwable) =
        healthMonitor.recordFailure(capabilityId, error)

    fun recordHang(capabilityId: String) = healthMonitor.recordHang(capabilityId)

    /**
     * Brings a quarantined capability back online — e.g. after a cooldown
     * period, or a manual admin/debug action. Resets its failure count; the
     * bus will resume routing to it on the next publish. Restarting a
     * capability that was never quarantined is a harmless no-op.
     */
    fun restart(capabilityId: String) = healthMonitor.recordSuccess(capabilityId)

    /** Live status of every capability the supervisor has seen so far. */
    fun status(): Map<String, HealthMonitor.Status> = healthMonitor.snapshot()
}
