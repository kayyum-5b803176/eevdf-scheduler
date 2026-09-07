package com.eevdf.capabilities.multidevicesync.logic

import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics

/**
 * Wires this capability's declared `task.saved` subscription (manifest.kt) to
 * a real call: every save anywhere in the app schedules a debounced export.
 *
 * WHAT THIS REPLACES / SUPPLEMENTS
 * ---------------------------------
 * task-list-screen's `TaskViewModel` already calls
 * `MultiUserSyncManager.scheduleExport()` directly at its own mutation sites
 * (`triggerSyncExport()`) — that direct capability-to-capability call is
 * flagged, deliberate debt (see task-list-screen's `EVENT-BUS-ARCHITECTURE.md`
 * §6 entry), not something this handler removes. What this handler adds is
 * coverage for writes task-list-screen never sees at all: group-picker and
 * links-screen both write to `TaskRepository` directly, and backup-restore's
 * restore path does too — none of those go through `TaskViewModel`, so none
 * of them ever called `triggerSyncExport()`. Now that `TaskRepository`
 * publishes `task.saved` on every save regardless of caller, this capability
 * hears about ALL of them, not just the subset task-list-screen happens to
 * mediate.
 *
 * `scheduleExport()` is itself debounced (cancels-and-reschedules on a
 * timer — see `MultiUserSyncManager`), so a task-list-screen write and this
 * handler both firing for the same save is harmless: one export goes out,
 * not two.
 */
class TaskSavedSyncHandler(bus: EventBus) {
    init {
        bus.subscribe(Topics.TASK_SAVED, CAPABILITY_ID) {
            MultiUserSyncManager.scheduleExport()
        }
    }

    private companion object {
        const val CAPABILITY_ID = "multi-device-sync"
    }
}
