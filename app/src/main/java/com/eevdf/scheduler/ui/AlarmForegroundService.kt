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
 * Foreground service whose lifecycle now matches AOSP Clock:
 *
 *   ACTION_TIMER_START  → becomes foreground with a chronometer notification.
 *                         NO WakeLock is acquired — AlarmManager fires even in Deep Doze.
 *                         The system clock drives the notification countdown with zero CPU cost.
 *   ACTION_TIMER_EXPIRE → acquires FULL WakeLock, wakes screen, loops alarm sound.
 *   ACTION_STOP         → stops sound, releases WakeLock, stops service.
 *   ACTION_TIMER_PAUSE  → stops service (no lock to release).
 *
 * What changed vs the original:
 *  - PARTIAL_WAKE_LOCK (8-hour) removed entirely.
 *    AlarmManager.setAlarmClock() is already Doze-immune — the WakeLock was redundant
 *    and was the root cause of the "WiFi battery drain" symptom (WiFi chip stays on
 *    whenever the CPU WakeLock prevents sleep).
 *  - ACTION_TIMER_TICK removed. The notification now uses setUsesChronometer(true) +
 *    setChronometerCountDown(true) + setWhen(triggerEpoch), so Android's system clock
 *    updates the countdown display every second with no app code at all.
 *  - overrunRunnable removed. TaskViewModel's overrunTimer already tracks elapsed time
 *    after expiry — the service no longer needs a duplicate 1-second loop.
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

        private const val CHANNEL_TIMER  = "eevdf_timer_fg_channel"
        private const val CHANNEL_DELAY  = "eevdf_delay_fg_channel"
        private const val CHANNEL_ALARM  = "eevdf_alarm_fg_channel"
        private const val NOTIF_ID       = 3001

        // FULL WakeLock only — acquired at alarm expiry to physically wake the screen
        private const val WAKE_TAG     = "EEVDFScheduler:AlarmWake"
        private const val WAKE_TIMEOUT = 3_600_000L  // 1 hour max

        private const val ALARM_MGR_REQ = 9001

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

        fun timerStart(
            context: Context,
            taskName: String,
            remainingSecs: Long,
            taskType: String = "DEFAULT"
        ) {
            send(context, ACTION_TIMER_START, taskName, remainingSecs, taskType)
            scheduleAlarmManager(context, taskName, remainingSecs, taskType)
        }

        fun timerExpire(context: Context, taskName: String, taskType: String = "DEFAULT") {
            val intent = Intent(context, AlarmForegroundService::class.java).apply {
                action = ACTION_TIMER_EXPIRE
                putExtra(EXTRA_TASK_NAME, taskName)
                putExtra(EXTRA_REMAINING_SECS, 0L)
                putExtra(EXTRA_TASK_TYPE, taskType)
            }
            context.startService(intent)
        }

        fun timerPause(context: Context) {
            send(context, ACTION_TIMER_PAUSE, "", 0)
            cancelAlarmManager(context)
        }

        fun stopAlarm(context: Context) {
            send(context, ACTION_STOP, "", 0)
            cancelAlarmManager(context)
        }

        /**
         * Cancel the pending AlarmManager alarm without stopping the foreground service.
         * Called when the notice state machine transitions to wait or repeat-execute so
         * the alarm does not fire spuriously and trigger timerExpire while a phase is
         * still in progress.
         */
        fun cancelScheduledAlarm(context: Context) {
            cancelAlarmManager(context)
        }

        /**
         * Schedule a Doze-immune alarm via AlarmManager.setAlarmClock().
         * Fires exactly on time even in Deep Doze.
         */
        private fun scheduleAlarmManager(
            context: Context,
            taskName: String,
            remainingSecs: Long,
            taskType: String = "DEFAULT"
        ) {
            val triggerAt = System.currentTimeMillis() + remainingSecs * 1000L

            val receiverPi = PendingIntent.getBroadcast(
                context, ALARM_MGR_REQ,
                Intent(context, TimerAlarmReceiver::class.java).apply {
                    putExtra(EXTRA_TASK_NAME, taskName)
                    putExtra(EXTRA_TASK_TYPE, taskType)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val showPi = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE
            )

            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(triggerAt, showPi), receiverPi)
        }

        private fun cancelAlarmManager(context: Context) {
            val pi = PendingIntent.getBroadcast(
                context, ALARM_MGR_REQ,
                Intent(context, TimerAlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return
            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.cancel(pi)
        }

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
            if (action == ACTION_TIMER_START) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(intent)
                else
                    context.startService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    // FULL WakeLock only — acquired at expiry to physically wake the screen
    private var wakeLock: PowerManager.WakeLock? = null
    private var isAlarmRinging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIF_ID, buildTimerNotification("Starting…", 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskName   = intent?.getStringExtra(EXTRA_TASK_NAME) ?: ""
        val remaining  = intent?.getLongExtra(EXTRA_REMAINING_SECS, 0) ?: 0
        val taskType   = intent?.getStringExtra(EXTRA_TASK_TYPE) ?: "DEFAULT"
        val notifDelay = intent?.getLongExtra(EXTRA_NOTIF_DELAY, 0L) ?: 0L

        when (intent?.action) {
            // Delay phase: show countdown notification until ViewModel fires the real timer
            ACTION_DELAY_START -> {
                updateNotification(buildDelayNotification(taskName, notifDelay))
            }

            ACTION_TIMER_START -> {
                // Show a countdown notification driven by the system clock.
                updateNotification(buildTimerNotification(taskName, remaining))
            }

            ACTION_TIMER_PAUSE -> {
                stopForegroundCompat()
                stopSelf()
            }

            ACTION_TIMER_EXPIRE -> {
                if (!isAlarmRinging) {
                    isAlarmRinging = true
                    acquireWakeLock()
                    showExpiredNotification(taskName)
                    val prefs = getSharedPreferences("eevdf_prefs", android.content.Context.MODE_PRIVATE)
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
        stopEverything()
    }

    // ── Sound ──────────────────────────────────────────────────────────────────

    // Sound is now handled by SoundManager

    // ── WakeLock (alarm expiry only) ───────────────────────────────────────────

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

    // ── Notifications ──────────────────────────────────────────────────────────

    /**
     * Shown during the NOTIFICATION-type delay phase. Counts down to the moment
     * the real timer begins.
     */
    private fun buildDelayNotification(taskName: String, delaySecs: Long): Notification {
        val delayEndEpoch = System.currentTimeMillis() + delaySecs * 1000L
        val builder = NotificationCompat.Builder(this, CHANNEL_DELAY)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Starting soon \u2014 $taskName")
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

    /**
     * Chronometer-based timer notification.
     *
     * setUsesChronometer(true) + setChronometerCountDown(true) + setWhen(triggerEpoch)
     * tells Android to display a live countdown updated every second by the system
     * clock — no app code needed, zero CPU cost. This is exactly how AOSP Clock works.
     */
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

        // setChronometerCountDown counts down to setWhen. minSdk 26 so always available.
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
            .setUsesChronometer(true)   // counts UP from setWhen — shows elapsed time for free
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

    // ── Cleanup ────────────────────────────────────────────────────────────────

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

    // ── Channels ───────────────────────────────────────────────────────────────

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_TIMER, "Task Timer", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DELAY, "Notification Delay", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALARM, "Timer Expired", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(true)
                }
            )
        }
    }
}
