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
 * RESOLVED (twice): this used to call `NotificationHelper.cancelExpired(context)`
 * directly — a real cross-capability import — because that method existed
 * specifically to know "cancel notification id 3001." After notification's
 * redecomposition (see its manifest.kt), it has no alarm-specific knowledge
 * left at all, so cancelling now goes through the fully generic
 * `Topics.NOTIFICATION_CANCEL_REQUESTED` (any capability, any notification
 * id) instead — this capability supplies the id, notification just cancels
 * whatever id it's given. Blocking, not fire-and-forget, for the same
 * reason `Topics.ALARM_STOPPED` below is: `EventBus.publish` (structured
 * concurrency, bounded by `runIsolated`'s 5s timeout) does not return until
 * every subscriber has run, so this still gives a "cancelled before this
 * method returns" guarantee without a direct import.
 */
@AndroidEntryPoint
class AlarmStopReceiver : BroadcastReceiver() {

    @Inject lateinit var bus: EventBus

    override fun onReceive(context: Context, intent: Intent) {
        // Capture the ringing task's name BEFORE stopping — stopping clears
        // the persisted AlarmState, and Topics.ALARM_STOPPED's payload is the
        // stopped task's name per the known-topics table. Falls back to an
        // empty string rather than skipping the publish: sound/vibration's
        // subscribers only need to know an alarm stopped, not which one.
        val ringingTaskName = (AlarmScheduler.currentState(context) as? AlarmState.Ringing)?.taskName.orEmpty()

        // Stop the foreground service (releases WakeLock too)
        AlarmForegroundService.stopAlarm(context)
        // Local broadcast so AlarmActivity (same capability) can close itself
        context.sendBroadcast(Intent(ACTION_STOP_ALARM))

        runBlocking {
            bus.publish(Topics.ALARM_STOPPED, ringingTaskName, "alarm-ringer")
            bus.publish(Topics.NOTIFICATION_CANCEL_REQUESTED, AlarmForegroundService.NOTIF_ID_EXPIRE, "alarm-ringer")
        }
    }

    companion object {
        const val ACTION_STOP_ALARM    = "com.eevdf.scheduler.ACTION_STOP_ALARM"
        const val ACTION_TIMER_EXPIRED = "com.eevdf.scheduler.ACTION_TIMER_EXPIRED"
    }
}
