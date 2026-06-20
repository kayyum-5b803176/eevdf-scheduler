package com.eevdf.app.feature.alarm

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
import com.eevdf.app.feature.settings.SoundManager
import com.eevdf.app.feature.settings.UiCustomizationPrefs
import com.eevdf.app.feature.settings.VibrationManager
import com.eevdf.app.feature.task.MainActivity

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

        // ── AOSP-Clock-style alarm lifecycle broadcasts ───────────────────────
        // Sent when a timer expiry alarm starts ringing and when it stops, so any
        // component (overlay activity, the service, external apps / Tasker) can
        // stay in sync regardless of which one is foreground.  These are exported
        // with no permission for maximum interop, mirroring AOSP DeskClock's
        // ALARM_ALERT / ALARM_DONE broadcasts.
        const val ACTION_ALARM_ALERT = "com.eevdf.scheduler.ALARM_ALERT"
        const val ACTION_ALARM_DONE  = "com.eevdf.scheduler.ALARM_DONE"

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
         *
         * @param remainingSecs  Seconds shown in the countdown notification (current execute slice).
         * @param alarmSecs      Seconds until the AlarmManager fires.  For NOTIFICATION tasks this
         *                       equals the sum of all remaining (execute + wait) cycles so the alarm
         *                       is set ONCE for the full cycle duration rather than being cancelled
         *                       and re-set on every execute→wait→execute transition.
         *                       Defaults to [remainingSecs] for all non-NOTIFICATION tasks.
         */
        fun timerStart(
            context: Context,
            taskName: String,
            remainingSecs: Long,
            taskType: String = "DEFAULT",
            alarmSecs: Long = remainingSecs
        ) {
            // AlarmScheduler is the sole AlarmManager owner.
            // Use alarmSecs (total cycle time) for the alarm, remainingSecs for the notification.
            AlarmScheduler.schedule(context, taskName, alarmSecs, taskType)
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

                    // Overlay Intent suppression (UI Customization): when the user
                    // has enabled it and the foreground app is in the configured
                    // list, suppress the full-screen overlay.  The alarm still
                    // rings and shows its notification; only the overlay is hidden.
                    // Because the hardware-key handlers live in the overlay, keys
                    // are inactive while it is suppressed — by design.
                    val suppressOverlay = UiCustomizationPrefs.shouldSuppressOverlay(
                        this, getForegroundPackage(), isDeviceLocked()
                    )

                    showExpiredNotification(taskName, suppressOverlay)
                    val prefs = getSharedPreferences("eevdf_prefs", MODE_PRIVATE)
                    SoundManager.startAlarmForType(this, prefs, taskType)
                    VibrationManager.startAlarmForType(this, prefs, taskType)

                    // AOSP-parity: broadcast that the alarm started ringing so any
                    // listener (overlay, external apps / Tasker) can react.  Sent
                    // unrestricted (exported, no permission) for max interop.
                    sendBroadcast(
                        Intent(ACTION_ALARM_ALERT).apply {
                            putExtra(EXTRA_TASK_NAME, taskName)
                            putExtra(EXTRA_TASK_TYPE, taskType)
                        }
                    )

                    if (!suppressOverlay) {
                        // Force-launch the full-screen overlay so a focused window
                        // always exists for hardware-key handling — not merely a
                        // full-screen intent (which the system may downgrade to a
                        // heads-up while the device is in use, leaving no window to
                        // receive key events).  The service was started from an
                        // alarm broadcast, so it holds a short background-activity-
                        // launch grant here.  The notification's setFullScreenIntent
                        // remains as the fallback path.
                        try {
                            startActivity(AlarmActivity.createIntent(this, taskName))
                        } catch (_: Exception) {
                            // BAL denied (rare): the full-screen intent on the
                            // notification is the fallback and surfaces the overlay.
                        }
                    }
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
        val pm = getSystemService(POWER_SERVICE) as PowerManager
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

    private fun showExpiredNotification(taskName: String, suppressOverlay: Boolean = false) {
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
        val builder = NotificationCompat.Builder(this, CHANNEL_ALARM)
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
        // When suppressed, omit the full-screen intent so the overlay does NOT pop
        // over the configured app.  The high-priority alarm notification still
        // appears (with its Stop action) so the user isn't silently missing it.
        if (!suppressOverlay) {
            builder.setFullScreenIntent(fullScreenPi, true)
        }
        updateNotification(builder.build())
    }

    /**
     * Best-effort current foreground package via UsageStatsManager (same approach
     * as the hover-bubble service).  Returns null when PACKAGE_USAGE_STATS is not
     * granted or no recent usage is available — in which case overlay suppression
     * is skipped (fail-open: the overlay shows).
     */
    /**
     * Current foreground package, resolved precisely via UsageStatsManager
     * EVENTS (not aggregated stats).
     *
     * The previous implementation used queryUsageStats(INTERVAL_DAILY) and picked
     * the entry with the largest lastTimeUsed.  Those are coarse daily buckets
     * whose lastTimeUsed lags by seconds-to-minutes and frequently resolve to the
     * wrong app (or to our own app, which just ran the alarm) — which is why the
     * overlay showed even when a configured app was in the foreground.
     *
     * queryEvents() returns the actual ordered stream of foreground/background
     * transitions.  We scan a short recent window and take the package of the
     * most recent MOVE_TO_FOREGROUND (a.k.a. ACTIVITY_RESUMED) event — the real
     * current foreground app.  We widen the window if nothing is found.
     *
     * Returns null when PACKAGE_USAGE_STATS is not granted or no event is found
     * (fail-open: suppression is skipped and the overlay shows).
     */
    private fun getForegroundPackage(): String? {
        val usm = getSystemService(USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        // Try a tight window first, then progressively widen if the device has
        // been idle (no recent transition events).
        for (windowMs in longArrayOf(10_000L, 60_000L, 300_000L, 3_600_000L)) {
            val pkg = lastForegroundFromEvents(usm, now - windowMs, now)
            if (pkg != null) return pkg
        }
        return null
    }

    private fun lastForegroundFromEvents(
        usm: android.app.usage.UsageStatsManager,
        begin: Long,
        end: Long
    ): String? {
        val events = usm.queryEvents(begin, end)
        val e = android.app.usage.UsageEvents.Event()
        var lastPkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                // Events arrive in time order, so the last match is the most recent.
                lastPkg = e.packageName
            }
        }
        return lastPkg
    }

    /**
     * True when the device is locked OR the screen is off — i.e. the AOSP "alarm
     * fires on the lock screen" situation.  Uses KeyguardManager.isKeyguardLocked
     * and falls back to PowerManager interactivity so a powered-off screen counts
     * as locked even on no-secure-lock setups.
     */
    private fun isDeviceLocked(): Boolean {
        val km = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val keyguardLocked = km?.isKeyguardLocked == true
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        val screenOff = pm?.isInteractive == false
        return keyguardLocked || screenOff
    }

    private fun updateNotification(notification: Notification) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
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
        val wasRinging = isAlarmRinging
        VibrationManager.stop(this)
        SoundManager.stop(this)
        isAlarmRinging = false
        releaseWakeLock()
        stopForegroundCompat()
        // AOSP-parity: broadcast that the alarm finished (stopped/dismissed) so any
        // listener stays in sync.  Only emit if it was actually ringing, to avoid
        // spurious DONE events on pause/start teardown.
        if (wasRinging) {
            sendBroadcast(Intent(ACTION_ALARM_DONE))
        }
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
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
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
