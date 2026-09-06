package com.eevdf.kernel.crashguard

import com.eevdf.kernel.supervisor.CapabilityHangException
import com.eevdf.kernel.supervisor.Supervisor
import kotlinx.coroutines.withTimeoutOrNull

/** Default time a capability gets to handle one dispatch before it's considered hung. */
const val DEFAULT_HANG_TIMEOUT_MILLIS = 5_000L

/**
 * Runs [block] in isolation on behalf of [capabilityId] (rule 5): an
 * exception or a hang inside [block] is caught here, unconditionally, and
 * reported to [supervisor] — it never propagates to the kernel or to any
 * other capability. This is a *mechanism*, not a policy every capability
 * author must remember to apply (design doc §16) — every bus dispatch goes
 * through this function, with no opt-out.
 *
 * JVM-fatal conditions ([VirtualMachineError], [LinkageError],
 * [ThreadDeath]) are deliberately NOT contained — those are never a single
 * capability's problem to isolate.
 */
suspend fun runIsolated(
    capabilityId: String,
    supervisor: Supervisor,
    timeoutMillis: Long = DEFAULT_HANG_TIMEOUT_MILLIS,
    block: suspend () -> Unit,
) {
    try {
        val completed = withTimeoutOrNull(timeoutMillis) {
            block()
            true
        }
        if (completed == null) {
            CrashIsolation.report(capabilityId, CapabilityHangException(capabilityId))
            supervisor.recordHang(capabilityId)
        } else {
            supervisor.recordSuccess(capabilityId)
        }
    } catch (e: Throwable) {
        if (e is VirtualMachineError || e is LinkageError || e is ThreadDeath) throw e
        CrashIsolation.report(capabilityId, e)
        supervisor.recordFailure(capabilityId, e)
    }
}
