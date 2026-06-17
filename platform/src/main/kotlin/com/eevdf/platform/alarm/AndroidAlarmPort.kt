package com.eevdf.platform.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.eevdf.core.platform.AlarmPort

/**
 * Doze-immune alarm scheduling, consolidated from the reference's orphaned
 * `module/doze_immune_alarm` (which had a placeholder package and a duplicate
 * receiver). Uses `setAlarmClock()` — the only API that fires exactly in Doze
 * without the Android 12+ SCHEDULE_EXACT_ALARM permission prompt.
 */
class AndroidAlarmPort(
    private val context: Context,
    private val receiver: Class<*>,
) : AlarmPort {

    private val am get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun scheduleExact(requestId: String, triggerAtEpochMillis: Long) {
        val pi = pendingIntent(requestId)
        val showInfo = AlarmManager.AlarmClockInfo(triggerAtEpochMillis, pi)
        am.setAlarmClock(showInfo, pi)
    }

    override fun cancel(requestId: String) {
        am.cancel(pendingIntent(requestId))
    }

    private fun pendingIntent(requestId: String): PendingIntent {
        val intent = Intent(context, receiver).apply { putExtra(EXTRA_REQUEST_ID, requestId) }
        return PendingIntent.getBroadcast(
            context,
            requestId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object { const val EXTRA_REQUEST_ID = "eevdf.alarm.requestId" }
}
