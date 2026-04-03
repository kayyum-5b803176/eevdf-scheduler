package com.eevdf.scheduler.ui

import android.content.Context
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {

    // These IDs are now owned by AlarmForegroundService — kept here for cancellation
    private const val NOTIFICATION_ID_EXPIRED = 3001

    fun cancelExpired(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_EXPIRED)
    }

    /** Format seconds as "0:05", "1:23", "1:02:34" — same style as Google Clock */
    fun formatElapsed(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m >= 60) {
            val h = m / 60
            val rm = m % 60
            "$h:${"%02d".format(rm)}:${"%02d".format(s)}"
        } else {
            "$m:${"%02d".format(s)}"
        }
    }
}
