package com.eevdf.capabilities.notification

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat

/** Builds a standard banner-style [Notification] from a [BannerSpec]. Pure — never posts it. */
object BannerNotificationBuilder {
    fun build(context: Context, spec: BannerSpec): Notification =
        NotificationCompat.Builder(context, spec.channelId)
            .setSmallIcon(spec.iconRes)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setContentIntent(spec.contentIntent)
            .setPriority(spec.priority)
            .setAutoCancel(spec.autoCancel)
            .setOngoing(spec.ongoing)
            .setSilent(spec.silent)
            .build()
}
