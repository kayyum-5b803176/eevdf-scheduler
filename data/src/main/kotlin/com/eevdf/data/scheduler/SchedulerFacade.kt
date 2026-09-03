package com.eevdf.data.scheduler

import com.eevdf.core.scheduler.eevdf.CpuShares
import com.eevdf.core.scheduler.eevdf.EevdfScheduler
import com.eevdf.core.scheduler.model.DlBudget
import com.eevdf.core.scheduler.model.QuotaBudget
import com.eevdf.core.scheduler.model.RtConfig
import com.eevdf.core.scheduler.model.SchedTask
import com.eevdf.data.task.Task
import com.eevdf.data.task.TaskMembership

/**
 * Bridge between the rich Room [Task] entity (used by the UI) and the pure
 * scheduler core. The UI keeps calling `EEVDFScheduler.recalculate(...)` etc.
 * with the same signatures it always had; under the hood the math now runs in
 * the pure, single-pass, deterministic core (`com.eevdf.core.scheduler.*`).
 *
 * This is the deliberate compromise: the pure core never mutates; this adapter
 * applies the core's results back onto the entity's `var` fields to preserve
 * the legacy mutate-in-place behaviour the callers expect. Callers can be
 * migrated off mutation incrementally without touching the core.
 */

internal fun Task.toSched(): SchedTask = SchedTask(
    id = id, parentId = parentId, isGroup = isGroup, isCompleted = isCompleted,
    isRunning = isRunning, priority = priority, internalWeight = internalWeight,
    pinnedShare = pinnedShare, timeSliceSeconds = timeSliceSeconds, vruntime = vruntime,
    eligibleTime = eligibleTime, virtualDeadline = virtualDeadline, lag = lag,
    totalRunTime = totalRunTime, runCount = runCount, schedulerClass = schedulerClass,
    rt = if (schedulerClass == SchedTask.RT) RtConfig(
        priority = rtPriority,
        policy = if (rtPolicy == "FIFO") RtConfig.Policy.FIFO else RtConfig.Policy.RR,
        activeDaysMask = rtActiveDays, activationHour = rtActivationHour,
        activationMinute = rtActivationMinute, activationSecond = rtActivationSecond,
        sliceTimeoutSeconds = rtSliceTimeoutSeconds,
    ) else null,
    dl = if (schedulerClass == SchedTask.DL) DlBudget(
        runtimeSeconds = dlRuntimeSeconds, deadlineSeconds = dlDeadlineSeconds,
        periodSeconds = dlPeriodSeconds, periodStartEpochSeconds = dlPeriodStartEpoch / 1000L,
        runtimeUsedSeconds = dlRuntimeUsedSeconds,
    ) else null,
    quota = if (quotaSeconds > 0L) QuotaBudget(
        quotaSeconds = quotaSeconds, periodSeconds = quotaPeriodSeconds,
        periodStartEpochSeconds = quotaPeriodStartEpoch / 1000L, usedSeconds = quotaUsedSeconds,
    ) else null,
)

data class SchedulerStats(
    val totalTasks: Int,
    val activeTasks: Int,
    val completedTasks: Int,
    val averageVruntime: Double,
    val totalWeight: Double,
    val fairnessScore: Double,
    val systemLoad: Double = 0.0,
)

/** Prefix marking a synthetic id built by [EEVDFScheduler.withMemberships] — never a real Task.id. */
const val MEMBERSHIP_SYNTHETIC_PREFIX = "membership:"

object EEVDFScheduler {

