package com.yourapp.alarm   // ← change to your package

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * DozeImmuneTimer
 *
 * A drop-in helper that schedules and cancels a Doze-immune alarm using
 * AlarmManager.setAlarmClock(). Use this alongside your CountDownTimer or
 * Handler-based UI countdown — the AlarmManager is the guaranteed trigger,
 * the CountDownTimer is only for display.
 *
 * WHY setAlarmClock() AND NOT setExact() / setExactAndAllowWhileIdle()?
 * ─────────────────────────────────────────────────────────────────────
 * • setExact()                 → deferred in Doze. Not suitable for alarms.
 * • setExactAndAllowWhileIdle() → fires in Doze but on Android 12+ requires
 *                                 SCHEDULE_EXACT_ALARM which needs user consent.
 * • setAlarmClock()            → always fires exactly on time, no special
 *                                 permission needed, immune to all Doze levels.
 *                                 Shows a clock icon in the status bar.
 *                                 This is what AOSP Clock uses.
 *
 * USAGE
 * ─────
 * // When timer starts:
 * DozeImmuneTimer.schedule(context, "My Task", remainingSeconds)
 *
 * // When timer is paused or cancelled:
 * DozeImmuneTimer.cancel(context)
 *
 * // In your foreground service, guard against double-trigger:
 * if (intent?.action == ACTION_TIMER_EXPIRE && !isAlarmRinging) {
 *     isAlarmRinging = true
 *     playSound()
 *     wakeScreen()
 * }
 */
object DozeImmuneTimer {

    const val EXTRA_LABEL = "doze_immune_timer_label"

    // Stable request code — must be consistent between schedule() and cancel()
    private const val REQUEST_CODE = 0xDEAD_A1A7.toInt()

    /**
     * Schedule a Doze-immune alarm to fire after [remainingSeconds].
     *
     * @param context       Application or service context.
     * @param label         A string payload forwarded to your receiver/service
     *                      (e.g. task name, timer ID). Can be any string.
     * @param remainingSeconds  Seconds from now until the alarm should fire.
     * @param showActivityClass The Activity to open when the user taps the clock
     *                          icon in the status bar. Typically your main screen.
     */
    fun schedule(
        context: Context,
        label: String,
        remainingSeconds: Long,
        showActivityClass: Class<*>? = null
    ) {
        val triggerAtMs = System.currentTimeMillis() + remainingSeconds * 1000L

        val receiverPi = buildReceiverPendingIntent(context, label, PendingIntent.FLAG_UPDATE_CURRENT)

        // Show intent — what opens when user taps the alarm clock icon in status bar.
        // If no activity is provided we reuse the receiver intent (it's ignored visually).
        val showPi = if (showActivityClass != null) {
            PendingIntent.getActivity(
                context, 1,
                Intent(context, showActivityClass).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            receiverPi
        }

        alarmManager(context).setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAtMs, showPi),
            receiverPi
        )
    }

    /**
     * Cancel the pending alarm. Call on pause, stop, or task completion.
     * Safe to call even if no alarm is scheduled.
     */
    fun cancel(context: Context) {
        val pi = buildReceiverPendingIntent(context, "", PendingIntent.FLAG_NO_CREATE)
            ?: return   // nothing scheduled, nothing to cancel
        alarmManager(context).cancel(pi)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun buildReceiverPendingIntent(
        context: Context,
        label: String,
        extraFlags: Int
    ): PendingIntent? = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, TimerAlarmReceiver::class.java).apply {
            putExtra(EXTRA_LABEL, label)
        },
        extraFlags or PendingIntent.FLAG_IMMUTABLE
    )

    private fun alarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}
