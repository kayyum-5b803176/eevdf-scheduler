package com.eevdf.capabilities.taskwake

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.eevdf.capabilities.navigationroutes.AppRoutes

/**
 * The OS side of a wake: one AlarmManager entry per task, `setAlarmClock` — the same
 * Doze-immune call the task timer uses, so a wake fires on time with the screen off
 * and the app closed. It stores nothing: the wake table is the source of truth, this
 * only mirrors it into AlarmManager (and [WakeBootReceiver] rebuilds it after a reboot).
 */
object WakeScheduler {

    const val ACTION_WAKE = "com.eevdf.scheduler.ACTION_WAKE_DUE"
    const val EXTRA_TASK_ID = "wake_task_id"

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** One stable request code per task, so scheduling again replaces and cancel finds it. */
    private fun requestCode(taskId: String): Int = taskId.hashCode()

    private fun receiverIntent(context: Context, taskId: String): Intent =
        Intent(context, WakeAlarmReceiver::class.java).apply {
            action = ACTION_WAKE
            putExtra(EXTRA_TASK_ID, taskId)
        }

    /** Arms (or re-arms) the OS alarm for [taskId] at [targetEpochMs]. */
    fun schedule(context: Context, taskId: String, targetEpochMs: Long) {
        val fire = PendingIntent.getBroadcast(
            context, requestCode(taskId), receiverIntent(context, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val show = PendingIntent.getActivity(
            context, requestCode(taskId) xor 0x5A5A5A5A,
            AppRoutes.main(context).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager(context).setAlarmClock(AlarmManager.AlarmClockInfo(targetEpochMs, show), fire)
    }

    /** Removes the OS alarm of [taskId]. Safe when none is set. */
    fun cancel(context: Context, taskId: String) {
        val existing = PendingIntent.getBroadcast(
            context, requestCode(taskId), receiverIntent(context, taskId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager(context).cancel(existing)
        existing.cancel()
    }
}
