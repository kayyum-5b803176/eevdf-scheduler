package com.eevdf.data.scheduler

import android.content.SharedPreferences
import com.eevdf.data.task.Task
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Injectable, stateless wrapper over the [RtScheduler] `object` (SCHED_FIFO /
 * SCHED_RR realtime-window logic). Delegates 1:1; behavior is unchanged. RR
 * round-robin position is still persisted in the caller-supplied
 * [SharedPreferences], exactly as before — this class adds no state of its own.
 */
@Singleton
class RtSchedulerService @Inject constructor() {

    fun isRtWindowActive(task: Task, nowMs: Long = System.currentTimeMillis()): Boolean =
        RtScheduler.isRtWindowActive(task, nowMs)

    fun nextActivationMs(task: Task, nowMs: Long = System.currentTimeMillis()): Long =
        RtScheduler.nextActivationMs(task, nowMs)

    fun nextDeactivationMs(task: Task, nowMs: Long = System.currentTimeMillis()): Long =
        RtScheduler.nextDeactivationMs(task, nowMs)

    fun nextResortMs(tasks: List<Task>, nowMs: Long = System.currentTimeMillis()): Long =
        RtScheduler.nextResortMs(tasks, nowMs)

    fun pickRrTask(
        activeTasks: List<Task>,
        prefs: SharedPreferences,
        nowMs: Long = System.currentTimeMillis(),
    ): Task? = RtScheduler.pickRrTask(activeTasks, prefs, nowMs)

    fun advanceRrIndex(prefs: SharedPreferences, cohortSize: Int) =
        RtScheduler.advanceRrIndex(prefs, cohortSize)

    fun clearRrState(prefs: SharedPreferences) =
        RtScheduler.clearRrState(prefs)

    fun hasActiveRtDescendant(
        task: Task, allTasks: List<Task>, nowMs: Long = System.currentTimeMillis(),
    ): Boolean = RtScheduler.hasActiveRtDescendant(task, allTasks, nowMs)

    fun minRtUrgency(task: Task, allTasks: List<Task>): Long =
        RtScheduler.minRtUrgency(task, allTasks)
}

/**
 * Injectable, stateless wrapper over the [LoadAverage] `object` (Linux-style
 * EWMA load average). Pure functions, delegated 1:1; identical numerics.
 */
@Singleton
class LoadAverageService @Inject constructor() {

    val windowSeconds: Double get() = LoadAverage.LOAD_WINDOW_SECONDS

    fun advanced(task: Task, nowEpoch: Long, isRunning: Boolean): Task =
        LoadAverage.advanced(task, nowEpoch, isRunning)

    fun currentValue(task: Task, nowEpoch: Long, isRunning: Boolean): Double =
        LoadAverage.currentValue(task, nowEpoch, isRunning)

    fun systemLoad(tasks: List<Task>, nowEpoch: Long, runningId: String?): Double =
        LoadAverage.systemLoad(tasks, nowEpoch, runningId)
}
