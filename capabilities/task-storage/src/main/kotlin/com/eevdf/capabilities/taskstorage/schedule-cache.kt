package com.eevdf.capabilities.taskstorage

/**
 * The three columns of `tasks` that are a CACHE of what the EEVDF scheduler
 * works out from the others (`lag`, `eligibleTime`, `virtualDeadline`).
 *
 * Used as a Room "partial entity" so they can be rewritten without touching
 * any other column of the row — see [TaskDao.updateScheduleCache]. Multi-user
 * sync deliberately does not share these columns (they would otherwise be
 * rewritten on every pause for every task); each user recomputes its own after
 * a sync via [TaskRepository.recalculateScheduleCache].
 */
data class ScheduleCache(
    val id: String,
    val lag: Double,
    val eligibleTime: Double,
    val virtualDeadline: Double,
)
