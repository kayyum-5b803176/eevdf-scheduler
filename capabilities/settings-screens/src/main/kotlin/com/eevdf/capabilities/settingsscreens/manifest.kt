package com.eevdf.capabilities.settingsscreens

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object SettingsScreensManifest : CapabilityManifest {
    override val capabilityId = "settings-screens"
    override val publishes: Set<Topic<*>> = emptySet()
    override val subscribes: Set<Topic<*>> = emptySet()

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // No-op: settings-screens is UI over settings-storage. It reads and
        // writes preferences through that capability directly; it neither
        // publishes nor subscribes. If unavailable the user cannot open the
        // settings UI, but every already-saved preference still applies,
        // because the values live in settings-storage, not here.
    }
}
