package com.eevdf.capabilities.sound

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

/**
 * Fully isolated: zero dependency on `vibration`, `alarm-ringer`,
 * `task-list-screen`, or `settings-screens`. Every trigger arrives as a
 * topic (see [subscribes]) and every real action it takes is itself
 * published as a topic (see [publishes]) — nothing here is reached by a
 * direct import from anywhere, and this capability never imports anything
 * outside `kernel` and `settings-storage` (for the pure prefs-key constants
 * it shares with settings-screens — see `SoundPrefs`'s own KDoc for why
 * that's data-sharing, not a behavior dependency).
 */
object SoundManifest : CapabilityManifest {
    override val capabilityId = "sound"
    override val publishes: Set<Topic<*>> = setOf(Topics.SOUND_STARTED, Topics.SOUND_STOPPED)
    override val subscribes: Set<Topic<*>> = setOf(
        Topics.ALARM_RINGING,
        Topics.ALARM_STOPPED,
        Topics.SOUND_CUE_REQUESTED,
    )

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // If unavailable: no alarm sound, no execute/wait cue sound plays.
        // The visual alarm (notification, full-screen intent, AlarmActivity)
        // and vibration are both independent of this capability — neither
        // depends on sound also being available.
    }
}
