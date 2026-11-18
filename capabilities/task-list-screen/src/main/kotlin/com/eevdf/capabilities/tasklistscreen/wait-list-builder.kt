package com.eevdf.capabilities.tasklistscreen

import com.eevdf.capabilities.taskstorage.SemaphoreRules
import com.eevdf.capabilities.taskstorage.Task
import com.eevdf.capabilities.taskstorage.TaskDisplayItem

/**
 * Wait-tab rows: every task waiting for a group-semaphore slot, oldest arrival
 * first, a waiting group followed by its contents. [all] is the non-completed
 * pool WITH sleeping and waiting tasks (`TaskViewModel.activeTasksIncludingSleeping`).
 *
 * Mirrors [SleepDelegate.buildSleepList]: the same "hidden, behaves as if it did
 * not exist" state, listed so the person can still find, finish, delete or move
 * it. Each root also carries its 1-based place in its parent's queue.
 */
internal fun buildWaitList(all: List<Task>): List<TaskDisplayItem> {
    val byParent  = all.groupBy { it.parentId }
    val positions = SemaphoreRules.queuePositions(all)
    val waiting   = SemaphoreRules.waitingCounts(all)

    fun counts(group: Task): Pair<Int, Int> {
        var groups = 0; var leaves = 0
        fun walk(id: String) {
            byParent[id].orEmpty().forEach { c ->
                if (c.isGroup) { groups++; walk(c.id) } else leaves++
            }
        }
        walk(group.id)
        return groups to leaves
    }

    val out = mutableListOf<TaskDisplayItem>()
    fun emit(task: Task, depth: Int) {
        val (g, l) = if (task.isGroup) counts(task) else 0 to 0
        out += TaskDisplayItem(
            task, depth,
            childGroupCount = g, childTaskCount = l,
            waitingCount = waiting[task.id] ?: 0,
            waitPosition = if (depth == 0) positions[task.id] ?: 0 else 0,
        )
        if (task.isGroup) byParent[task.id].orEmpty().sortedBy { it.name.lowercase() }.forEach { emit(it, depth + 1) }
    }
    SemaphoreRules.waitingRoots(all).forEach { emit(it, 0) }
    return out
}
