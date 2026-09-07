package com.eevdf.capabilities.alarmringer

import android.content.Context
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics

/**
 * Wires this capability's declared `timer.expired` AND `realtime-window.
 * expired` subscriptions (manifest.kt) to real, deliberately SAFE calls.
 *
 * WHY NEITHER RE-TRIGGERS OR STOPS THE ALARM
 * ---------------------------------------------
 * `timer.expired` (published unconditionally by countdown-timer's
 * `TimerEngine` for every task type, including NOTIFICATION) looks, at
 * first glance, like it should do what this capability's own
 * `AlarmCommandHandler` already does for `Topics.ALARM_TIMER_EXPIRE_REQUESTED`
 * — start ringing. It must NOT: `ALARM_TIMER_EXPIRE_REQUESTED` is published
 * conditionally by task-list-screen's `TimerLifecycleDelegate`, skipped
 * specifically for NOTIFICATION-type tasks (those route through
 * `NoticeStateMachine.handleExpiredNotificationTask` instead, which owns its
 * own delay/wait/execute alarm scheduling). Reacting to `timer.expired` by
 * calling `AlarmForegroundService.timerExpire()` here would double-fire the
 * alarm for every non-NOTIFICATION task (both topics fire for those) and
 * incorrectly fire it for NOTIFICATION tasks the Notice state machine hasn't
 * decided should ring yet.
 *
 * `realtime-window.expired` carries the closed task's ID (per this topic's
 * own KDoc in `Topics`), but this capability's persisted [AlarmState] only
 * ever stores a `taskName` — it has no `TaskRepository` to resolve one to
 * the other, and deliberately so: alarm-ringer has no dependency on
 * task-storage anywhere else, and adding one just to answer "does the id
 * that just expired match the name currently ringing" would be a new,
 * narrow architectural exception for one comparison. Until the payload
 * carries a name too (or a lookup path is added deliberately, not as a side
 * effect of this fix), the correct move is NOT to guess-match by id — a
 * wrong match would stop the wrong alarm.
 *
 * So both subscriptions are diagnostic only: they record that the engine
 * observed the event, independent of whatever triggering/stopping decision
 * gets made through the topics that already own that responsibility — the
 * same "logging or future adaptive behavior" use this capability's own
 * [AlarmReliabilityChecker]-adjacent tooling already exists for (see
 * notification's `AlarmDeliveryLog`, the same pattern applied here).
 */
class TimerExpiryHandler(
    appContext: Context,
    bus: EventBus,
) {
    init {
        bus.subscribe(Topics.TIMER_EXPIRED, CAPABILITY_ID) { taskId ->
            TimerExpiryLog.recordExpiry(appContext, taskId)
        }
        bus.subscribe(Topics.REALTIME_WINDOW_EXPIRED, CAPABILITY_ID) { taskId ->
            TimerExpiryLog.recordRtWindowExpiry(appContext, taskId)
        }
    }

    private companion object {
        const val CAPABILITY_ID = "alarm-ringer"
    }
}

/**
 * The most recent `timer.expired` / `realtime-window.expired` observations —
 * single last-value records, not a history, same shape and same reasoning as
 * notification's `AlarmDeliveryLog`. Nothing reads these yet; they exist
 * so [TimerExpiryHandler]'s subscriptions do real work instead of being
 * empty placeholders.
 */
object TimerExpiryLog {
    private const val PREFS_NAME = "eevdf_timer_expiry_log"
    private const val KEY_TASK_ID = "last_expired_task_id"
    private const val KEY_EPOCH   = "last_expired_epoch_ms"
    private const val KEY_RT_TASK_ID = "last_rt_window_expired_task_id"
    private const val KEY_RT_EPOCH   = "last_rt_window_expired_epoch_ms"

    fun recordExpiry(context: Context, taskId: String, nowMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_TASK_ID, taskId)
            .putLong(KEY_EPOCH, nowMs)
            .apply()
    }

    fun recordRtWindowExpiry(context: Context, taskId: String, nowMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_RT_TASK_ID, taskId)
            .putLong(KEY_RT_EPOCH, nowMs)
            .apply()
    }
}

