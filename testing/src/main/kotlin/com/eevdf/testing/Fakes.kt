package com.eevdf.testing

import com.eevdf.core.scheduler.model.SchedTask
import com.eevdf.core.scheduler.ports.RrStatePort
import com.eevdf.core.scheduler.ports.TaskQueuePort

/** In-memory RR cursor for tests — mirrors the reset-on-cohort-change contract. */
class InMemoryRrStateStore : RrStatePort {
    private var cohort: String = ""
    private var index: Int = 0
    override fun currentIndex(cohortKey: String): Int = if (cohort == cohortKey) index else 0
    override fun setIndex(cohortKey: String, index: Int) { cohort = cohortKey; this.index = index }
    override fun clear(cohortKey: String) { cohort = ""; index = 0 }
}

/** In-memory task queue for fast, emulator-free use-case tests. */
class FakeTaskQueue(initial: List<SchedTask> = emptyList()) : TaskQueuePort {
    private val tasks = initial.associateBy { it.id }.toMutableMap()
    override suspend fun activeTasks(): List<SchedTask> = tasks.values.filter { !it.isCompleted }
    override suspend fun childrenOf(parentId: String?): List<SchedTask> =
        tasks.values.filter { it.parentId == parentId && !it.isCompleted }
    override suspend fun save(task: SchedTask) { tasks[task.id] = task }
    override suspend fun saveAll(tasks: List<SchedTask>) { tasks.forEach { this.tasks[it.id] = it } }
}
