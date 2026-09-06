package com.eevdf.capabilities.designsystem

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic

object DesignSystemManifest : CapabilityManifest {
    override val capabilityId = "design-system"
    override val publishes: Set<Topic<*>> = emptySet()
    override val subscribes: Set<Topic<*>> = emptySet()

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // No-op: design-system has no bus participation. It's a compile-time
        // shared UI/asset dependency (views, tokens, colors) that other
        // capabilities import directly — the one documented exception to
        // rule 3, since this is a build-time asset relationship, not a
        // runtime behavioral call between capabilities.
    }
}
