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

    private const val CHANNEL_ID_TIMER   = "eevdf_timer_channel"
    private const val CHANNEL_ID_ALARM   = "eevdf_alarm_channel"
    private const val NOTIFICATION_ID_TIMER   = 1001
    private const val NOTIFICATION_ID_DONE    = 1002
    private const val NOTIFICATION_ID_EXPIRED = 1003

    // ── Channel setup ──────────────────────────────────────────────────────────

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Low-priority channel for the running-timer ticker
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_TIMER,
                    "EEVDF Task Timer",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows remaining time while a task timer is running"
                    enableVibration(false)
                    setSound(null, null)
                }
            )

            // High-priority channel for the expired alarm — like Google Clock
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_ALARM,
                    "EEVDF Timer Expired",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Shown when a task timer expires — requires dismissal"
                    enableVibration(true)
                    setSound(null, null) // sound is played by Ringtone in ViewModel, not here
                }
            )
        }
    }

    // ── Running timer notification ─────────────────────────────────────────────

    fun showTimerRunning(context: Context, taskName: String, remainingSeconds: Long) {
        val pi = mainActivityIntent(context, requestCode = 0)

        val h = remainingSeconds / 3600
        val m = (remainingSeconds % 3600) / 60
        val s = remainingSeconds % 60
        val timeStr = if (h > 0) "$h:${"%02d".format(m)}:${"%02d".format(s)}"
        else "${"%02d".format(m)}:${"%02d".format(s)}"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_TIMER)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("⏱ $taskName")
            .setContentText("Time remaining: $timeStr")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        safeNotify(context, NOTIFICATION_ID_TIMER, notification)
    }

    fun cancelTimer(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_TIMER)
    }

    // ── Expired alarm notification (like Google Clock) ─────────────────────────

    /**
     * Shows an ongoing, updating notification when the timer has expired.
     * [elapsedSeconds] counts up from 0 — same UX as Google Clock's "Timer expired".
     * Has a dedicated "Stop" action button that fires AlarmStopReceiver.
     */
    fun showTimerExpired(context: Context, taskName: String, elapsedSeconds: Long) {
        // Cancel the running-timer notification first
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_TIMER)

        val openPi = mainActivityIntent(context, requestCode = 10)

        // "Stop" action — fires AlarmStopReceiver
        val stopIntent = Intent(context, AlarmStopReceiver::class.java).apply {
            action = AlarmStopReceiver.ACTION_TIMER_EXPIRED
        }
        val stopPi = PendingIntent.getBroadcast(
            context, 20, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val elapsed = formatElapsed(elapsedSeconds)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Timer expired")                        // like Google Clock
            .setContentText("$taskName · $elapsed")
            .setSubText(taskName)
            .setOngoing(true)                                        // cannot be swiped away
            .setSilent(true)                                         // Ringtone handles sound
            .setOnlyAlertOnce(false)
            .setWhen(System.currentTimeMillis() - elapsedSeconds * 1000L)
            .setShowWhen(true)
            .setUsesChronometer(true)                                // ticking clock like Google Clock
            .setChronometerCountDown(false)
            .setContentIntent(openPi)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopPi
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        safeNotify(context, NOTIFICATION_ID_EXPIRED, notification)
    }

    fun cancelExpired(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_EXPIRED)
    }

    // ── Completion notification ────────────────────────────────────────────────

    fun showTimerDone(context: Context, taskName: String, nextTaskName: String?) {
        val pi = mainActivityIntent(context, requestCode = 1)
        val text = if (nextTaskName != null) "Next up: $nextTaskName"
                   else "All tasks completed! Great work!"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALARM)
            .setSmallIcon(android.R.drawable.checkbox_on_background)
            .setContentTitle("✅ Time slice done: $taskName")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        safeNotify(context, NOTIFICATION_ID_DONE, notification)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun mainActivityIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun safeNotify(context: Context, id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted
        }
    }

    /** Format seconds as "0:05", "1:23", "10:00" — same style as Google Clock */
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
