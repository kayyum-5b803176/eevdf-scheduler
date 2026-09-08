package com.eevdf.capabilities.notification

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics

/**
 * This capability's ONLY bus wiring — mirrors `sound`'s `SoundCueHandler`/
 * `vibration`'s `VibrationCueHandler` exactly: subscribe to a request topic,
 * do the one real action, publish that it happened. This is what gives
 * `notification` the same event-log visibility `sound`/`vibration` already
 * had — every cancel now shows up as a `notification.cancel-requested` /
 * `notification.cancelled` pair, not silently.
 *
 * Building/posting notifications is deliberately NOT wired here — see
 * `notification-specs.kt`'s KDoc for why an Android-typed request/response
 * (a `Notification` object crossing the bus) isn't architecturally possible,
 * and this capability's manifest.kt for why building is a plain direct call
 * to [BannerNotificationBuilder]/[FullScreenNotificationBuilder] instead.
 */
class NotificationCancelHandler(
    private val appContext: Context,
    private val bus: EventBus,
) {
    init {
        bus.subscribe(Topics.NOTIFICATION_CANCEL_REQUESTED, CAPABILITY_ID) { notificationId ->
            NotificationManagerCompat.from(appContext).cancel(notificationId)
            bus.publish(Topics.NOTIFICATION_CANCELLED, notificationId, CAPABILITY_ID)
        }
    }

    private companion object {
        const val CAPABILITY_ID = "notification"
    }
}
