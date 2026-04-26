package com.eevdf.scheduler.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * The ONLY class in the app that is allowed to call AlarmManager or write AlarmState.
 *
 * ── Contract ──────────────────────────────────────────────────────────────────
 *
 *  1. State is written to disk BEFORE the AlarmManager call on schedule(), and
 *     AFTER on cancel() / markRinging().  This ordering means:
 *       • If process dies after write but before AlarmManager call → alarm fires,
 *         receiver reads Scheduled, forwards to service.  Correct.
 *       • If process dies after AlarmManager cancel but before write → alarm is
 *         already gone from the system, receiver will never fire.  Harmless.
 *
 *  2. cancel() is the ONLY legal way to remove the alarm.  Nothing else
 *     (ViewModel.onCleared, service.onDestroy, stopAlarmSound) may call it.
 *
 *  3. schedule() is idempotent: calling it again while Scheduled simply
 *     replaces the existing AlarmManager entry (FLAG_UPDATE_CURRENT) and
 *     overwrites the stored state.  Safe for pause→resume cycles.
 *
 *  4. onAlarmFired() transitions Scheduled → Ringing.  Only TimerAlarmReceiver
 *     calls this.  If state is NOT Scheduled when the receiver fires, the alarm
 *     was cancelled (pause, stop, user dismissed) and the receiver must not
 *     forward the event.  This is the definitive ghost-alarm guard.
 *
 *  5. stop() transitions Ringing → Idle.  Only called when the user dismisses
 *     the alarm (Stop button, AlarmActivity, AlarmStopReceiver).
 *
 * ── Why not cancel in onCleared / onDestroy ───────────────────────────────────
 *
 *  onCleared() is called when Android kills the ViewModel cleanly (e.g. task
 *  swipe, config change).  If the timer is still running at that point, the
 *  AlarmManager entry MUST survive so it fires when the slice expires.
 *  onDestroy() of the service is similarly unreliable — it fires both on
 *  explicit stop AND on OOM kill.  Neither is the right place to cancel.
 *
 *  The only correct time to cancel is when the user explicitly pauses or stops
 *  the timer.  Those actions flow through ViewModel.pauseTimer() /
 *  ViewModel.resetTimer() which call AlarmScheduler.cancel() directly.
 */
object AlarmScheduler {

    private const val REQUEST_CODE = 0xA1A7_EE00.toInt()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Schedule a Doze-immune alarm to fire after [remainingSecs] seconds.
     *
     * Writes Scheduled state to disk first, then calls AlarmManager.
     * Replaces any previously scheduled alarm for the same request code.
     */
    fun schedule(
        context: Context,
        taskName: String,
        remainingSecs: Long,
        taskType: String
    ) {
        val triggerEpoch = System.currentTimeMillis() + remainingSecs * 1000L

        // Write state before AlarmManager call — crash-safe ordering.
        AlarmState.write(context, AlarmState.Scheduled(taskName, triggerEpoch, taskType))

        val receiverPi = buildReceiverPiCreate(context, taskName, taskType)
        val showPi     = PendingIntent.getActivity(
            context, REQUEST_CODE + 1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager(context).setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerEpoch, showPi),
            receiverPi
        )
    }

    /**
     * Cancel the pending alarm and return to Idle.
     *
     * Safe to call when no alarm is scheduled (FLAG_NO_CREATE returns null).
     *
     * ONLY call this when the user explicitly pauses or stops the timer.
     * NEVER call this from onCleared() or onDestroy().
     */
    fun cancel(context: Context) {
        // Cancel AlarmManager first, then write Idle — so if process dies between
        // the two calls the alarm is already gone and will not fire.
        val pi = buildReceiverPiIfExists(context)
        if (pi != null) {
            alarmManager(context).cancel(pi)
            pi.cancel()
        }
        AlarmState.write(context, AlarmState.Idle)
    }

    /**
     * Transition Scheduled → Ringing.
     *
     * Called by [TimerAlarmReceiver] after it has verified the state is Scheduled.
     * Returns true if the transition was valid and the service should start.
     * Returns false if the state was already Idle or Ringing (ghost alarm guard):
     *   the caller must not start the alarm service.
     */
    fun onAlarmFired(context: Context): Boolean {
        return when (val state = AlarmState.read(context)) {
            is AlarmState.Scheduled -> {
                AlarmState.write(context, AlarmState.Ringing(
                    taskName   = state.taskName,
                    taskType   = state.taskType,
                    firedEpoch = System.currentTimeMillis()
                ))
                true
            }
            // State is Idle  → alarm was cancelled (pause/stop) before it fired.
            //                  OEM ROMs sometimes deliver a broadcast after cancel().
            //                  Guard blocks it.
            // State is Ringing → double-delivery guard (should never happen with
            //                  setAlarmClock, but defensive).
            else -> false
        }
    }

    /**
     * Transition Ringing → Idle.
     *
     * Called when the user dismisses the alarm (Stop button, AlarmActivity,
     * AlarmStopReceiver).  Safe to call from Idle (no-op write, no harm).
     */
    fun stop(context: Context) {
        AlarmState.write(context, AlarmState.Idle)
    }

    /** Read current state — for diagnostic / recovery use only. */
    fun currentState(context: Context): AlarmState = AlarmState.read(context)

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * FLAG_UPDATE_CURRENT always creates - returns non-null.
     * Used by schedule() where setAlarmClock() requires PendingIntent (non-null).
     */
    private fun buildReceiverPiCreate(
        context: Context,
        taskName: String,
        taskType: String
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, TimerAlarmReceiver::class.java).apply {
            putExtra(AlarmForegroundService.EXTRA_TASK_NAME, taskName)
            putExtra(AlarmForegroundService.EXTRA_TASK_TYPE, taskType)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )!! // FLAG_UPDATE_CURRENT never returns null

    /**
     * FLAG_NO_CREATE returns null when no matching PendingIntent exists.
     * Used by cancel() to safely skip when no alarm is scheduled.
     */
    private fun buildReceiverPiIfExists(context: Context): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, TimerAlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

    private fun alarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}
