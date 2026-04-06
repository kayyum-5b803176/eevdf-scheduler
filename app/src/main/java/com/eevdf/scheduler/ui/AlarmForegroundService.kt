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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Foreground service that runs for the ENTIRE duration of a task timer.
 *
 * Lifecycle:
 *   ACTION_TIMER_START  → service becomes foreground with a "Timer running" notification
 *   ACTION_TIMER_TICK   → updates the notification with remaining time every second
 *   ACTION_TIMER_EXPIRE → acquires WakeLock, wakes screen, starts looping alarm sound,
 *                         updates notification to "Timer expired · 0:23"
 *   ACTION_STOP         → stops sound, releases WakeLock, stops service
 *
 * Why run from timer start rather than expiry:
 *   Android 12+ throws ForegroundServiceStartNotAllowedException when
 *   startForegroundService() is called while the app is in the background.
 *   By starting the service when the user taps "Start" (app is visible),
 *   the service is already foreground when the timer expires — no restrictions apply.
 */
class AlarmForegroundService : Service() {

    companion object {
        const val ACTION_TIMER_START  = "com.eevdf.scheduler.TIMER_START"
        const val ACTION_TIMER_TICK   = "com.eevdf.scheduler.TIMER_TICK"
        const val ACTION_TIMER_EXPIRE = "com.eevdf.scheduler.TIMER_EXPIRE"
        const val ACTION_TIMER_PAUSE  = "com.eevdf.scheduler.TIMER_PAUSE"
        const val ACTION_STOP         = "com.eevdf.scheduler.ALARM_STOP"

        const val EXTRA_TASK_NAME      = "task_name"
        const val EXTRA_REMAINING_SECS = "remaining_secs"

        private const val CHANNEL_TIMER = "eevdf_timer_fg_channel"
        private const val CHANNEL_ALARM = "eevdf_alarm_fg_channel"
        private const val NOTIF_ID      = 3001

        private const val WAKE_TAG         = "EEVDFScheduler:AlarmWake"
        private const val WAKE_TIMEOUT     = 3_600_000L  // 1 hour max — alarm screen wake
        private const val CPU_WAKE_TAG     = "EEVDFScheduler:CpuWake"
        private const val CPU_WAKE_TIMEOUT = 8 * 3_600_000L  // 8 hours max — timer cpu wake

        fun timerStart(context: Context, taskName: String, remainingSecs: Long) =
            send(context, ACTION_TIMER_START, taskName, remainingSecs)

        fun timerTick(context: Context, taskName: String, remainingSecs: Long) =
            send(context, ACTION_TIMER_TICK, taskName, remainingSecs)

        fun timerExpire(context: Context, taskName: String) =
            send(context, ACTION_TIMER_EXPIRE, taskName, 0)

        fun timerPause(context: Context) =
            send(context, ACTION_TIMER_PAUSE, "", 0)

        fun stopAlarm(context: Context) =
            send(context, ACTION_STOP, "", 0)

        private fun send(context: Context, action: String, taskName: String, secs: Long) {
            val intent = Intent(context, AlarmForegroundService::class.java).apply {
                this.action = action
                putExtra(EXTRA_TASK_NAME, taskName)
                putExtra(EXTRA_REMAINING_SECS, secs)
            }
            // startForegroundService only when starting fresh; for ticks use startService
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

    private var wakeLock: PowerManager.WakeLock? = null    // FULL — screen on at alarm
    private var cpuWakeLock: PowerManager.WakeLock? = null // PARTIAL — CPU alive during timer
    private var alarmPlayer: MediaPlayer? = null
    private var isAlarmRinging = false

    private val handler = Handler(Looper.getMainLooper())
    private var overrunSeconds = 0L
    private var expiredTaskName = ""

    // Ticks the "elapsed" counter in the notification after expiry
    private val overrunRunnable = object : Runnable {
        override fun run() {
            overrunSeconds++
            updateExpiredNotification(expiredTaskName, overrunSeconds)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // Must call startForeground immediately in onCreate to avoid ANR
        startForeground(NOTIF_ID, buildTimerNotification("Starting…", 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskName = intent?.getStringExtra(EXTRA_TASK_NAME) ?: ""
        val remaining = intent?.getLongExtra(EXTRA_REMAINING_SECS, 0) ?: 0

        when (intent?.action) {
            ACTION_TIMER_START -> {
                // Acquire a partial wake lock to keep CPU alive during countdown.
                // Screen can still turn off — no battery impact from display.
                acquireCpuWakeLock()
                updateNotification(buildTimerNotification(taskName, remaining))
            }

            ACTION_TIMER_TICK -> {
                // Called every second by ViewModel — keep notification fresh
                updateNotification(buildTimerNotification(taskName, remaining))
            }

            ACTION_TIMER_PAUSE -> {
                // Timer paused — release CPU lock, stop service
                releaseCpuWakeLock()
                stopForegroundCompat()
                stopSelf()
            }

            ACTION_TIMER_EXPIRE -> {
                expiredTaskName = taskName
                acquireWakeLock()
                playAlarmSound()
                startOverrunCounter(taskName)
            }

            ACTION_STOP -> {
                stopEverything()
            }
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
                isLooping = true   // rings forever until stopped
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

    // ── WakeLock ───────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        releaseWakeLock()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK          or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP   or  // physically turns screen on
                    PowerManager.ON_AFTER_RELEASE,
            WAKE_TAG
        ).also { it.acquire(WAKE_TIMEOUT) }
    }

    /** Keeps the CPU running during the countdown without turning the screen on. */
    private fun acquireCpuWakeLock() {
        releaseCpuWakeLock()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CPU_WAKE_TAG)
            .also { it.acquire(CPU_WAKE_TIMEOUT) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun releaseCpuWakeLock() {
        cpuWakeLock?.let { if (it.isHeld) it.release() }
        cpuWakeLock = null
    }

    // ── Overrun counter ────────────────────────────────────────────────────────

    private fun startOverrunCounter(taskName: String) {
        handler.removeCallbacks(overrunRunnable)
        overrunSeconds = 0
        updateExpiredNotification(taskName, 0)
        handler.postDelayed(overrunRunnable, 1000L)
    }

    private fun stopOverrunCounter() {
        handler.removeCallbacks(overrunRunnable)
        overrunSeconds = 0
    }

    // ── Notifications ──────────────────────────────────────────────────────────

    private fun buildTimerNotification(taskName: String, remainingSecs: Long): Notification {
        val h = remainingSecs / 3600
        val m = (remainingSecs % 3600) / 60
        val s = remainingSecs % 60
        val timeStr = if (h > 0) "$h:${"%02d".format(m)}:${"%02d".format(s)}"
        else "${"%02d".format(m)}:${"%02d".format(s)}"

        val openPi = openMainActivityPi(0)

        return NotificationCompat.Builder(this, CHANNEL_TIMER)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("⏱ $taskName")
            .setContentText("Time remaining: $timeStr")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateExpiredNotification(taskName: String, elapsedSecs: Long) {
        val stopPi = PendingIntent.getBroadcast(
            this, 20,
            Intent(this, AlarmStopReceiver::class.java).apply {
                action = AlarmStopReceiver.ACTION_TIMER_EXPIRED
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val elapsed = NotificationHelper.formatElapsed(elapsedSecs)
        val notification = NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Timer expired")
            .setContentText("$taskName · $elapsed")
            .setOngoing(true)
            .setSilent(true)
            .setWhen(System.currentTimeMillis() - elapsedSecs * 1000L)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setContentIntent(openMainActivityPi(10))
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
        stopAlarmPlayer()
        releaseWakeLock()
        releaseCpuWakeLock()
        stopOverrunCounter()
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
                    setSound(null, null)  // sound handled by MediaPlayer
                    enableVibration(true)
                }
            )
        }
    }
}
