package com.eevdf.capabilities.remindernotifier

import android.content.Context
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics

/**
 * Wires this capability's declared `alarm.ringing`/`alarm.stopped`
 * subscriptions (manifest.kt) to real calls.
 *
 * `alarm.stopped` -> [NotificationHelper.cancelExpired]. `AlarmStopReceiver`
 * (alarm-ringer) already calls this directly today and KEEPS doing so — that
 * direct call is unconditional, while this bus path only fires when
 * `Topics.ALARM_STOPPED` carries a non-null ringing task name (see
 * `AlarmStopReceiver`'s own comment on why). Removing the direct call would
 * trade a guaranteed cancel for a conditional one; this handler is additive
 * coverage instead — genuinely real code behind the manifest's declared
 * subscription, and a second path for any future publisher of
 * `alarm.stopped` that isn't `AlarmStopReceiver`. Cancelling an
 * already-cancelled notification is a no-op, so the duplication is free.
 *
 * `alarm.ringing` -> [AlarmDeliveryLog.recordRinging]. There's no existing
 * "post a reminder notification" action in this capability to hook up here —
 * the expired-alarm notification itself is built and posted by
 * `AlarmForegroundService` directly (`showExpiredNotification`), not through
 * reminder-notifier. What this capability's own [AlarmReliabilityChecker]
 * KDoc already calls out as a real, motivating use for this data ("logging or
 * future adaptive behavior") is recording that a ringing event actually
 * reached the delivery layer, timestamped — the first building block for
 * answering "was the last alarm actually delivered" from the Permissions
 * screen someday, without inventing a duplicate notification post here.
 */
class AlarmDeliveryHandler(
    appContext: Context,
    bus: EventBus,
) {
    init {
        bus.subscribe(Topics.ALARM_RINGING, CAPABILITY_ID) { event ->
            AlarmDeliveryLog.recordRinging(appContext, event.taskName)
        }
        bus.subscribe(Topics.ALARM_STOPPED, CAPABILITY_ID) {
            NotificationHelper.cancelExpired(appContext)
        }
    }

    private companion object {
        const val CAPABILITY_ID = "reminder-notifier"
    }
}
