package com.eevdf.data.scheduler

import com.eevdf.data.task.Task
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Injectable, stateless front door to the EEVDF scheduling math.
 *
 * The actual algorithm lives in the pure JVM core (`com.eevdf.core.scheduler.*`)
 * and is bridged by the [EEVDFScheduler] `object` facade. This class delegates
 * 1:1 to that facade so the scheduling behavior is byte-for-byte identical —
 * its only job is to turn a global singleton into a constructor-injectable
 * dependency, so call sites depend on an instance they are *given* rather than
 * a hard-coded `object` reference.
 *
 * It is `@Singleton` and holds no mutable state, so sharing one instance across
 * the graph is safe and matches the previous `object` semantics exactly.
 */
@Singleton
class EevdfSchedulerService @Inject constructor() {

    fun totalWeight(tasks: List<Task>): Double =
        EEVDFScheduler.totalWeight(tasks)

    fun averageVruntime(tasks: List<Task>): Double =
        EEVDFScheduler.averageVruntime(tasks)

    fun recalculate(tasks: List<Task>) =
        EEVDFScheduler.recalculate(tasks)

    fun selectNext(tasks: List<Task>): Task? =
        EEVDFScheduler.selectNext(tasks)

    fun placeNewTask(task: Task, existingTasks: List<Task>) =
        EEVDFScheduler.placeNewTask(task, existingTasks)

    fun updateVruntime(task: Task, secondsRan: Long) =
        EEVDFScheduler.updateVruntime(task, secondsRan)

    fun getScheduleOrder(tasks: List<Task>): List<Task> =
        EEVDFScheduler.getScheduleOrder(tasks)

    fun computeShares(tasks: List<Task>, groupsEnabled: Boolean = false): Map<String, Double> =
        EEVDFScheduler.computeShares(tasks, groupsEnabled)

    fun otherPinnedTotal(tasks: List<Task>, excludeId: String?, parentId: String?): Double =
        EEVDFScheduler.otherPinnedTotal(tasks, excludeId, parentId)

    fun calcPinnedWeight(
        targetShare: Double, parentId: String?, excludeId: String?,
        allTasks: List<Task>, fallbackWeight: Double = 4.0,
    ): Double = EEVDFScheduler.calcPinnedWeight(targetShare, parentId, excludeId, allTasks, fallbackWeight)

    fun syncPinnedWeights(allTasks: List<Task>): List<Task> =
        EEVDFScheduler.syncPinnedWeights(allTasks)

    fun hasActiveDlDescendant(task: Task, allTasks: List<Task>): Boolean =
        EEVDFScheduler.hasActiveDlDescendant(task, allTasks)

    fun getStats(
        tasks: List<Task>, groupsEnabled: Boolean = false, runningId: String? = null,
    ): SchedulerStats = EEVDFScheduler.getStats(tasks, groupsEnabled, runningId)
}
