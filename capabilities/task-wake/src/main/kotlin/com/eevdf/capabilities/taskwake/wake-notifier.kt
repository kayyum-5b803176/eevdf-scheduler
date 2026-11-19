package com.eevdf.capabilities.taskwake

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.eevdf.capabilities.navigationroutes.AppRoutes

/**
 * The notification that stands in for the preempt card while the app is not on screen:
 * a wake came due in the background, or a wake was missed while the phone was off.
 * Tapping it opens the app; the wake coordinator hears the app come to the foreground
 * (`app.foreground-changed`) and puts the card up there.
 */
object WakeNotifier {

    private const val CHANNEL_ID = "task_wake"

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Scheduled wake", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A task's scheduled wake came due, or was missed"
            }
        )
    }

    private fun post(context: Context, taskId: String, title: String, text: String) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, taskId.hashCode(),
            AppRoutes.main(context).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Without the notification permission this is simply not shown; it never throws.
        nm.notify(taskId.hashCode(), n)
    }

    fun postDue(context: Context, taskId: String, taskName: String) =
        post(context, taskId, "Wake: $taskName", "Open the app to preempt or dismiss")

    fun postMissed(context: Context, taskId: String, taskName: String, targetEpochMs: Long) =
        post(context, taskId, "Missed wake: $taskName", "Was due ${WakeRules.formatTarget(targetEpochMs)}")

    /** A wake that could not be honoured (its task is queued or hidden). Nothing to open it for: just say so. */
    fun postNotice(context: Context, taskId: String, title: String, text: String) =
        post(context, taskId, title, text)

    fun cancel(context: Context, taskId: String) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(taskId.hashCode())
    }
}
