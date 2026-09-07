package com.eevdf.capabilities.notification

import android.content.Context
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics

/**
 * Wires this capability's declared `alarm.ringing`/`alarm.stopped`
 * subscriptions (manifest.kt) to real calls.
 *
 * `alarm.stopped` -> [NotificationHelper.cancelExpired]. RESOLVED: this is
 * now the ONLY caller of `cancelExpired` — `AlarmStopReceiver` (alarm-ringer)
 * used to call it directly too, as a "guaranteed" path, because the
 * `Topics.ALARM_STOPPED` publish only fired conditionally (a non-null
 * ringing task name) and asynchronously (fire-and-forget). Both of those
 * are fixed at the publish site now (unconditional payload, blocking
 * publish bounded by the same `runIsolated` timeout every dispatch has —
 * see `AlarmStopReceiver`'s own KDoc), so this bus subscription alone gives
 * the same guarantee the direct call used to, and alarm-ringer has zero
 * import of this capability for any action any more.
 *
 * `alarm.ringing` -> [AlarmDeliveryLog.recordRinging]. There's no existing
 * "post a reminder notification" action in this capability to hook up here —
 * the expired-alarm notification itself is built and posted by
 * `AlarmForegroundService` directly (`showExpiredNotification`), not through
 * notification. What this capability's own [AlarmReliabilityChecker]
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
        const val CAPABILITY_ID = "notification"
    }
}
