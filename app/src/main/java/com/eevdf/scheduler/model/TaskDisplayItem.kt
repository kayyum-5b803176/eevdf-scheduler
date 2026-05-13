package com.eevdf.scheduler.model

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
    val isDlActive: Boolean = false
)
