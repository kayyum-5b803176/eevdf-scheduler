package com.eevdf.data.task

import com.eevdf.core.scheduler.eevdf.CpuShares
import com.eevdf.core.scheduler.eevdf.EevdfScheduler
import com.eevdf.core.scheduler.model.SchedTask
import com.eevdf.core.scheduler.ports.TaskQueuePort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implements the core [TaskQueuePort]. Note the dependency direction: this data
 * class depends on core (the port + the pure scheduler), never the reverse —
 * the opposite of the reference `db.TaskRepository -> scheduler.*` inversion.
 *
 * Responsibilities:
 *  • map [TaskEntity] ↔ [SchedTask] at the boundary,
 *  • call the pure scheduler for placement/recalc,
 *  • persist only the scheduling columns the pass produced.
 */
class TaskRepositoryImpl(
    private val dao: TaskDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TaskQueuePort {

    override suspend fun activeTasks(): List<SchedTask> = withContext(io) {
        dao.activeTasks().map(TaskMapper::toDomain)
    }

    override suspend fun childrenOf(parentId: String?): List<SchedTask> = withContext(io) {
        dao.childrenOf(parentId).map(TaskMapper::toDomain)
    }

    override suspend fun save(task: SchedTask) = withContext(io) {
        val entity = dao.byId(task.id) ?: return@withContext
        dao.update(TaskMapper.applyScheduling(entity, task))
    }

    override suspend fun saveAll(tasks: List<SchedTask>) = withContext(io) {
        val byId = tasks.associateBy { it.id }
        val updated = byId.keys.mapNotNull { id ->
            dao.byId(id)?.let { TaskMapper.applyScheduling(it, byId.getValue(id)) }
        }
        if (updated.isNotEmpty()) dao.updateAll(updated)
    }

    /**
     * Insert a brand-new task, placing its initial vruntime via the pure EEVDF
     * `place_entity` rule against its siblings, then re-syncing pinned weights.
     */
    suspend fun insertNew(entity: TaskEntity) = withContext(io) {
        val existing = dao.activeTasks().map(TaskMapper::toDomain)
        val domain = TaskMapper.toDomain(entity)
        val placed = domain.copy(vruntime = EevdfScheduler.initialVruntime(domain, existing))
        dao.insert(TaskMapper.applyScheduling(entity, placed))

        // Re-sync pinned weights across the active set (minimal diff persisted).
        val changed = CpuShares.syncPinnedWeights(existing + placed)
        if (changed.isNotEmpty()) saveAll(changed)
    }
}
