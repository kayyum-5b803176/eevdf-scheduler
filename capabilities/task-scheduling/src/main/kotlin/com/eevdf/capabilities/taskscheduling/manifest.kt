package com.eevdf.capabilities.taskscheduling

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object TaskSchedulingManifest : CapabilityManifest {
    override val capabilityId = "task-scheduling"
    override val publishes = setOf(Topics.REALTIME_WINDOW_EXPIRED)
    override val subscribes: Set<Topic<*>> = emptySet()

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // FLAGGED, same debt class as task-storage: reached by direct import
        // from task-list-screen and add-task-screen today (scheduling
        // decisions computed inline, not via the bus). No defined fallback
        // yet — resolved when those capabilities migrate (Phase 4).
    }
}

/**
 * DEFERRED, not done this phase:
 * - `eevdf-scheduler.kt`'s class is still named `EevdfScheduler` (rule 8
 *   flags this as the canonical *bad* example — should become something
 *   like `RankFairShareTasks`). Renaming the class (not just the file)
 *   touches every call site across this capability; deferred to keep this
 *   already-large phase's blast radius bounded.
 * - `scheduler-facade.kt` (was SchedulerFacade.kt) and `task-schedule-bridge.kt`
 *   (was RtScheduler.kt) were moved as separate files, not merged into one
 *   bridge file as the Phase -1 table proposed — same reasoning, deferred as
 *   a pure internal reorganization with zero external API impact.
 */
