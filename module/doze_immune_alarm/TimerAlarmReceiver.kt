package com.yourapp.alarm   // ← change to your package

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the AlarmManager.setAlarmClock() callback when a timer expires.
 *
 * AlarmManager.setAlarmClock() is fully exempt from Doze mode — it fires at the
 * exact scheduled time regardless of screen state, battery optimization settings,
 * or OEM power management. This makes it the only reliable way to trigger an alarm
 * after the screen has been off for more than ~30 seconds on Android 6+.
 *
 * This receiver is intentionally minimal: it just forwards the expiry event to
 * your foreground service, which handles sound, screen wake, and UI.
 *
 * SETUP:
 *   1. Change the package name above.
 *   2. Replace `YourForegroundService` with your actual service class.
 *   3. Replace `EXTRA_TASK_NAME` / `ACTION_TIMER_EXPIRE` with your own constants,
 *      or import them from your service's companion object.
 *   4. Register in AndroidManifest.xml:
 *        <receiver android:name=".TimerAlarmReceiver" android:exported="false" />
 */
class TimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Retrieve whatever payload you passed when scheduling the alarm.
        // Common example: the label/name of the task that just expired.
        val label = intent.getStringExtra(DozeImmuneTimer.EXTRA_LABEL) ?: return

        // Forward to your foreground service.
        // The service is expected to already be running (started when the timer began).
        // Using startService (not startForegroundService) because the service is
        // already in the foreground — no need to promote it again.
        val serviceIntent = Intent(context, YourForegroundService::class.java).apply {
            action = YourForegroundService.ACTION_TIMER_EXPIRE
            putExtra(DozeImmuneTimer.EXTRA_LABEL, label)
        }
        context.startService(serviceIntent)
    }
}
