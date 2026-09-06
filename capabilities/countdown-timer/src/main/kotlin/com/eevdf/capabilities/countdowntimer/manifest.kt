package com.eevdf.capabilities.countdowntimer

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object CountdownTimerManifest : CapabilityManifest {
    override val capabilityId = "countdown-timer"
    override val publishes: Set<Topic<*>> = setOf(Topics.TIMER_EXPIRED)
    override val subscribes: Set<Topic<*>> = setOf(Topics.BACKUP_EXPORT_REQUESTED, Topics.BACKUP_IMPORT_REQUESTED)

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // A missed timer.expired means this task's alarm does not fire from
        // the in-process path. alarm-ringer also has an AlarmManager-backed
        // path scheduled at start time, which is the doze-immune one and is
        // unaffected by this capability being unavailable — so the alarm
        // still rings; only the immediate in-app transition is skipped.
    }
}
