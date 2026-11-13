package com.eevdf.capabilities.tasklistscreen

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object TaskListScreenManifest : CapabilityManifest {
    override val capabilityId = "task-list-screen"
    override val publishes: Set<Topic<*>> = setOf(
        Topics.TIMER_RUNNING_CHANGED,
        // Real publisher of REALTIME_WINDOW_EXPIRED (ListBuilderDelegate),
        // even though task-scheduling is the topic's originally-declared
        // owner — see task-scheduling's manifest.kt for why that capability
        // structurally can't be the one calling bus.publish().
        Topics.REALTIME_WINDOW_EXPIRED,
        // Published exclusively by CurrentTaskOwner — the one place allowed
        // to change "which task is dispatched." See its KDoc and
        // Topics.CURRENT_TASK_CHANGED's KDoc.
        Topics.CURRENT_TASK_CHANGED,
        // The toolbar sync icon (v6.38.0 — replaces the direct
        // MultiUserSyncManager.scheduleExport() call).
        Topics.SYNC_NOW_REQUESTED,
    )
    override val subscribes: Set<Topic<*>> = setOf(
        Topics.TIMER_RUNNING_CHANGED,
        Topics.BUBBLE_TAPPED,
        Topics.PHONE_CALL_STATE_CHANGED,
        Topics.BACKUP_EXPORT_REQUESTED,
        Topics.TIMER_EXPIRED,
        Topics.ALARM_STOPPED,
        Topics.OVERLAY_SHOWN,
        Topics.TASK_SAVED,
        // v6.38.0: the toolbar dot, and "data changed underneath you — re-read
        // the timer" (TaskViewModel.subscribeToSync / SyncReconcileDelegate).
        Topics.SYNC_STATUS_CHANGED,
        Topics.SYNC_REMOTE_APPLIED,
    )

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // The list screen is the user's main view, not a dependency of other
        // capabilities' correctness. If it is detached (backgrounded, or
        // quarantined by the supervisor) there is no in-memory list state to
        // update: timers keep running in their services, alarms still ring,
        // backups still checkpoint via task-storage, and the screen rebuilds
        // from the database on its next attach.
    }
}

/**
 * `TIMER_EXPIRED`: declared as a subscription above, and now genuinely
 * published (by countdown-timer's `TimerEngine` — see its manifest.kt) —
 * but deliberately NOT subscribed to here with a `bus.subscribe()` call.
 * `TimerEngine` is instantiated directly inside this capability's own
 * `TaskViewModel` (`internal val timerEngine = TimerEngine(bus)`), and
 * `TaskViewModel` already reacts to the same expiry via a direct LiveData
 * observer on that same instance (`timerEngine.expiredTask.observeForever`)
 * — the actual, original mechanism, unrelated to the bus. Subscribing to
 * `TIMER_EXPIRED` here too would be this capability reacting to its own
 * publish, round-tripped through the bus for zero additional information: a
 * pure no-op with overhead, not a real integration. The declared
 * subscription stays (removing it would understate what this capability
 * genuinely does react to, since the underlying fact IS consumed — just not
 * via `bus.subscribe`), but this note exists so nobody "finishes" this one
 * by adding a hollow subscribe call.
 */

/**
 * `OVERLAY_SHOWN`: RESOLVED with a real `bus.subscribe()` call — see
 * `TaskViewModel.subscribeToOverlayShown`. Feeds a coarse
 * `LiveData<taskId>` (`overlayShownTaskId`) intended for the task list to
 * badge/highlight the relevant row; nothing currently reads it from the UI
 * layer, but the data plumbing is real, not a placeholder.
 *
 * `TASK_CONFLICT_DETECTED` (and `conflictedTaskIds`) were retired in
 * v6.38.0 together with the sync conflict dialog — see multi-device-sync's
 * manifest.kt.
 */
