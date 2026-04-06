package com.eevdf.scheduler.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the AlarmManager callback when a task timer expires.
 *
 * AlarmManager.setAlarmClock() is immune to Doze mode and fires exactly on time
 * regardless of battery optimization or screen state — this is what makes the
 * alarm reliable on Android 12+ where CountDownTimer/Handler get deferred after
 * ~30 seconds of screen-off (Light Doze).
 *
 * This receiver simply forwards the expiry to AlarmForegroundService which is
 * already running as a foreground service and handles sound + screen wake.
 */
class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskName = intent.getStringExtra(AlarmForegroundService.EXTRA_TASK_NAME) ?: return
        AlarmForegroundService.timerExpire(context, taskName)
    }
}
