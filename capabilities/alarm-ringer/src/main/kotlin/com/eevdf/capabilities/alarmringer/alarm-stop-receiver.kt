package com.eevdf.capabilities.alarmringer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
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
 *
 * RESOLVED: this used to also call `NotificationHelper.cancelExpired(context)`
 * directly (a real import of notification) as a "guaranteed" path,
 * because the `Topics.ALARM_STOPPED` publish only fired conditionally (only
 * when a ringing task name was found) and asynchronously (`scope.launch`,
 * not awaited). Both of those are fixed now: the publish is unconditional
 * (empty string when no task name is found — notification's
 * `AlarmDeliveryHandler` doesn't need one to cancel a notification) and
 * blocking (`runBlocking`, bounded by the same `runIsolated` 5s timeout
 * every bus dispatch has), so the bus path alone gives the same "cancelled
 * before this method returns" guarantee the direct call used to. This
 * capability now has zero import of notification for any action —
 * only `NotificationHelper.formatElapsed` remains anywhere in alarm-ringer,
 * a pure string-formatting utility, not a notification action.
 */
@AndroidEntryPoint
class AlarmStopReceiver : BroadcastReceiver() {

    @Inject lateinit var bus: EventBus

    override fun onReceive(context: Context, intent: Intent) {
        // Capture the ringing task's name BEFORE stopping — stopping clears
        // the persisted AlarmState, and Topics.ALARM_STOPPED's payload is the
        // stopped task's name per the known-topics table. Falls back to an
        // empty string rather than skipping the publish: notification's
        // subscriber only needs to know an alarm stopped, not which one, to
        // cancel the one notification it manages.
        val ringingTaskName = (AlarmScheduler.currentState(context) as? AlarmState.Ringing)?.taskName.orEmpty()

        // Stop the foreground service (releases WakeLock too)
        AlarmForegroundService.stopAlarm(context)
        // Local broadcast so AlarmActivity (same capability) can close itself
        context.sendBroadcast(Intent(ACTION_STOP_ALARM))

        // Blocking, not fire-and-forget: see this class's KDoc for why this
        // is what makes the direct NotificationHelper call safe to remove.
        runBlocking { bus.publish(Topics.ALARM_STOPPED, ringingTaskName, "alarm-ringer") }
    }

    companion object {
        const val ACTION_STOP_ALARM    = "com.eevdf.scheduler.ACTION_STOP_ALARM"
        const val ACTION_TIMER_EXPIRED = "com.eevdf.scheduler.ACTION_TIMER_EXPIRED"
    }
}
