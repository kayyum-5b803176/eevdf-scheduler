package com.eevdf.capabilities.navigationroutes

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic

object NavigationRoutesManifest : CapabilityManifest {
    override val capabilityId = "navigation-routes"
    override val publishes: Set<Topic<*>> = emptySet()
    override val subscribes: Set<Topic<*>> = emptySet()

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // No-op: this capability is a table of Activity class-name strings
        // resolved at Intent time. It holds no state and does no work, so
        // there is nothing to be unavailable.
    }
}
