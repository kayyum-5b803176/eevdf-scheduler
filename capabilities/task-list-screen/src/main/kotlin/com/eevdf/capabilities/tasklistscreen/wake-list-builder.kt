package com.eevdf.capabilities.tasklistscreen

import com.eevdf.capabilities.taskstorage.Task
import com.eevdf.capabilities.taskstorage.TaskDisplayItem
import com.eevdf.capabilities.taskstorage.TaskWake

/**
 * Wake-tab rows: every task whose scheduled wake is ARMED (a wake waiting for its time, or one
 * that has fired and whose preempt card is waiting for an answer — the same tasks whose wake icon
 * blinks), soonest due first, so a wake already waiting for an answer comes first.
 *
 * [all] is the non-completed pool WITH sleeping and waiting tasks
 * (`TaskViewModel.activeTasksIncludingSleeping`); [wakes] is the whole `task_wake` table. Only
 * leaf tasks are listed — a wake is set on a task, never on a group — and a wake whose task is
 * gone or completed is skipped (those rows are cleaned up lazily, when they come due).
 *
 * Mirrors [buildWaitList] and `SleepDelegate.buildSleepList`: flat rows, view-only. Unlike them an
 * armed wake hides nothing — the task also stays in Queue / Schedule — so this tab only lists it.
 * The due time of each row is read by the adapter from the same wake table.
 */
internal fun buildWakeList(all: List<Task>, wakes: List<TaskWake>): List<TaskDisplayItem> {
    val byId = all.associateBy { it.id }
    return wakes
        .filter { it.isArmed }
        .sortedBy { it.targetEpochMs }
        .mapNotNull { wake -> byId[wake.taskId]?.takeIf { !it.isGroup }?.let { TaskDisplayItem(it, 0) } }
}
