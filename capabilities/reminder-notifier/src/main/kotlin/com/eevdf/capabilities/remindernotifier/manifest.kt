package com.eevdf.capabilities.remindernotifier

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object ReminderNotifierManifest : CapabilityManifest {
    override val capabilityId = "reminder-notifier"
    override val publishes: Set<Topic<*>> = emptySet()
    override val subscribes = setOf(Topics.ALARM_RINGING, Topics.ALARM_STOPPED)

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // No notification posted/cleared for this alarm event. The alarm's
        // own foreground service/activity (alarm-ringer) still rings and
        // displays independently — reminder-notifier is a secondary channel,
        // not the alarm's only signal to the user.
    }
}

/**
 * RESOLVED (was flagged, same debt class as feedback-cues, Phase 1):
 * `SUBSCRIBES = [ALARM_RINGING, ALARM_STOPPED]` above is now backed by real
 * `bus.subscribe()` calls — see [AlarmDeliveryHandler]. `alarm-ringer`
 * publishes both (`AlarmForegroundService` for ringing,
 * `AlarmStopReceiver` for stopped), so this manifest's declared shape now
 * matches the live call graph, not just the intended one.
 */
