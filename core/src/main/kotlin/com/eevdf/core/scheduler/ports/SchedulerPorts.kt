package com.eevdf.core.scheduler.ports

import com.eevdf.core.scheduler.model.SchedTask

/**
 * Ports owned by the CORE subsystem. The data/ and platform/ subsystems
 * IMPLEMENT these; core never imports them. This inverts the reference
 * dependency `db.TaskRepository -> scheduler.EEVDFScheduler` (a persistence
 * layer reaching up into business logic) into the clean direction:
 *
 *     data  ──implements──▶  core port  ◀──depends on──  core use-cases
 *
 * Result: the scheduling policy can change without touching persistence, and a
 * new persistence backend (or an in-memory fake for tests) can be swapped in
 * without touching the scheduler.
 */

/** Read/write access to the schedulable task set, in pure domain terms. */
interface TaskQueuePort {
    suspend fun activeTasks(): List<SchedTask>
    suspend fun childrenOf(parentId: String?): List<SchedTask>
    suspend fun save(task: SchedTask)
    suspend fun saveAll(tasks: List<SchedTask>)
}

/**
 * SCHED_RR round-robin cursor persistence. In the reference this lived as a raw
 * `SharedPreferences` read INSIDE the core RtScheduler — a platform import in
 * pure logic. Behind this port, core stays pure and platform decides storage.
 */
interface RrStatePort {
    fun currentIndex(cohortKey: String): Int
    fun setIndex(cohortKey: String, index: Int)
    fun clear(cohortKey: String)
}
