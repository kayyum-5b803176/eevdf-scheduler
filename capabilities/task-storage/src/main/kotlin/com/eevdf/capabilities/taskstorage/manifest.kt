package com.eevdf.capabilities.taskstorage

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object TaskStorageManifest : CapabilityManifest {
    override val capabilityId = "task-storage"
    override val publishes = setOf(Topics.TASK_SAVED)
    override val subscribes = setOf(Topics.BACKUP_EXPORT_REQUESTED, Topics.BACKUP_IMPORT_REQUESTED)

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // FLAGGED, not yet resolved: task-storage is still reached by direct
        // import from ~15 not-yet-migrated files (task-list-screen,
        // add-task-screen, group-picker, notice-phase, countdown-timer,
        // call-autoswitch, links-screen, stats-screens, backup-restore,
        // multi-device-sync), not exclusively through the bus. Those direct
        // reads/writes have no crash-guard boundary today, so an unavailable
        // task-storage has no defined fallback for them yet. This gets
        // resolved capability-by-capability as each of those migrates
        // (Phase 4/5) and its task-storage calls move behind the bus or a
        // documented bridge — same pattern as feedback-cues' open debt note.
    }
}

/**
 * DOCUMENTED EXCEPTION (per the redesign's own §4): task-storage imports
 * task-scheduling's `task-schedule-bridge.kt` directly (Task <-> SchedTask
 * conversion in TaskRepository) — a compile-time dependency declared in this
 * module's build.gradle.kts, not routed through the bus. This is the one
 * sanctioned capability-to-capability import in the whole system.
 *
 * ALSO FLAGGED: `vruntime-staleness-regression-test.kt` (androidTest) imports
 * run-history's `RunLogRepository`/`RunSession` directly to test an
 * interaction between the two capabilities. This is test-only and doesn't
 * violate rule 3 in production code, but it's a direct cross-capability
 * class reference in a test, not a FakeBus-mediated one — worth revisiting
 * when run-history's bus wiring (task.saved subscription) is fully in place.
 */
