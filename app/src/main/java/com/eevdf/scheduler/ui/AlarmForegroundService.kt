package com.eevdf.scheduler.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Foreground service that owns the notification UI and alarm sound/wake.
 *
 * ── Responsibilities ──────────────────────────────────────────────────────────
 *
 *  ACTION_TIMER_START  → show countdown notification (system-clock driven).
 *  ACTION_DELAY_START  → show delay-phase notification.
 *  ACTION_TIMER_PAUSE  → remove notification, stop self.
 *  ACTION_TIMER_EXPIRE → acquire WakeLock, play alarm sound, show expired UI.
 *  ACTION_STOP         → stop sound, release WakeLock, stop self.
 *
 * ── Non-responsibilities ──────────────────────────────────────────────────────
 *
 *  This class does NOT call AlarmManager directly.  All AlarmManager interaction
 *  is owned exclusively by [AlarmScheduler].
 *
 *  This class does NOT decide whether an alarm is valid.  That check belongs in
 *  [TimerAlarmReceiver] via [AlarmScheduler.onAlarmFired].
 *
 * ── Lifecycle rules ───────────────────────────────────────────────────────────
 *
 *  START_NOT_STICKY: if Android kills the service, it is NOT restarted.
 *  The AlarmManager entry in the system process is unaffected by service death —
 *  it will still fire and deliver to TimerAlarmReceiver, which will restart the
 *  service via startForegroundService.
 *
 *  onDestroy: releases sound and WakeLock only.  Must NOT cancel the alarm.
 *  Cancelling in onDestroy would silently remove the alarm on process death,
 *  which is exactly the bug that caused random alarm disappearance.
 */
class AlarmForegroundService : Service() {

