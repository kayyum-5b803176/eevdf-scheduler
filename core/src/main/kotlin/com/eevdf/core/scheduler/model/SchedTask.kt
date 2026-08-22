package com.eevdf.core.scheduler.model

/**
 * Pure, immutable scheduling view of a task.
 *
 * In the reference implementation the algorithm operated directly on the Room
 * `@Entity` `Task`: one ~50-field class was persistence row + scheduler state +
 * UI object, and the scheduler mutated it in place. That coupling is the root
 * cause of "a new feature breaks an old one".
 *
 * [SchedTask] carries ONLY what the scheduler reasons about, is immutable, and
 * has no Android/Room/time imports. Class-specific configuration lives in
 * cohesive value objects ([RtConfig], [DlBudget], [QuotaBudget]) that own their
 * own rules, so adding (say) a new quota behaviour can't perturb EEVDF logic.
 *
 * Scheduling outputs come back as new copies (see EevdfScheduler); inputs are
 * never mutated.
 */
data class SchedTask(
    val id: String,
    val parentId: String?,
    val isGroup: Boolean,
    val isCompleted: Boolean,
    val isRunning: Boolean,

    /** User-facing priority (nice-like). Higher = more CPU share. */
    val priority: Int,

    /** Auto-derived weight from a pinned share, or null when the task floats on priority. */
    val internalWeight: Double? = null,

    /** Fixed CPU share in percent (0–100), or null = auto-float by weight. */
    val pinnedShare: Double? = null,

    /** Requested slice length in seconds (the EEVDF "request"). */
    val timeSliceSeconds: Long,

    // ── EEVDF state (inputs; refreshed copies returned by the scheduler) ──

    /**
     * Virtual runtime for this entity **at its own parent's runqueue level**.
     *
     * For a leaf task ([isGroup] == false): time this task has run, normalised
     * by weight.  Directly advanced by [EevdfScheduler.advanceVruntime].
     *
     * For a group entity ([isGroup] == true): the group's participation token
     * in its *parent's* EEVDF contest.  Advanced by
     * [EevdfScheduler.advanceVruntimeHierarchical] each time any descendant
     * runs, so the group competes fairly (e.g. against task-a) using its own
     * [timeSliceSeconds] and [weight] — completely independent of the child
     * vruntimes, which live one level down inside the group's own runqueue.
     *
     * This dual-level semantics mirrors Linux's per-cgroup `cfs_rq` +
     * group scheduling entity: the same struct field is reused at the
     * appropriate level without a separate storage field.
     */
    val vruntime: Double = 0.0,
    val eligibleTime: Double = 0.0,
    val virtualDeadline: Double = 0.0,
    val lag: Double = 0.0,

    val totalRunTime: Long = 0L,
    val runCount: Int = 0,

    /** "fair_sched_class" | "rt_sched_class" | "dl_sched_class". */
    val schedulerClass: String = FAIR,

    // ── Class-specific config (null unless that class is used) ──
    val rt: RtConfig? = null,
    val dl: DlBudget? = null,
    val quota: QuotaBudget? = null,
) {
    /** Effective EEVDF weight: pinned-derived weight if present, else priority. */
    val weight: Double get() = internalWeight ?: priority.toDouble()

    val isFair: Boolean get() = schedulerClass == FAIR
    val isRtClass: Boolean get() = schedulerClass == RT
    val isDlClass: Boolean get() = schedulerClass == DL

    /** RT validly configured for this class. */
    val isRtConfigured: Boolean get() = isRtClass && (rt?.isConfigured == true)

    /** DL validly configured for this class. */
    val isDlConfigured: Boolean get() = isDlClass && (dl?.isConfigured == true)

    companion object {
        const val FAIR = "fair_sched_class"
        const val RT = "rt_sched_class"
        const val DL = "dl_sched_class"
    }
}
