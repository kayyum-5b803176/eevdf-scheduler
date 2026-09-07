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
 * RESOLVED for the two topics this manifest declares (was flagged since
 * Phase 1): `SUBSCRIBES = [ALARM_RINGING, ALARM_STOPPED]` above is now real.
 * `AlarmForegroundService` no longer calls `SoundManager`/`VibrationManager`
 * directly to start the alarm sound — it only publishes `Topics.ALARM_RINGING`,
 * and [AlarmCueHandler] in this capability is what actually starts/stops
 * playback in response. See [AlarmCueHandler]'s KDoc for why the stop side
 * additionally keeps a direct fallback call in alarm-ringer itself.
 *
 * STILL DIRECT, OUT OF SCOPE FOR THIS PAIR OF TOPICS: task-list-screen's
 * NOTIFICATION-task action sounds (`notice-state-machine.kt`'s
 * playExecuteSound/playWaitSound) and settings-screens' sound/vibration
 * preview screens both call `SoundManager`/`VibrationManager` directly too —
 * neither is an alarm ringing/stopping, so neither is what
 * `ALARM_RINGING`/`ALARM_STOPPED` cover. Whether those deserve their own
 * topics is a separate design question, not something implied by this pair
 * being wired now.
 */
