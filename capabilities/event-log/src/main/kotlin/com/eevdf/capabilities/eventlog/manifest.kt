package com.eevdf.capabilities.eventlog

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic

object EventLogManifest : CapabilityManifest {
    override val capabilityId = "event-log"
    override val publishes: Set<Topic<*>> = emptySet()
    override val subscribes: Set<Topic<*>> = emptySet()

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // No-op: a read-only diagnostic view over EventBus.log, reached by
        // Intent from the overflow menu. Nothing depends on it, and it holds
        // no state of its own — EventBus.log's BusEventLog keeps recording
        // regardless of whether this screen is ever opened.
    }
}

/**
 * WHY THIS ISN'T A BUS PARTICIPANT (design note)
 * ------------------------------------------------
 * This screen reads `EventBus.log` directly, a kernel-owned diagnostic feed
 * of every `publish()` call — NOT a `Topic` subscription. Rule 3 ("the only
 * channel capabilities may use to reach each other") governs
 * capability-to-capability communication; introspecting the bus's own
 * activity is neither that nor a new exception to it, the same way a screen
 * reading `Supervisor.isAvailable(...)` would be reading kernel state, not
 * talking to another capability. See `BusEventLog`'s KDoc in kernel/event-bus.
 */
