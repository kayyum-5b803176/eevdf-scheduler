package com.eevdf.scheduler.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
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
        const val ACTION_TIMER_EXPIRE = "com.eevdf.scheduler.TIMER_EXPIRE"
        const val ACTION_TIMER_PAUSE  = "com.eevdf.scheduler.TIMER_PAUSE"
        const val ACTION_STOP         = "com.eevdf.scheduler.ALARM_STOP"

        const val EXTRA_TASK_NAME      = "task_name"
        const val EXTRA_REMAINING_SECS = "remaining_secs"

        private const val CHANNEL_TIMER = "eevdf_timer_fg_channel"
        private const val CHANNEL_ALARM = "eevdf_alarm_fg_channel"
        private const val NOTIF_ID      = 3001

        // FULL WakeLock only — acquired at alarm expiry to physically wake the screen
        private const val WAKE_TAG     = "EEVDFScheduler:AlarmWake"
        private const val WAKE_TIMEOUT = 3_600_000L  // 1 hour max

        private const val ALARM_MGR_REQ = 9001

        fun timerStart(context: Context, taskName: String, remainingSecs: Long) {
            send(context, ACTION_TIMER_START, taskName, remainingSecs)
            scheduleAlarmManager(context, taskName, remainingSecs)
        }

        fun timerExpire(context: Context, taskName: String) =
            send(context, ACTION_TIMER_EXPIRE, taskName, 0)

        fun timerPause(context: Context) {
            send(context, ACTION_TIMER_PAUSE, "", 0)
            cancelAlarmManager(context)
        }

        fun stopAlarm(context: Context) {
            send(context, ACTION_STOP, "", 0)
            cancelAlarmManager(context)
        }

        /**
         * Schedule a Doze-immune alarm via AlarmManager.setAlarmClock().
         * Fires exactly on time even in Deep Doze — this is the ONLY mechanism
         * needed to guarantee on-time delivery. No WakeLock required during countdown.
         */
        private fun scheduleAlarmManager(context: Context, taskName: String, remainingSecs: Long) {
            val triggerAt = System.currentTimeMillis() + remainingSecs * 1000L

            val receiverPi = PendingIntent.getBroadcast(
                context, ALARM_MGR_REQ,
                Intent(context, TimerAlarmReceiver::class.java).apply {
                    putExtra(EXTRA_TASK_NAME, taskName)
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

        private fun send(context: Context, action: String, taskName: String, secs: Long) {
            val intent = Intent(context, AlarmForegroundService::class.java).apply {
                this.action = action
                putExtra(EXTRA_TASK_NAME, taskName)
                putExtra(EXTRA_REMAINING_SECS, secs)
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
    private var alarmPlayer: MediaPlayer? = null
    private var isAlarmRinging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIF_ID, buildTimerNotification("Starting…", 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskName  = intent?.getStringExtra(EXTRA_TASK_NAME) ?: ""
        val remaining = intent?.getLongExtra(EXTRA_REMAINING_SECS, 0) ?: 0

        when (intent?.action) {
            ACTION_TIMER_START -> {
                // Show a countdown notification driven by the system clock.
                // No WakeLock — phone sleeps normally, AlarmManager wakes it on time.
                updateNotification(buildTimerNotification(taskName, remaining))
            }

            ACTION_TIMER_PAUSE -> {
                stopForegroundCompat()
                stopSelf()
            }

            ACTION_TIMER_EXPIRE -> {
                if (!isAlarmRinging) {
                    acquireWakeLock()
                    playAlarmSound()
                    showExpiredNotification(taskName)
                    // Start vibration with user-configured pattern and timeout
                    val prefs = getSharedPreferences("eevdf_prefs", android.content.Context.MODE_PRIVATE)
                    VibrationManager.startAlarm(this, prefs)
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

    private fun playAlarmSound() {
        stopAlarmPlayer()
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        try {
            alarmPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmForegroundService, uri)
                isLooping = true
                prepare()
                start()
            }
            isAlarmRinging = true
        } catch (e: Exception) {
            isAlarmRinging = false
        }
    }

    private fun stopAlarmPlayer() {
        alarmPlayer?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        alarmPlayer = null
        isAlarmRinging = false
    }

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
        stopAlarmPlayer()
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
                NotificationChannel(CHANNEL_ALARM, "Timer Expired", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(true)
                }
            )
        }
    }
}
