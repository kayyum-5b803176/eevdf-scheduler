package com.eevdf.scheduler.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the "Stop" action from the expired-timer notification.
 * Sends a local broadcast that MainActivity listens to, so it can call
 * viewModel.stopAlarmSound() and dismiss the alarm UI.
 */
class AlarmStopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Cancel the expired notification immediately
        NotificationHelper.cancelExpired(context)
        // Broadcast locally so the running Activity can react
        val local = Intent(ACTION_STOP_ALARM)
        context.sendBroadcast(local)
    }

    companion object {
        const val ACTION_STOP_ALARM = "com.eevdf.scheduler.ACTION_STOP_ALARM"
        const val ACTION_TIMER_EXPIRED = "com.eevdf.scheduler.ACTION_TIMER_EXPIRED"
    }
}
