package com.eevdf.capabilities.tasklistscreen

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object TaskListScreenManifest : CapabilityManifest {
    override val capabilityId = "task-list-screen"
    override val publishes: Set<Topic<*>> = setOf(Topics.TIMER_RUNNING_CHANGED)
    override val subscribes: Set<Topic<*>> = setOf(
        Topics.TIMER_RUNNING_CHANGED,
        Topics.BUBBLE_TAPPED,
        Topics.PHONE_CALL_STATE_CHANGED,
        Topics.BACKUP_EXPORT_REQUESTED,
        Topics.BACKUP_IMPORT_REQUESTED,
        Topics.TIMER_EXPIRED,
        Topics.ALARM_STOPPED,
        Topics.OVERLAY_SHOWN,
        Topics.TASK_SAVED,
        Topics.TASK_CONFLICT_DETECTED,
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
