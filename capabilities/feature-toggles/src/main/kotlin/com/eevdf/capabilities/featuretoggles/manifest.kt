package com.eevdf.capabilities.featuretoggles

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic

object FeatureTogglesManifest : CapabilityManifest {
    override val capabilityId = "feature-toggles"
    override val publishes: Set<Topic<*>> = emptySet()
    override val subscribes: Set<Topic<*>> = emptySet()

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // No-op: every flag accessor already returns its declared default when
        // the backing store cannot be read, so an unavailable feature-toggles
        // degrades to "all flags at their defaults" rather than failing.
    }
}