    companion object {
        const val ACTION_TIMER_START  = "com.eevdf.scheduler.TIMER_START"
        const val ACTION_DELAY_START  = "com.eevdf.scheduler.DELAY_START"
        const val ACTION_TIMER_EXPIRE = "com.eevdf.scheduler.TIMER_EXPIRE"
        const val ACTION_TIMER_PAUSE  = "com.eevdf.scheduler.TIMER_PAUSE"
        const val ACTION_STOP         = "com.eevdf.scheduler.ALARM_STOP"

        const val EXTRA_TASK_NAME      = "task_name"
        const val EXTRA_REMAINING_SECS = "remaining_secs"
        const val EXTRA_TASK_TYPE      = "task_type"
        const val EXTRA_NOTIF_DELAY    = "notif_delay_secs"

        private const val CHANNEL_TIMER = "eevdf_timer_fg_channel"
        private const val CHANNEL_DELAY = "eevdf_delay_fg_channel"
        private const val CHANNEL_ALARM = "eevdf_alarm_fg_channel"
        private const val NOTIF_ID      = 3001

        private const val WAKE_TAG     = "EEVDFScheduler:AlarmWake"
        private const val WAKE_TIMEOUT = 3_600_000L   // 1 hour max

        // ── Public API — called from ViewModel only ───────────────────────────

        /**
         * Call when the timer starts.
         * Schedules the Doze-immune alarm via AlarmScheduler (not inline here),
         * then starts the foreground service to show the countdown notification.
         */
        fun timerStart(
            context: Context,
            taskName: String,
            remainingSecs: Long,
            taskType: String = "DEFAULT"
        ) {
            // AlarmScheduler is the sole AlarmManager owner.
            AlarmScheduler.schedule(context, taskName, remainingSecs, taskType)
            send(context, ACTION_TIMER_START, taskName, remainingSecs, taskType)
        }

        /**
         * Call when the timer is paused.
         * Cancels the alarm via AlarmScheduler, removes the notification.
         */
        fun timerPause(context: Context) {
            // AlarmScheduler.cancel() is the ONLY legal way to remove the alarm.
            AlarmScheduler.cancel(context)
            send(context, ACTION_TIMER_PAUSE, "", 0)
        }

        /**
         * Called by TimerAlarmReceiver after AlarmScheduler.onAlarmFired() returns true.
         * Starts the service in the Ringing state (sound + WakeLock).
         * Do NOT call this from ViewModel — it bypasses the ghost-alarm guard.
         */
        fun timerExpire(context: Context, taskName: String, taskType: String = "DEFAULT") {
            val intent = Intent(context, AlarmForegroundService::class.java).apply {
                action = ACTION_TIMER_EXPIRE
                putExtra(EXTRA_TASK_NAME, taskName)
                putExtra(EXTRA_TASK_TYPE, taskType)
            }
            // Must be startForegroundService — the app is background at this point
            // (called from BroadcastReceiver after app kill).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Call when the user stops the alarm (Stop button, AlarmActivity).
         * Transitions AlarmState Ringing → Idle, stops the service.
         */
        fun stopAlarm(context: Context) {
            AlarmScheduler.stop(context)
            send(context, ACTION_STOP, "", 0)
        }

        /** Cancel the alarm without stopping the notification service.
         *  Used by the notice state machine when transitioning phases. */
        fun cancelScheduledAlarm(context: Context) {
            AlarmScheduler.cancel(context)
        }

        fun delayStart(context: Context, taskName: String, delaySecs: Long) {
            val intent = Intent(context, AlarmForegroundService::class.java).apply {
                action = ACTION_DELAY_START
                putExtra(EXTRA_TASK_NAME, taskName)
                putExtra(EXTRA_NOTIF_DELAY, delaySecs)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        // ── Internal helpers ─────────────────────────────────────────────────

        private fun send(
            context: Context,
            action: String,
            taskName: String,
            secs: Long,
            taskType: String = "DEFAULT"
        ) {
            val intent = Intent(context, AlarmForegroundService::class.java).apply {
                this.action = action
                putExtra(EXTRA_TASK_NAME, taskName)
                putExtra(EXTRA_REMAINING_SECS, secs)
                putExtra(EXTRA_TASK_TYPE, taskType)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    // ── Service state ─────────────────────────────────────────────────────────

    private var wakeLock: PowerManager.WakeLock? = null
    private var isAlarmRinging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // Must call startForeground() in onCreate() within 5 seconds of
        // startForegroundService().  Use a silent placeholder notification —
        // the real notification is set in onStartCommand for each action.
        startForeground(NOTIF_ID, buildTimerNotification("Starting…", 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val taskName   = intent.getStringExtra(EXTRA_TASK_NAME) ?: ""
        val remaining  = intent.getLongExtra(EXTRA_REMAINING_SECS, 0)
        val taskType   = intent.getStringExtra(EXTRA_TASK_TYPE) ?: "DEFAULT"
        val notifDelay = intent.getLongExtra(EXTRA_NOTIF_DELAY, 0L)

        when (intent.action) {
            ACTION_DELAY_START -> {
                updateNotification(buildDelayNotification(taskName, notifDelay))
            }

            ACTION_TIMER_START -> {
                updateNotification(buildTimerNotification(taskName, remaining))
            }

            ACTION_TIMER_PAUSE -> {
                // Alarm is already cancelled (AlarmScheduler.cancel called in timerPause).
                // Just remove the notification and stop.
                stopForegroundCompat()
                stopSelf()
            }

            ACTION_TIMER_EXPIRE -> {
                // Guard: only ring once even if the intent is delivered twice.
                // AlarmScheduler.onAlarmFired() is the primary guard (in receiver),
                // isAlarmRinging is the secondary in-process guard.
                if (!isAlarmRinging) {
                    isAlarmRinging = true
                    acquireWakeLock()
                    showExpiredNotification(taskName)
                    val prefs = getSharedPreferences("eevdf_prefs", Context.MODE_PRIVATE)
                    SoundManager.startAlarmForType(this, prefs, taskType)
                    VibrationManager.startAlarmForType(this, prefs, taskType)
                }
            }

            ACTION_STOP -> stopEverything()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release resources only.  Must NOT cancel AlarmManager here.
        //
        // onDestroy fires in two cases:
        //   1. Explicit stop (ACTION_STOP, ACTION_TIMER_PAUSE): alarm was already
        //      cancelled by AlarmScheduler.cancel() before this was called.
        //   2. OOM kill by Android: alarm MUST remain scheduled so it fires later.
        //
        // Calling AlarmScheduler.cancel() here would silently remove the alarm
        // in case 2, which is the root cause of the random alarm disappearance bug.
        VibrationManager.stop(this)
        SoundManager.stop(this)
        releaseWakeLock()
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        releaseWakeLock()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK          or
            PowerManager.ACQUIRE_CAUSES_WAKEUP   or
            PowerManager.ON_AFTER_RELEASE,
            WAKE_TAG
        ).also { it.acquire(WAKE_TIMEOUT) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun buildDelayNotification(taskName: String, delaySecs: Long): Notification {
        val delayEndEpoch = System.currentTimeMillis() + delaySecs * 1000L
        val builder = NotificationCompat.Builder(this, CHANNEL_DELAY)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Starting soon — $taskName")
            .setContentText("Timer begins in")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openMainActivityPi(0))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setWhen(delayEndEpoch)
            .setUsesChronometer(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setChronometerCountDown(true)
        }
        return builder.build()
    }

    private fun buildTimerNotification(taskName: String, remainingSecs: Long): Notification {
        val triggerEpoch = System.currentTimeMillis() + remainingSecs * 1000L
        val builder = NotificationCompat.Builder(this, CHANNEL_TIMER)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("⏱ $taskName")
            .setContentText("Time remaining")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openMainActivityPi(0))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setWhen(triggerEpoch)
            .setUsesChronometer(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setChronometerCountDown(true)
        }
        return builder.build()
    }

    private fun showExpiredNotification(taskName: String) {
        val stopPi = PendingIntent.getBroadcast(
            this, 20,
            Intent(this, AlarmStopReceiver::class.java).apply {
                action = AlarmStopReceiver.ACTION_TIMER_EXPIRED
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val fullScreenPi = PendingIntent.getActivity(
            this, 30,
            AlarmActivity.createIntent(this, taskName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Timer expired")
            .setContentText(taskName)
            .setOngoing(true)
            .setSilent(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setUsesChronometer(true)   // counts UP from setWhen — elapsed time
            .setContentIntent(openMainActivityPi(10))
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPi, true)
            .build()
        updateNotification(notification)
    }

    private fun updateNotification(notification: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notification)
    }

    private fun openMainActivityPi(reqCode: Int) = PendingIntent.getActivity(
        this, reqCode,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private fun stopEverything() {
        VibrationManager.stop(this)
        SoundManager.stop(this)
        isAlarmRinging = false
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // ── Channels ──────────────────────────────────────────────────────────────

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_TIMER, "Task Timer", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null); enableVibration(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DELAY, "Notification Delay", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null); enableVibration(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALARM, "Timer Expired", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null); enableVibration(true)
                }
            )
        }
    }
}
