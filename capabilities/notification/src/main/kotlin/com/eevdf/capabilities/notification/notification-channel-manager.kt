package com.eevdf.capabilities.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Generic channel creation — no alarm/sound/vibration awareness. The caller
 * decides importance, sound behavior (via `setSound(null, null)` if they
 * want it silent at the channel level — sound/vibration playback is owned
 * entirely by the `sound`/`vibration` capabilities, never this one, so a
 * channel created here never carries its own sound/vibration).
 */
object NotificationChannelManager {

    fun ensureChannel(
        context: Context,
        channelId: String,
        name: String,
        importance: Int,
        description: String? = null,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) != null) return
        val channel = NotificationChannel(channelId, name, importance).apply {
            description?.let { this.description = it }
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }
}
