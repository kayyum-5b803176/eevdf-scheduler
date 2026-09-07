package com.eevdf.capabilities.runhistory

import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics

/**
 * Wires this capability's declared `task.saved` subscription (manifest.kt) to
 * a real call: every save opportunistically ticks [RunLogRepository]'s
 * throttled compaction check.
 *
 * WHY THIS, SPECIFICALLY, FOR run-history
 * ----------------------------------------
 * The actual per-run logging ([RunLogRepository.recordRun]) is NOT moved
 * behind this subscription — it stays a direct call from `TaskRepository`
 * (task-storage), because it needs data `task.saved`'s payload (just a task
 * id) doesn't carry: the session's start epoch, duration, and load-average
 * snapshot. That direct edge is the same shape as the `task-schedule-bridge`
 * exception documented in task-storage's own manifest, just not yet called
 * out there by name.
 *
 * What `task.saved` DOES give this capability is a reason to run
 * [RunLogRepository.compactIfDue] outside the timer-session path — a device
 * that's only creating/editing/completing tasks, never actually running a
 * countdown, previously never triggered the 30-day / 365-day tier rollups at
 * all.
 */
class TaskSavedCompactionHandler(
    private val runLog: RunLogRepository,
    bus: EventBus,
) {
    init {
        bus.subscribe(Topics.TASK_SAVED, CAPABILITY_ID) {
            runLog.compactIfDue()
        }
    }

    private companion object {
        const val CAPABILITY_ID = "run-history"
    }
}
