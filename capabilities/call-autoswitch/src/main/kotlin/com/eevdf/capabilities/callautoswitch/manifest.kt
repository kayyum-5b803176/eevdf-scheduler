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

/**
 * RESOLVED — `OVERLAY_SHOWN` (was dead) is now published for real, from
 * `BubbleOverlayService.showBubble()`, exactly once per genuine show
 * transition (the method's existing `bubbleView?.isAttachedToWindow == true`
 * early return already prevented re-showing an already-visible bubble, so it
 * also naturally prevents re-publishing on every poll tick). Payload is the
 * call task's id, read from `AutoSwitchPrefs.getCallTaskId`. Consumed by
 * task-list-screen's `TaskViewModel._overlayShownTaskId` — see that
 * capability's `subscribeToOverlayShown` for why the corresponding "hidden"
 * transition is inferred from `PHONE_CALL_STATE_CHANGED` ENDED rather than a
 * second topic.
 */
