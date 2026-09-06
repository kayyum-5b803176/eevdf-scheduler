package com.eevdf.capabilities.alarmringer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eevdf.capabilities.remindernotifier.NotificationHelper
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives the Stop action from the expired-timer notification.
 *
 * The local broadcast to [ACTION_STOP_ALARM] stays as-is: both senders and
 * receivers of it ([AlarmActivity]) live inside this same capability, so it
 * is not a cross-capability boundary — rule 3 only governs communication
 * BETWEEN capabilities.
 *
 * What DID change: the old `com.eevdf.contract.control.AlarmActions` object
 * that held this constant lived in `:contract` specifically so
 * `task-list-screen`'s `MainActivity` could register for it across a
 * capability boundary. Now `Topics.ALARM_STOPPED` carries that cross-boundary
 * signal instead, and `AlarmActions` is retired entirely — `ACTION_STOP_ALARM`
 * becomes a private, capability-local constant again.
 */
@AndroidEntryPoint
class AlarmStopReceiver : BroadcastReceiver() {

    @Inject lateinit var bus: EventBus

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        // Capture the ringing task's name BEFORE stopping — stopping clears
        // the persisted AlarmState, and Topics.ALARM_STOPPED's payload is the
        // stopped task's name per the known-topics table.
        val ringingTaskName = (AlarmScheduler.currentState(context) as? AlarmState.Ringing)?.taskName

        // Stop the foreground service (releases WakeLock too)
        AlarmForegroundService.stopAlarm(context)
        // Cancel the expired notification
        NotificationHelper.cancelExpired(context)
        // Local broadcast so AlarmActivity (same capability) can close itself
        context.sendBroadcast(Intent(ACTION_STOP_ALARM))

        if (ringingTaskName != null) {
            scope.launch { bus.publish(Topics.ALARM_STOPPED, ringingTaskName) }
        }
    }

    companion object {
        const val ACTION_STOP_ALARM    = "com.eevdf.scheduler.ACTION_STOP_ALARM"
        const val ACTION_TIMER_EXPIRED = "com.eevdf.scheduler.ACTION_TIMER_EXPIRED"
    }
}
