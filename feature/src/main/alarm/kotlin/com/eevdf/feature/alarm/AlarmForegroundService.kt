package com.eevdf.feature.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.eevdf.feature.R
import com.eevdf.platform.media.SoundManager
import com.eevdf.platform.media.VibrationManager
import com.eevdf.platform.notification.AlarmNotificationPolicy
import com.eevdf.platform.notification.AlarmReliabilityChecker
import com.eevdf.platform.notification.AppForegroundTracker
import com.eevdf.platform.notification.ForegroundAppDetector
import com.eevdf.feature.shared.prefs.NotificationPrefs
import com.eevdf.contract.nav.AppRoutes

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
        private const val TAG = "EEVDFAlarm"

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
        // Bumped from "eevdf_alarm_fg_channel" — channel settings are
        // immutable by the app once created, so any earlier build (or manual
        // user tweak while debugging the banner-only version) that left this
        // channel's importance/"pop on screen" below HIGH would silently
        // block the full-screen intent forever. A fresh channel ID guarantees
        // this build starts from correct HIGH-importance defaults.
        private const val CHANNEL_ALARM = "eevdf_alarm_fg_channel_v2"

        // Two IDs, one per concern, instead of one ID reused for placeholder/
        // countdown/delay AND the expiry notification. Keeping them isolated
        // means updating the countdown can never be mistaken by anything
        // (us or the platform) for "updating" the expiry notification, and
        // vice versa — each has its own independent lifecycle.
        private const val NOTIF_ID_ONGOING = 3000   // placeholder / countdown / delay
        private const val NOTIF_ID_EXPIRE  = 3001   // timer-expired alarm (unchanged —
                                                     // NotificationHelper.cancelExpired()
                                                     // targets this exact value)

        /** How long to wait for AlarmActivity to actually appear before falling
         *  back to a manual screen wake. See the fallback comment in
         *  ACTION_TIMER_EXPIRE for why this exists. */
        private const val FULLSCREEN_FALLBACK_DELAY_MS = 2_000L

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
        Log.d(TAG, "onCreate: posting placeholder notification (fresh service instance)")
        createChannels()
        // Must call startForeground() in onCreate() within 5 seconds of
        // startForegroundService().  Use a silent placeholder notification —
        // the real notification is set in onStartCommand for each action.
        //
        // IMPORTANT: this must NOT be buildTimerNotification(_, 0). That builds a
        // chronometer with triggerEpoch = now, i.e. already in the past by the time
        // it renders, so the notification flashes a negative/count-up stopwatch
        // instead of "0:00". If onStartCommand's real update is ever delayed (or,
        // rarely, delivered with a null Intent and skipped), that broken state is
        // what the user is stuck looking at. The placeholder must carry no
        // chronometer at all.
        startForeground(NOTIF_ID_ONGOING, buildPlaceholderNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // No action to act on — don't leave the chronometer-less placeholder
            // notification (or a stale prior one) stuck forever; just tear down.
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

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
                    Log.d(TAG, "EXPIRE fired: taskName=$taskName isDeviceLocked=${isDeviceLocked()}")
                    acquireWakeLock()

                    // Foreground-app detection is a best-effort UsageStatsManager
                    // read for the Exclude App feature only. Isolated in its own
                    // try/catch: if it throws on some OEM/edge case, that must
                    // degrade to "no match" — it must never take down the alarm
                    // itself, which is a far worse failure than one missed
                    // Exclude App check.
                    val appForeground = AppForegroundTracker.isAppInForeground
                    val foregroundPkg = if (appForeground) null else try {
                        ForegroundAppDetector.getForegroundPackage(this)
                    } catch (e: Exception) {
                        Log.w(TAG, "getForegroundPackage failed, treating as no match", e)
                        null
                    }
                    val excludeAppMatch = !appForeground &&
                        NotificationPrefs.isAppExcluded(this, foregroundPkg)

                    // Pure decision — see AlarmNotificationPolicy for the full
                    // rationale. Extracted out of this method so the decision
                    // itself is unit-testable independent of the service.
                    val decision = AlarmNotificationPolicy.decide(
                        appForeground = appForeground,
                        excludeAppMatch = excludeAppMatch,
                        lockScreenOverlayEnabled = NotificationPrefs.isLockScreenOverlayEnabled(this)
                    )

                    // Diagnostic snapshot only — never gates behavior. Channel
                    // lookup wrapped defensively; a query failure here must not
                    // block posting the actual alarm notification.
                    val channelImportance = try {
                        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                            .getNotificationChannel(CHANNEL_ALARM)?.importance
                    } catch (e: Exception) {
                        null
                    }
                    Log.d(
                        TAG,
                        "EXPIRE decision: appForeground=$appForeground foregroundPkg=$foregroundPkg " +
                            "excludeAppMatch=$excludeAppMatch suppressBanner=${decision.suppressBanner} " +
                            "attachFullScreenIntent=${decision.attachFullScreenIntent} " +
                            "canUseFullScreenIntent=${AlarmReliabilityChecker.canUseFullScreenIntent(this)} " +
                            "channelImportance=$channelImportance"
                    )

                    showExpiredNotification(taskName, decision.suppressBanner, decision.attachFullScreenIntent)
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

                    // Bounded fallback — nothing more than this one check. The
                    // full-screen intent is the primary, correct mechanism (see
                    // showExpiredNotification); the platform can still decline
                    // to honor it for reasons outside app control (OEM policy,
                    // battery/standby state, rapid repeat throttling — all
                    // observed in practice). If we asked for full-screen and
                    // AlarmActivity genuinely never appeared, a manual
                    // startActivity() is attempted once, best-effort: it may
                    // itself be blocked by background-start restrictions, but
                    // costs nothing to try, since acquireWakeLock() has already
                    // woken the screen either way.
                    if (decision.attachFullScreenIntent) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (isAlarmRinging && !AlarmActivity.isShowing) {
                                Log.d(TAG, "Fallback: AlarmActivity never appeared, attempting direct launch")
                                try {
                                    startActivity(AlarmActivity.createIntent(this, taskName))
                                } catch (e: Exception) {
                                    Log.w(TAG, "Fallback direct launch failed", e)
                                }
                            }
                        }, FULLSCREEN_FALLBACK_DELAY_MS)
                    }
                } else {
                    Log.d(TAG, "EXPIRE ignored: isAlarmRinging guard already true (duplicate delivery?)")
                }
            }

            ACTION_STOP -> stopEverything()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: service instance torn down")
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

    /**
     * Restored to FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP after switching to
     * PARTIAL_WAKE_LOCK regressed the app: the screen stopped waking at all
     * when a timer expired. That change assumed the full-screen intent
     * reliably launches AlarmActivity (which would wake the screen itself via
     * setTurnScreenOn), but that launch is evidently not happening
     * consistently here — so relying on it alone left the phone with no
     * screen wake whatsoever. Reverting to a known-working baseline while the
     * actual full-screen-intent launch issue is investigated separately.
     */
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

    /**
     * Static placeholder shown only for the brief window between startForeground()
     * in onCreate() and the real notification built in onStartCommand(). No
     * setWhen/chronometer — there is no meaningful remaining time yet, so there is
     * nothing to count down that could render as negative.
     */
    private fun buildPlaceholderNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_TIMER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Starting…")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openMainActivityPi(0))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun buildDelayNotification(taskName: String, delaySecs: Long): Notification {
        // Clamp to >=1s: a chronometer built with `when` <= now is already in the
        // past by render time and shows a negative count instead of "0:00".
        val safeDelaySecs = delaySecs.coerceAtLeast(1L)
        val delayEndEpoch = System.currentTimeMillis() + safeDelaySecs * 1000L
        val builder = NotificationCompat.Builder(this, CHANNEL_DELAY)
            .setSmallIcon(R.drawable.ic_notification)
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
        // Same clamp as buildDelayNotification — see comment there.
        val safeRemainingSecs = remainingSecs.coerceAtLeast(1L)
        val triggerEpoch = System.currentTimeMillis() + safeRemainingSecs * 1000L
        val builder = NotificationCompat.Builder(this, CHANNEL_TIMER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(taskName)
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

    /**
     * Expiry notification. Always built the same way; which STYLE the user
     * actually sees is resolved by the platform, not chosen manually here:
     *
     *   • [attachFullScreenIntent] adds a full-screen intent pointing at
     *     [AlarmActivity]. With CATEGORY_ALARM + PRIORITY_MAX on a
     *     HIGH-importance channel, the platform launches it full-screen while
     *     the device is locked, and silently downgrades to a normal heads-up
     *     banner while unlocked — i.e. "locked → overlay, unlocked → banner"
     *     comes from the platform's own full-screen-intent rules, not from
     *     anything decided in this method.
     *   • [suppressBanner] governs the banner style only: `setSilent()` is
     *     what gates the heads-up peek on a non-full-screen-intent posting
     *     (or on the downgraded-to-banner path above) — it does not affect
     *     whether the full-screen intent itself fires while locked.
     */
    private fun showExpiredNotification(
        taskName: String,
        suppressBanner: Boolean = false,
        attachFullScreenIntent: Boolean = false
    ) {
        val stopPi = PendingIntent.getBroadcast(
            this, 20,
            Intent(this, AlarmStopReceiver::class.java).apply {
                action = AlarmStopReceiver.ACTION_TIMER_EXPIRED
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Timer expired")
            .setContentText(taskName)
            .setOngoing(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setUsesChronometer(true)   // counts UP from setWhen — elapsed time
            .setContentIntent(openMainActivityPi(10))
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (attachFullScreenIntent) {
            val fullScreenPi = PendingIntent.getActivity(
                this, 30,
                AlarmActivity.createIntent(this, taskName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setFullScreenIntent(fullScreenPi, true)
        }

        // Suppressed (Exclude App match, OR our own app is foreground — see
        // the two suppression reasons at the call site): mark silent so the
        // banner posts quietly with no peek. Otherwise leave silent unset —
        // HIGH channel importance + not-silent is what makes it heads-up. The
        // channel itself carries no sound/vibration (see createChannels):
        // SoundManager/VibrationManager already own that, so this never
        // doubles up regardless of which branch runs.
        if (suppressBanner) {
            builder.setSilent(true)
        }

        // Post via a fresh startForeground() call — mirroring AOSP DeskClock's
        // TimerModel.updateHeadsUpNotification(), which does exactly this
        // (ServiceCompat.startForeground(...)) for the expiry notification
        // specifically, rather than a plain NotificationManager.notify() on
        // the already-running foreground notification. On Android 14+ this
        // also re-asserts the SPECIAL_USE foreground service type for this
        // specific post, which mediaPlayback never correctly represented for
        // a non-MediaSession alarm ringer.
        val notification = builder.build()
        Log.d(TAG, "Posting notification: hasFullScreenIntent=${notification.fullScreenIntent != null} isSilent=${suppressBanner}")
        // Cancel the ongoing/placeholder notification explicitly — we're
        // switching the FGS's notification to a different ID (NOTIF_ID_EXPIRE),
        // so the old one would otherwise linger as an orphaned, non-FGS entry.
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID_ONGOING)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIF_ID_EXPIRE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            updateExpiredNotification(notification)
        }
    }

    /**
     * Diagnostic only — logged alongside the EXPIRE decision so a logcat
     * capture shows what the app itself believed the lock state was,
     * compared against the platform's own full-screen-intent decision (which
     * is what actually determines whether AlarmActivity launches). Not used
     * to gate any behavior.
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
        nm.notify(NOTIF_ID_ONGOING, notification)
    }

    private fun updateExpiredNotification(notification: Notification) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_EXPIRE, notification)
    }

    private fun openMainActivityPi(reqCode: Int) = PendingIntent.getActivity(
        this, reqCode,
        AppRoutes.main(this).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private fun stopEverything() {
        val wasRinging = isAlarmRinging
        Log.d(TAG, "stopEverything: wasRinging=$wasRinging")
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
        // stopForeground() only removes whichever ID is CURRENTLY the FGS
        // notification. Explicitly cancel both — a stray notification from
        // the other ID (e.g. a countdown notification left behind after a
        // switch to the expiry ID) must not linger in the shade.
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID_ONGOING)
        nm.cancel(NOTIF_ID_EXPIRE)
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
                    // No sound/vibration here: SoundManager/VibrationManager already
                    // own both explicitly when the alarm rings. The notification
                    // itself only needs to be non-silent to be heads-up-eligible
                    // (see showExpiredNotification) — it doesn't need its own sound
                    // or vibration to do that, and giving it either would double up.
                    setSound(null, null); enableVibration(false)
                }
            )
        }
    }
}
