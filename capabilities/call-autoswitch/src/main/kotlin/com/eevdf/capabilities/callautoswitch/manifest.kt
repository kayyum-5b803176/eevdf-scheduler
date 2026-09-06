package com.eevdf.capabilities.callautoswitch

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object CallAutoswitchManifest : CapabilityManifest {
    override val capabilityId = "call-autoswitch"
    override val publishes: Set<Topic<*>> = setOf(
        Topics.OVERLAY_SHOWN,
        Topics.PHONE_CALL_STATE_CHANGED,
        Topics.TIMER_RUNNING_CHANGED,
        Topics.BUBBLE_TAPPED,
        Topics.ALARM_TIMER_START_REQUESTED,
        Topics.ALARM_TIMER_PAUSE_REQUESTED,
    )
    override val subscribes: Set<Topic<*>> = setOf(
        Topics.OVERLAY_CALL_STARTED_REQUESTED,
        Topics.OVERLAY_CALL_ENDED_REQUESTED,
        Topics.TIMER_RUNNING_CHANGED,
    )

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // If unavailable, no floating bubble appears and no automatic
        // task-switch happens on an incoming call. Nothing else degrades:
        // the user's currently running timer keeps running exactly as it
        // would have without auto-switch enabled at all, which is also what
        // happens when the user simply turns the feature off in settings.
    }
}
