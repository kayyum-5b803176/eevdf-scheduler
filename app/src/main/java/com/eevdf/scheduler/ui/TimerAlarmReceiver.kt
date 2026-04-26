package com.eevdf.scheduler.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Receives the AlarmManager callback when a task timer expires.
 *
 * ── Why this class is minimal ─────────────────────────────────────────────────
 *
 *  All state logic lives in [AlarmScheduler] and [AlarmState].
 *  This receiver's only job is:
 *    1. Ask AlarmScheduler whether this alarm is still valid.
 *    2. If yes → start the foreground service to ring the alarm.
 *    3. If no  → do nothing (ghost alarm guard).
 *
 * ── Ghost alarm guard ─────────────────────────────────────────────────────────
 *
 *  AlarmManager.cancel() is not always reliable on OEM ROMs (MIUI, EMUI,
 *  OneUI buffer broadcasts internally).  [AlarmScheduler.onAlarmFired] reads
 *  the persisted [AlarmState] and returns false if the state is not Scheduled
 *  (meaning cancel() was called before the broadcast landed).  In that case
 *  this receiver silently exits — no service is started, no alarm rings.
 *
 * ── startForegroundService vs startService ────────────────────────────────────
 *
 *  The app has NO foreground service running when this receiver fires (the
 *  service was stopped when the timer was started, since it only stays alive
 *  during the countdown notification).  On Android 8+, calling startService()
 *  from a background BroadcastReceiver throws IllegalStateException silently.
 *  startForegroundService() is explicitly permitted within a BroadcastReceiver's
 *  10-second execution window and requires the service to call startForeground()
 *  within 5 seconds — AlarmForegroundService does this in onCreate().
 */
class TimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // ── Ghost alarm guard ─────────────────────────────────────────────────
        // onAlarmFired() atomically checks state == Scheduled and transitions to
        // Ringing.  Returns false if the alarm was cancelled (pause/stop/dismiss)
        // before this broadcast landed — in that case we must not ring.
        if (!AlarmScheduler.onAlarmFired(context)) return

        // State is now Ringing — start the service to play sound and wake screen.
        // Read task info from the persisted Ringing state rather than from the
        // intent extras, so the data is always consistent with what was scheduled
        // even if the intent was recycled by the OS.
        val ringing = AlarmScheduler.currentState(context) as? AlarmState.Ringing ?: return

        val serviceIntent = Intent(context, AlarmForegroundService::class.java).apply {
            action = AlarmForegroundService.ACTION_TIMER_EXPIRE
            putExtra(AlarmForegroundService.EXTRA_TASK_NAME, ringing.taskName)
            putExtra(AlarmForegroundService.EXTRA_TASK_TYPE, ringing.taskType)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
