package com.eevdf.capabilities.statsscreens

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object StatsScreensManifest : CapabilityManifest {
    override val capabilityId = "stats-screens"
    override val publishes: Set<Topic<*>> = emptySet()
    override val subscribes: Set<Topic<*>> = emptySet()

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // No-op: a read-only view over run-history and task-storage, reached
        // by Intent. Nothing depends on it, and it holds no state of its own.
    }
}
