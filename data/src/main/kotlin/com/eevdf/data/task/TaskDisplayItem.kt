package com.eevdf.data.task

/**
 * Wraps a Task for flat-list rendering in the RecyclerView.
 * The ViewModel flattens the task tree into this list, respecting group
 * expand/collapse state. The adapter uses [depth] for indentation and
 * [childGroupCount]/[childTaskCount]/[childTotalRuntime] to render group summary rows.
 */
data class TaskDisplayItem(
    val task: Task,
    val depth: Int,
    /** Total number of descendant groups at any depth — only meaningful when task.isGroup == true. */
    val childGroupCount: Int = 0,
    /** Total number of descendant leaf tasks at any depth — only meaningful when task.isGroup == true. */
    val childTaskCount: Int = 0,
    val childTotalRuntime: Long = 0L, // sum of all direct children's totalRunTime

    val cpuShare: Double = 0.0,       // real-time CPU share % from EEVDFScheduler.computeShares()
    /**
     * True when this task itself OR any ancestor group has its quota exceeded for
     * the current period.  Mirrors Linux cgroup bandwidth throttling propagating
     * down the hierarchy — a child cannot run if its parent's budget is exhausted.
     */
    val effectiveQuotaExceeded: Boolean = false,
    /**
     * True when this task itself OR any ancestor group is in the quota warning zone
     * (≥ 80 % consumed but not yet exceeded). Shown as amber pre-warning tint.
     */
    val effectiveQuotaWarning: Boolean = false,
    /**
     * Hierarchical queue position label for display in the schedule tab.
     * Top-level: "1", "2", "3"
     * First-level children: "1.1", "1.2", "2.1"
     * Deeper children: "1.1.1", "1.1.2", etc.
     * Empty string when no number is assigned (e.g. non-schedule tabs).
     */
    val queueNumber: String = "",

    /**
     * True when this task has [Task.schedulerClass] == "dl_sched_class" and still
     * has runtime budget remaining in the current DL period.  These tasks are
     * hoisted to rank #1 in the Schedule tab ahead of all EEVDF-ordered tasks.
     * Stamped at list-build time from [Task.isDlBudgetActive].
     */
    val isDlActive: Boolean = false,

    /**
     * True when this task is a GROUP that has been hoisted to rank #1 in the
     * Schedule tab because at least one descendant leaf has an active
     * SCHED_DEADLINE budget ([Task.isDlBudgetActive] == true).
     *
     * Mirrors Linux cgroup-aware SCHED_DEADLINE promotion: when a deadline
     * task inside a cgroup needs time, the entire root-level group entity is
     * elevated to the top of the run-queue — exactly the same behaviour as a
     * standalone deadline task at root level.
     *
     * Used by the adapter to render the same "DL active" badge on group rows
     * that it already renders on individual deadline tasks.
     */
    val isDlGroupHoisted: Boolean = false,

    /**
     * True when this task has [Task.schedulerClass] == "rt_sched_class" and the
     * current wall-clock time falls inside its activation window.  These tasks
     * are hoisted to rank #2 on the Schedule tab (below dl_sched_class, above
     * all EEVDF-ordered tasks).  Stamped at list-build time from
     * [RtScheduler.isRtWindowActive].
     */
    val isRtActive: Boolean = false,

    /**
     * True when this task is a GROUP hoisted because at least one descendant
     * leaf is inside its RT activation window.  Mirrors [isDlGroupHoisted] for
     * the RT class.
     */
    val isRtGroupHoisted: Boolean = false,

    /**
     * For group tasks: whether this group is currently expanded (children visible).
     * Stamped at list-build time from the ViewModel expand-state maps so that
     * DiffUtil detects a content change when the group is toggled and triggers
     * a rebind — ensuring the arrow icon rotation updates correctly.
     * Always true for leaf tasks (expand state is not meaningful).
     */
    val isExpanded: Boolean = true,

    // ── Links feature: symlinks + hardlinks ───────────────────────────────────

    /**
     * Non-null when this row is a SYMLINK pointer (see [TaskLink]), not the
     * real task. [task] is a live, read-only snapshot of the target so name/
     * running-state always display current — but this row carries no weight
     * in [cpuShare]/EEVDF computation, and tapping its timer icon must
     * navigate to the target's real location rather than run anything here.
     */
    val symlinkId: String? = null,

    /**
     * Non-null when this row represents a HARDLINK placement (see
     * [TaskMembership]) rather than [task]'s real, primary parent. [task]'s
     * own config (name, priority, etc.) is genuinely shared and shown as-is,
     * but [cpuShare]/vruntime/[childTotalRuntime] credit for this row come
     * from that one placement's own fields, not [task]'s primary ones.
     */
    val membershipId: String? = null,
    /** True for the one row a drill-down symlink jump landed on — see
     *  DrillFrame.highlightTaskId. Purely cosmetic (card tint), no effect on
     *  scheduling or ordering. */
    val isJumpHighlighted: Boolean = false,
    /** True for a symlink row whose target has been deleted — see [TaskLink]
     *  doc comment. [task] is a synthetic placeholder in this case, never a
     *  real DB row; the row renders disabled/greyed with delete as the only
     *  available action. */
    val isBrokenLink: Boolean = false,
    /**
     * The "door" this row was reached through, if any — a [TaskMembership]
     * id whose [TaskMembership.taskId] is this row's own real task, OR an
     * ancestor of it, that the user is currently viewing THROUGH. Inherited
     * downward through an entire hardlinked group's real subtree (see
     * [ListBuilderDelegate]): every real descendant rendered underneath a
     * hardlink placement carries the SAME door, so that running any of them
     * credits the group they're being viewed inside, not the group they
     * happen to physically live in. `null` means "reached only via real
     * parentId edges" — the plain, ordinary case.
     */
    val entryMembershipId: String? = null,
    /**
     * Display-only vrt/vdl for a MEMBERSHIP (hardlink) row, computed from that
     * placement's own vruntime — see [TaskMembership] doc comment. [task]
     * itself is ALWAYS the pristine, unmodified real row; these two fields
     * are the ONLY place a placement's vrt/vdl live for rendering purposes.
     *
     * This exists specifically so [task] is safe to seed as the app's
     * "currently selected/running task" and safe to pass into any
     * `TaskRepository.update(...)` call without risk — a full-row `@Update`
     * on a `Task` object that had its own vruntime field overwritten for
     * display would silently persist that borrowed value onto the REAL row
     * the moment the timer starts, long before any accounting even runs.
     * That was a real, shipped bug; see git history / ARCHITECTURE.md for
     * the trace. Null for every non-membership row — the adapter falls back
     * to `task.vruntime`/`task.virtualDeadline` in that case.
     */
    val displayVruntime: Double? = null,
    val displayVirtualDeadline: Double? = null,
    /**
     * True when this row's REAL task/group (never set for a symlink or
     * membership row itself — those are already identified by
     * [symlinkId]/[membershipId]) is the TARGET of at least one symlink or
     * the taskId of at least one hardlink placement elsewhere in the app.
     * Drives the "R" square badge — see [membershipId]/[symlinkId] for the
     * "H"/"S" counterparts. False (badge hidden) for the vast majority of
     * ordinary, never-linked tasks.
     */
    val isLinkedElsewhere: Boolean = false,
) {
    /** True for either a [symlinkId] or [membershipId] row — a "links" row, not the task's primary appearance. */
    val isLinkRow: Boolean get() = symlinkId != null || membershipId != null
}
