package com.eevdf.capabilities.taskstorage

import androidx.lifecycle.LiveData
import com.eevdf.kernel.clock.Clock
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics
import com.eevdf.kernel.eventbus.WakeProfileSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * FORK: copies one task or group into a new sibling and records where it came from. See [ForkRules] for
 * what is copied, reset and dropped.
 *
 * A separate class on top of [TaskRepository] (not a method of it) so adding a DAO here never changes
 * TaskRepository's constructor — which tests build by hand. Everything that makes the copy a proper new
 * task goes through [TaskRepository.insert]: it is numbered with the smallest free ID of its kind, queued
 * behind a full semaphore group, hidden with a sleeping parent, and placed at the back of its group.
 */
class ForkRepository @Inject constructor(
    private val tasks: TaskRepository,
    private val forkDao: TaskForkDao,
    private val loadFactorDao: TaskLoadFactorDao,
    private val wakeDao: TaskWakeDao,
    /** Kernel event bus — task-wake owns the wake table, so a wake profile is handed over by topic. */
    private val bus: EventBus,
    private val clock: Clock,
) {
    /** Every fork, newest first. */
    val allForks: LiveData<List<TaskFork>> = forkDao.observeAll()

    /** Forgets the fork records whose child is gone. Cheap; called when the Fork page opens. */
    suspend fun cleanUp() = withContext(Dispatchers.IO) { forkDao.deleteOrphans() }

    /**
     * Forks [sourceId] as [name]; returns the new task (its [Task.identity] is the number it was given), or
     * null when the source no longer exists. Throws [IdentityPoolFullException] when the kind's ID pool is full.
     *
     * The copy lands in the SAME group as the original (see [ForkRules]).
     */
    suspend fun fork(sourceId: String, name: String): Task? = withContext(Dispatchers.IO) {
        val source = tasks.getTaskById(sourceId) ?: return@withContext null
        val now    = clock.nowEpochMillis()
        val child  = ForkRules.child(source, name.trim().ifEmpty { ForkRules.defaultName(source.name) }, now)

        tasks.insert(child)                       // numbered, placed, queued — like any new task

        // The settings that live in side tables travel with the copy.
        loadFactorDao.get(source.id)?.let { loadFactorDao.upsert(it.copy(taskId = child.id)) }
        forkDao.insert(TaskFork(taskId = child.id, parentTaskId = source.id, createdAt = now))
        // A task with no wake row has the default profile (PROFILE_ALARM), so only a different one is handed over.
        // The wake itself — an armed time — is not copied: the fork starts with nothing armed.
        wakeDao.get(source.id)?.profile
            ?.takeIf { it != TaskWake.PROFILE_ALARM }
            ?.let { bus.publish(Topics.WAKE_PROFILE_SET, WakeProfileSet(child.id, it), "task-storage") }
        child
    }
}
