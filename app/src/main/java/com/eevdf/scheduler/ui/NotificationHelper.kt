package com.eevdf.scheduler.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.eevdf.scheduler.R

object NotificationHelper {

    private const val CHANNEL_ID = "eevdf_timer_channel"
    private const val CHANNEL_NAME = "EEVDF Task Timer"
    private const val NOTIFICATION_ID_TIMER = 1001
    private const val NOTIFICATION_ID_DONE = 1002

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for EEVDF task timer events"
                enableVibration(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun showTimerRunning(context: Context, taskName: String, remainingSeconds: Long) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val h = remainingSeconds / 3600
        val m = (remainingSeconds % 3600) / 60
        val s = remainingSeconds % 60
        val timeStr = if (h > 0) "$h:${"%02d".format(m)}:${"%02d".format(s)}"
        else "${"%02d".format(m)}:${"%02d".format(s)}"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("⏱ $taskName")
            .setContentText("Time remaining: $timeStr")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_TIMER, notification)
        } catch (e: SecurityException) {
            // Permission not granted
        }
    }

    fun showTimerDone(context: Context, taskName: String, nextTaskName: String?) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (nextTaskName != null)
            "Next up: $nextTaskName"
        else
            "All tasks completed! Great work!"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.checkbox_on_background)
            .setContentTitle("✅ Time slice done: $taskName")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_DONE, notification)
        } catch (e: SecurityException) {
            // Permission not granted
        }
    }

    fun cancelTimer(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_TIMER)
    }
}
