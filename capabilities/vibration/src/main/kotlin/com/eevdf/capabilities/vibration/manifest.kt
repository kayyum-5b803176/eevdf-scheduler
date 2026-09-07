package com.eevdf.capabilities.vibration

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

/**
 * Fully isolated: zero dependency on `sound`, `alarm-ringer`,
 * `task-list-screen`, or `settings-screens`. Every trigger arrives as a
 * topic (see [subscribes]) and every real action it takes is itself
 * published as a topic (see [publishes]) — nothing here is reached by a
 * direct import from anywhere, and this capability never imports anything
 * outside `kernel` and `settings-storage` (for the pure prefs-key constants
 * and pattern list it shares with settings-screens — see
 * `VibrationPrefs`'s own KDoc for why that's data-sharing, not a behavior
 * dependency).
 */
object VibrationManifest : CapabilityManifest {
    override val capabilityId = "vibration"
    override val publishes: Set<Topic<*>> = setOf(Topics.VIBRATION_STARTED, Topics.VIBRATION_STOPPED)
    override val subscribes: Set<Topic<*>> = setOf(
        Topics.ALARM_RINGING,
        Topics.ALARM_STOPPED,
        Topics.VIBRATION_PREVIEW_REQUESTED,
    )

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // If unavailable: no alarm vibration, no pattern preview plays. The
        // visual alarm and sound are both independent of this capability —
        // neither depends on vibration also being available.
    }
}
