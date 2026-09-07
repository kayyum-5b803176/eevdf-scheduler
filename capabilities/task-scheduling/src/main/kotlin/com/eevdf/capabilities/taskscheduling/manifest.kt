package com.eevdf.capabilities.taskscheduling

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

/**
 * CORRECTED IN v6.10.2 — the Phase 2 split created a dependency CYCLE.
 *
 * The redesign spec §4 said to keep the Task <-> SchedTask bridge and put it
 * in task-scheduling. Taken literally that is impossible: the bridge must
 * know Task (owned by task-storage), while task-storage must know SchedTask
 * and the schedulers (owned here). Gradle rejected the resulting cycle, and
 * it would not have been fixable by any build-file tweak — it was a real
 * layering mistake, not a wiring one.
 *
 * The fix restores the layering the original :core / :data split already had:
 *
 *   task-scheduling  = PURE domain. SchedTask, SchedConfig, EevdfScheduler,
 *                      CpuShares, RtPolicy, rank-tasks. Knows nothing about
 *                      Task, RunLog, Room or Android. Depends on no capability.
 *                      (This is what lived in :core/scheduler.)
 *
 *   task-storage     = owns Task, and therefore owns every Task-aware adapter:
 *                      task-schedule-bridge, scheduler-facade, load-average,
 *                      load-ewma-reconstructor, run-eevdf-scheduler,
 *                      run-rt-scheduler — all now in
 *                      task-storage/scheduling/. (This is what lived in
 *                      :data/scheduler.)
 *
 * So the sanctioned exception from the spec still exists and is still exactly
 * one edge — but it points task-storage -> task-scheduling, one way, and the
 * bridge file sits on the task-storage side where Task already lives.
 */
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
 * RESOLVED, but NOT from this capability — `Topics.REALTIME_WINDOW_EXPIRED`
 * is now genuinely published, from `task-list-screen`'s
 * `ListBuilderDelegate` (see its `rtResortRunnable`), not from anywhere in
 * `task-scheduling` itself.
 *
 * WHY THAT'S THE CORRECT PLACE, NOT A WORKAROUND
 * -------------------------------------------------
 * This manifest's own KDoc above states the layering explicitly: this
 * capability is PURE domain — "knows nothing about Task, RunLog, Room or
 * Android," "depends on no capability." Publishing to the kernel event bus
 * needs a live `EventBus` instance and a place to detect the actual
 * activation/deactivation TRANSITION, not just evaluate the current window
 * state — `RtPolicy.isWindowActive` here is a stateless, side-effect-free
 * function; it has no notion of "was active a moment ago" to compare
 * against. `ListBuilderDelegate` already has exactly that: a one-shot
 * `Handler` callback armed for the precise millisecond `RtScheduler`
 * (task-storage's Task-aware wrapper around this capability's `RtPolicy`)
 * computes as the next activation-or-deactivation boundary, plus the task
 * ids that were active when that callback was armed — the actual
 * transition-detection state this capability was never meant to hold.
 *
 * `publishes` above stays as declared: this capability's domain logic
 * (`RtPolicy.isWindowActive`) is still what task-list-screen's real-owner
 * code calls to answer the question the publish depends on, even though the
 * `bus.publish()` call itself lives one layer up.
 */

/**
 * DEFERRED, still not done:
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
