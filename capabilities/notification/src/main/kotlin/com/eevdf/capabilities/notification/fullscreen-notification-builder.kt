package com.eevdf.capabilities.notification

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * Builds a full-screen-intent [Notification] from a [FullScreenSpec]. Pure —
 * never posts it. Always PRIORITY_MAX / CATEGORY_ALARM / VISIBILITY_PUBLIC:
 * the three settings the platform actually requires for a full-screen intent
 * to have a chance of firing while locked — not configurable per spec, since
 * a caller asking for this builder specifically wants that platform behavior
 * (a caller that doesn't should use [BannerNotificationBuilder] instead).
 */
object FullScreenNotificationBuilder {
    fun build(context: Context, spec: FullScreenSpec): Notification =
        NotificationCompat.Builder(context, spec.channelId)
            .setSmallIcon(spec.iconRes)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setContentIntent(spec.contentIntent)
            .setFullScreenIntent(spec.fullScreenIntent, true)
            .setOngoing(spec.ongoing)
            .setSilent(spec.silent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
}
