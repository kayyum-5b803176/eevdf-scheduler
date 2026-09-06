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
 * FLAGGED, not yet resolved (same debt class as feedback-cues, Phase 1):
 * reached by direct import from 9 call sites in `feature/task/list`,
 * `feature/alarm`, `feature/settings`, and `:app` today — not exclusively
 * through the bus. `alarm-ringer` (Phase 5, blocked on settings-screens) and
 * `countdown-timer` (Phase 4, blocked on task-list-screen) are the two that
 * actually need to publish `Topics.ALARM_RINGING`/`ALARM_STOPPED` for this
 * capability's SUBSCRIBES above to mean anything on the live bus; until then
 * this manifest documents the intended shape, not the current call graph.
 */
