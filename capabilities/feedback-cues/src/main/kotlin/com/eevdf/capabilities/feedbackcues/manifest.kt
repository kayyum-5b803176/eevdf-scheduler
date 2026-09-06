package com.eevdf.capabilities.feedbackcues

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object FeedbackCuesManifest : CapabilityManifest {
    override val capabilityId = "feedback-cues"
    override val publishes: Set<Topic<*>> = emptySet()
    override val subscribes = setOf(Topics.ALARM_RINGING, Topics.ALARM_STOPPED)

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // No sound/vibration for this alarm event. The visual alarm UI and
        // the notification (reminder-notifier) are unaffected — feedback-cues
        // is a pure enhancement layer, never load-bearing for alarm delivery.
    }
}

/**
 * NOTE (flagged, not silently fixed): `sound-manager.kt`/`vibration-manager.kt`
 * are still called via direct import from `feature/task`, `feature/alarm`, and
 * `feature/settings` (5 call sites) rather than through the bus above. That is
 * the "ports pattern" DI-style call this migration ultimately replaces with
 * `Topics.ALARM_RINGING`/`Topics.ALARM_STOPPED` events — but the callers
 * (alarm-ringer, countdown-timer, settings-screens) don't migrate until
 * Phase 3/5. This phase only relocates the package; the direct-call edge is
 * left in place and will be bus-ified when those capabilities move.
 */