    /**
     * Builds an "effective" task list for scheduling/share computation: every
     * real [tasks] entry, PLUS one synthetic [Task] copy per hardlink
     * placement in [memberships] — each a full stand-in for its real task,
     * with `id` swapped to a synthetic, prefixed value, `parentId` set to the
     * placement's [TaskMembership.groupId], and `vruntime` swapped for this
     * placement's own — the one thing that's genuinely placement-relative
     * (see [TaskMembership] doc comment).
     *
     * Deliberately does NOT touch `totalRunTime`/`runCount` — those are
     * shared, lifetime statistics that live on the real task and must read
     * the same everywhere; the `.copy()` below already carries them over
     * unmodified, which is exactly correct. Also does NOT touch
     * `eligibleTime`/`virtualDeadline`/`lag` — those are pure functions of
     * `vruntime` plus the (already-shared, already-correct) `weight`/
     * `timeSliceSeconds` on the copy, and get recomputed fresh internally by
     * `EevdfScheduler.recalculate()` before [getScheduleOrder] ever uses
     * them; whatever stale values ride along on the copy are discarded before
     * they could matter.
     *
     * This lets a hardlinked task compete as a genuine child in TWO (or more)
     * different parents' pools at once without any change to the pure EEVDF
     * core: [computeShares]/[getScheduleOrder]/[recalculate] only ever see a
     * flat `List<Task>` shaped by `parentId` — they cannot tell a synthetic
     * membership entry apart from a real one, which is exactly the point.
     *
     * Callers that persist results back must recognise
     * [MEMBERSHIP_SYNTHETIC_PREFIX] and write them onto the [TaskMembership]
     * row's `vruntime` (via `id.removePrefix(...)`), never onto a real [Task]
     * row and never onto any field but `vruntime` — see
     * [com.eevdf.data.task.TaskRepository.creditMembershipRun].
     *
     * Scope note: if the hardlinked task is itself a GROUP, its real children
     * are still looked up by its REAL id elsewhere (list building) — this
     * function does not attempt to also give those grandchildren a synthetic
     * membership-relative identity. The group's own placement-specific weight
     * competes correctly; composing a full nested subtree through multiple
     * simultaneous parents is out of scope for v1.
     */
    fun withMemberships(tasks: List<Task>, memberships: List<TaskMembership>): List<Task> {
        if (memberships.isEmpty()) return tasks
        val byTaskId = tasks.associateBy { it.id }
        val synthetic = memberships.mapNotNull { m ->
            val real = byTaskId[m.taskId] ?: return@mapNotNull null
            real.copy(
                id       = MEMBERSHIP_SYNTHETIC_PREFIX + m.id,
                parentId = m.groupId,
                vruntime = m.vruntime,
            )
        }
        return tasks + synthetic
    }

    fun totalWeight(tasks: List<Task>): Double = EevdfScheduler.totalWeight(tasks.map { it.toSched() })

    fun averageVruntime(tasks: List<Task>): Double = EevdfScheduler.averageVruntime(tasks.map { it.toSched() })

    /** Recalculate lag/eligible/deadline; writes results back onto the entities. */
    fun recalculate(tasks: List<Task>) {
        val byId = tasks.associateBy { it.id }
        EevdfScheduler.recalculate(tasks.map { it.toSched() }).forEach { s ->
            byId[s.id]?.apply { lag = s.lag; eligibleTime = s.eligibleTime; virtualDeadline = s.virtualDeadline }
        }
    }

    fun selectNext(tasks: List<Task>): Task? {
        val recalced = EevdfScheduler.recalculate(tasks.map { it.toSched() })
        val winner = EevdfScheduler.selectNext(recalced) ?: return null
        return tasks.firstOrNull { it.id == winner.id }
    }

    /** Place a brand-new task at its sibling average vruntime (anti-starvation). */
    fun placeNewTask(task: Task, existingTasks: List<Task>) {
        task.vruntime = EevdfScheduler.initialVruntime(task.toSched(), existingTasks.map { it.toSched() })
    }

    fun updateVruntime(task: Task, secondsRan: Long) {
        task.vruntime = EevdfScheduler.advanceVruntime(task.toSched(), secondsRan)
        task.totalRunTime += secondsRan
        task.runCount++
    }

    fun getScheduleOrder(tasks: List<Task>): List<Task> {
        val byId = tasks.associateBy { it.id }
        return EevdfScheduler.scheduleOrder(tasks.map { it.toSched() }).mapNotNull { byId[it.id] }
    }

    fun computeShares(tasks: List<Task>, groupsEnabled: Boolean = false): Map<String, Double> =
        CpuShares.computeShares(tasks.map { it.toSched() }, groupsEnabled)

