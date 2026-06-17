package com.eevdf.core.scheduler.eevdf

import com.eevdf.core.scheduler.model.SchedTask
import kotlin.math.abs

/**
 * CPU-share allocation and fairness metrics. Ported from the reference
 * `EEVDFScheduler` share/fairness methods onto the pure [SchedTask] model.
 *
 * Logic kept verbatim in spirit (it is correct and non-trivial): pinned tasks
 * get exactly their %, the remainder is split among floaters proportional to
 * weight, scoped per cgroup level; pinned weight is back-calculated; Jain's
 * fairness index aggregates bottom-up through the group tree.
 *
 * Cleanups: `Math.abs` → `kotlin.math.abs`; no in-place mutation; single source
 * for the Jain formula.
 */
object CpuShares {

    private const val MAX_INTERNAL_WEIGHT = 9_999.0

    /** Map of task.id → CPU share (0.0–100.0). Hierarchical when [groupsEnabled]. */
    fun computeShares(tasks: List<SchedTask>, groupsEnabled: Boolean = false): Map<String, Double> {
        if (!groupsEnabled) return sharesAtLevel(tasks.filter { !it.isCompleted })
        val result = mutableMapOf<String, Double>()
        fun level(parentId: String?) {
            val levelTasks = tasks.filter { !it.isCompleted && it.parentId == parentId }
            if (levelTasks.isEmpty()) return
            result.putAll(sharesAtLevel(levelTasks))
            levelTasks.filter { it.isGroup }.forEach { level(it.id) }
        }
        level(null)
        return result
    }

    private fun sharesAtLevel(levelTasks: List<SchedTask>): Map<String, Double> {
        val pinnedTotal = levelTasks.filter { it.pinnedShare != null }.sumOf { it.pinnedShare!! }
        val floatPool = (100.0 - pinnedTotal).coerceAtLeast(0.0)
        val floatWeight = levelTasks.filter { it.pinnedShare == null }.sumOf { it.weight }.coerceAtLeast(1.0)
        return levelTasks.associate { t ->
            t.id to (t.pinnedShare ?: ((t.weight / floatWeight) * floatPool))
        }
    }

    /** Back-calculate the internal weight that yields [targetShare]% within the sibling float pool. */
    fun pinnedWeight(
        targetShare: Double,
        parentId: String?,
        excludeId: String?,
        allTasks: List<SchedTask>,
        fallbackWeight: Double,
    ): Double {
        val siblings = allTasks.filter { !it.isCompleted && it.id != excludeId && it.parentId == parentId }
        val siblingPinned = siblings.filter { it.pinnedShare != null }.sumOf { it.pinnedShare!! }
        val floatPool = (100.0 - siblingPinned).coerceAtLeast(0.0)
        val otherFloatWeight = siblings.filter { it.pinnedShare == null }.sumOf { it.weight }
        val denominator = floatPool - targetShare
        return when {
            denominator <= 0.0 -> MAX_INTERNAL_WEIGHT
            otherFloatWeight == 0.0 -> fallbackWeight
            else -> (targetShare * otherFloatWeight) / denominator
        }
    }

    /** Returns copies of pinned tasks whose internal weight materially changed. */
    fun syncPinnedWeights(allTasks: List<SchedTask>): List<SchedTask> {
        val active = allTasks.filter { !it.isCompleted }
        return active.filter { it.pinnedShare != null }.mapNotNull { t ->
            val w = pinnedWeight(t.pinnedShare!!, t.parentId, t.id, active, t.priority.toDouble())
            if (t.internalWeight == null || abs(t.internalWeight - w) > 1e-9) t.copy(internalWeight = w) else null
        }
    }

    /** Jain's fairness index over a vruntime list: J = (Σv)² / (n·Σv²). 1.0 = perfectly fair. */
    fun fairness(tasks: List<SchedTask>): Double = jain(tasks.filter { !it.isCompleted }.map { it.vruntime })

    private fun jain(vrts: List<Double>): Double {
        if (vrts.size < 2) return 1.0
        val n = vrts.size.toDouble()
        val sum = vrts.sum()
        val sumSq = vrts.sumOf { it * it }
        return if (sumSq == 0.0) 1.0 else (sum * sum) / (n * sumSq)
    }
}
