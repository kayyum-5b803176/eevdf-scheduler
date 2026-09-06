package com.eevdf.capabilities.grouppicker

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic

object GroupPickerManifest : CapabilityManifest {
    override val capabilityId = "group-picker"
    override val publishes: Set<Topic<*>> = emptySet()
    override val subscribes: Set<Topic<*>> = emptySet()

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // No-op: group-picker is a modal dialog invoked directly by its two
        // callers (add-task-screen, links-screen), not a bus participant.
        // If unavailable, those callers simply cannot open the picker; their
        // own screens continue to function.
    }
}
