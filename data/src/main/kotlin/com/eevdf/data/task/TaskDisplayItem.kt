package com.eevdf.data.task

/**
 * Wraps a Task for flat-list rendering in the RecyclerView.
 * The ViewModel flattens the task tree into this list, respecting group
 * expand/collapse state. The adapter uses [depth] for indentation and
 * [childCount]/[childTotalRuntime] to render group summary rows.
 */
data class TaskDisplayItem(
    val task: Task,
    val depth: Int,
    val childCount: Int = 0,          // only meaningful when task.isGroup == true
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
    val isExpanded: Boolean = true
)
