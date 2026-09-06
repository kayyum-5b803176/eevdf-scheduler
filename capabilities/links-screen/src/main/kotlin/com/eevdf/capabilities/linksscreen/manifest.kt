package com.eevdf.capabilities.linksscreen

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object LinksScreenManifest : CapabilityManifest {
    override val capabilityId = "links-screen"
    override val publishes: Set<Topic<*>> = emptySet()
    override val subscribes: Set<Topic<*>> = emptySet()

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // No-op: reached by Intent via navigation-routes. Reads and writes
        // links through task-storage's repository directly (it no longer
        // shares task-list-screen's ViewModel — see Phase 6).
    }
}
