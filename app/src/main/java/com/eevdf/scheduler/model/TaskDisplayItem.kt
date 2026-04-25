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
    val cpuShare: Double = 0.0        // real-time CPU share % from EEVDFScheduler.computeShares()
)
