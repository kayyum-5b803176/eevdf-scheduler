package com.eevdf.scheduler.ui.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eevdf.scheduler.ui.notification.NotificationHelper

class AlarmStopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Stop the foreground service (releases WakeLock too)
        AlarmForegroundService.stopAlarm(context)
        // Cancel the expired notification
        NotificationHelper.cancelExpired(context)
        // Broadcast locally so MainActivity and AlarmActivity can close themselves
        val local = Intent(ACTION_STOP_ALARM)
        context.sendBroadcast(local)
    }

    companion object {
        const val ACTION_STOP_ALARM    = "com.eevdf.scheduler.ACTION_STOP_ALARM"
        const val ACTION_TIMER_EXPIRED = "com.eevdf.scheduler.ACTION_TIMER_EXPIRED"
    }
}
