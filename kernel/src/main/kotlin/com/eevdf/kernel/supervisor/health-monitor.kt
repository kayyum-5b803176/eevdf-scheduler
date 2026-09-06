package com.eevdf.kernel.supervisor

/** Thrown internally to represent a capability that hung past its timeout. */
class CapabilityHangException(capabilityId: String) :
    Exception("capability '$capabilityId' hung past its timeout")

enum class CapabilityHealth { AVAILABLE, UNAVAILABLE }

/**
 * Tracks per-capability failure/hang counts and derives live health state.
 * This is the structural answer to "which capability is broken right now" —
 * a queryable snapshot instead of grepping logs (design doc §16).
 */
class HealthMonitor(private val quarantineThreshold: Int = 3) {

    data class Status(
        val health: CapabilityHealth,
        val consecutiveFailures: Int,
        val lastError: Throwable? = null,
    )

    private val status = mutableMapOf<String, Status>()

    fun statusOf(capabilityId: String): Status =
        status[capabilityId] ?: Status(CapabilityHealth.AVAILABLE, 0)

    fun isAvailable(capabilityId: String): Boolean =
        statusOf(capabilityId).health == CapabilityHealth.AVAILABLE

    /** Resets the failure count and marks the capability available again. */
    fun recordSuccess(capabilityId: String) {
        status[capabilityId] = Status(CapabilityHealth.AVAILABLE, 0)
    }

    /**
     * Records one failure. After [quarantineThreshold] *consecutive*
     * failures the capability is marked unavailable — a single blip doesn't
     * quarantine a capability, but a genuine crash loop does (design doc
     * §16's "circuit breaker" behavior).
     */
    fun recordFailure(capabilityId: String, error: Throwable) {
        val prev = statusOf(capabilityId)
        val failures = prev.consecutiveFailures + 1
        val health =
            if (failures >= quarantineThreshold) CapabilityHealth.UNAVAILABLE else prev.health
        status[capabilityId] = Status(health, failures, error)
    }

    fun recordHang(capabilityId: String) =
        recordFailure(capabilityId, CapabilityHangException(capabilityId))

    /** Forces a capability unavailable immediately, bypassing the failure-count ramp. */
    fun markUnavailable(capabilityId: String) {
        status[capabilityId] = statusOf(capabilityId).copy(health = CapabilityHealth.UNAVAILABLE)
    }

    /** Live status snapshot for every capability seen so far. */
    fun snapshot(): Map<String, Status> = status.toMap()
}
