package com.eevdf.capabilities.taskstorage

import com.eevdf.capabilities.taskstorage.logic.SortHelper
import java.util.UUID

/**
 * Pure rules for FORK — copying one task or group into a new sibling, the way Linux `fork()` copies the
 * calling process: the copy keeps what the original IS (its settings) and none of what it has DONE
 * (its run history and scheduling position). No Android, no clock, no database.
 *
 * What the child gets:
 *   • COPIED — name (as given), description, priority, time slice, colour, task type and notice settings,
 *     scheduler class with its Deadline / Realtime settings, quota limits, load factor, default sleep length,
 *     semaphore settings, whether it is a group — and its parent group: a fork always lands in the SAME
 *     group as the original, like a forked process inherits its parent's cgroup. Nothing here lets the
 *     child land anywhere else.
 *   • RESET (a fork has done nothing yet) — run time, run count, timer and remaining time (a full slice
 *     again), scheduling position (virtual runtime, lag, deadline: [TaskRepository.insert] places it at the
 *     back like any new task), quota / Deadline budgets used so far, load averages, sleep, semaphore slot,
 *     creation time, and a fresh id.
 *   • DROPPED — the interrupt slot (only one task can hold it) and a pinned CPU share (a copy of a pinned
 *     share could push its group's siblings past 100%; the priority is kept, so it simply floats).
 *   • NOT COPIED — a group's children. A fork copies the one task it was called on, not what hangs under it.
 *
 * Its identity is requested as `kind:` and numbered by [TaskRepository.insert] with the smallest free ID in
 * that kind's pool ([IdentityRules]).
 */
object ForkRules {

    const val NAME_SUFFIX = " (fork)"

    /**
     * What the Fork page's picker offers: every task and group that is not finished, in the app's name order
     * (the first number in a name first, like the Queue). Sorted here, inside task-storage, so the page does not
     * have to reach into this capability's logic package for the comparator.
     */
    fun forkable(all: List<Task>): List<Task> =
        all.filter { !it.isCompleted }.sortedWith(SortHelper.taskNameComparator)

    /** What the name box starts with: "backup (fork)". */
    fun defaultName(sourceName: String): String = sourceName + NAME_SUFFIX

    /**
     * The kind the fork gets: the original's, except that a task with no kind yet (Undefined, or an identity
     * that is not assigned) becomes a Process — `fork` creates a process.
     */
    fun childKind(sourceIdentity: String): IdentityKind {
        val kind = IdentityRules.parseKind(sourceIdentity)
        return if (kind == null || kind == IdentityKind.UNDEFINED) IdentityKind.PROCESS else kind
    }

    /** The fork of [source], named [name]. Not saved: hand it to [TaskRepository.insert]. */
    fun child(
        source: Task,
        name: String,
        nowMs: Long,
        id: String = UUID.randomUUID().toString(),
    ): Task = source.copy(
        id = id,
        name = name,
        // identity: the kind only — insert() picks the number
        identity = "${childKind(source.identity).key}:",
        // dropped
        isInterrupt = false,
        interruptSlot = "A",
        pinnedShare = null,
        internalWeight = null,
        // reset: scheduling position (insert() places it)
        vruntime = 0.0, eligibleTime = 0.0, virtualDeadline = 0.0, lag = 0.0,
        // reset: timer and history
        remainingSeconds = source.timeSliceSeconds,
        isRunning = false, isCompleted = false,
        totalRunTime = 0L, runCount = 0,
        accumulatedMs = 0L, startTimeEpoch = 0L,
        createdAt = nowMs,
        isGroupExpanded = true,
        // reset: budgets used so far
        quotaPeriodStartEpoch = 0L, quotaUsedSeconds = 0L,
        dlPeriodStartEpoch = 0L, dlRuntimeUsedSeconds = 0L,
        // reset: load averages
        loadAverage = 0.0, loadLastUpdateEpoch = 0L,
        loadAvgCognitive = 0.0, loadLastUpdateCognitive = 0L,
        loadAvgPhysical = 0.0, loadLastUpdatePhysical = 0L,
        loadAvgEmotional = 0.0, loadLastUpdateEmotional = 0L,
        // reset: sleep and semaphore slot (insert() stamps the slot)
        sleepUntilEpoch = 0L, sleepLag = 0.0,
        semaphoreHeld = false, semaphoreQueuedAt = 0L,
    )
}