    fun otherPinnedTotal(tasks: List<Task>, excludeId: String?, parentId: String?): Double =
        tasks.filter { !it.isCompleted && it.pinnedShare != null && it.id != excludeId && it.parentId == parentId }
            .sumOf { it.pinnedShare!! }

    fun calcPinnedWeight(
        targetShare: Double, parentId: String?, excludeId: String?,
        allTasks: List<Task>, fallbackWeight: Double = 4.0,
    ): Double = CpuShares.pinnedWeight(targetShare, parentId, excludeId, allTasks.map { it.toSched() }, fallbackWeight)

    /** Returns entity copies whose internal weight changed (minimal persist diff). */
    fun syncPinnedWeights(allTasks: List<Task>): List<Task> {
        val byId = allTasks.associateBy { it.id }
        return CpuShares.syncPinnedWeights(allTasks.map { it.toSched() })
            .mapNotNull { s -> byId[s.id]?.copy(internalWeight = s.internalWeight) }
    }

    fun hasActiveDlDescendant(task: Task, allTasks: List<Task>): Boolean {
        if (!task.isGroup) return task.isDlBudgetActive
        // A group with its OWN active DL budget counts — without this check a
        // DL-class group with only CFS children returns false, breaking the
        // upward chain: grandparent groups would never see it as a DL entity.
        if (task.isDlBudgetActive) return true
        return allTasks.filter { it.parentId == task.id && !it.isCompleted }.any { child ->
            if (child.isGroup) hasActiveDlDescendant(child, allTasks) else child.isDlBudgetActive
        }
    }

    fun getStats(tasks: List<Task>, groupsEnabled: Boolean = false, runningId: String? = null): SchedulerStats {
        val active = tasks.filter { !it.isCompleted }
        val completed = tasks.filter { it.isCompleted }
        // System load = sum of all tasks' current (lazily-decayed) load averages.
        val load = LoadAverage.systemLoad(tasks, System.currentTimeMillis(), runningId)
        return if (!groupsEnabled) {
            SchedulerStats(
                totalTasks = tasks.size, activeTasks = active.size, completedTasks = completed.size,
                averageVruntime = averageVruntime(tasks), totalWeight = totalWeight(active),
                fairnessScore = CpuShares.fairness(tasks.map { it.toSched() }),
                systemLoad = load,
            )
        } else {
            val leaves = countReachableLeaves(active)
            val (w, vr, fair) = aggregate(active, active.filter { it.parentId == null })
            SchedulerStats(tasks.size, leaves, completed.size, vr, w, fair, load)
        }
    }

    // ── group-aware aggregation (ported from reference, on Task) ──
    private fun aggregate(all: List<Task>, siblings: List<Task>): Triple<Double, Double, Double> {
        if (siblings.isEmpty()) return Triple(0.0, 0.0, 1.0)
        val weights = mutableListOf<Double>(); val vrts = mutableListOf<Double>()
        for (s in siblings) {
            if (!s.isGroup) { if (s.weight > 0.0) { weights += s.weight; vrts += s.vruntime } }
            else {
                val children = all.filter { it.parentId == s.id }
                if (children.isNotEmpty()) {
                    val (cw, cv, _) = aggregate(all, children)
                    if (cw > 0.0) { weights += cw; vrts += cv }
                }
            }
        }
        if (weights.isEmpty()) return Triple(0.0, 0.0, 1.0)
        val tw = weights.sum()
        val avg = if (tw > 0.0) weights.zip(vrts).sumOf { (a, b) -> a * b } / tw else 0.0
        val fair = if (vrts.size < 2) 1.0 else {
            val sum = vrts.sum(); val sq = vrts.sumOf { it * it }
            if (sq == 0.0) 1.0 else (sum * sum) / (vrts.size * sq)
        }
        return Triple(tw, avg, fair)
    }

    private fun countReachableLeaves(active: List<Task>): Int {
        val map = active.associateBy { it.id }
        fun chainAllGroups(t: Task): Boolean {
            val p = t.parentId ?: return true
            val parent = map[p] ?: return true
            return parent.isGroup && chainAllGroups(parent)
        }
        return active.count { !it.isGroup && chainAllGroups(it) }
    }
}
