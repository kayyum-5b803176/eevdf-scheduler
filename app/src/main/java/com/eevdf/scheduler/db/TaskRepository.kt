package com.eevdf.scheduler.db

import androidx.lifecycle.LiveData
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.scheduler.EEVDFScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TaskRepository(private val dao: TaskDao) {

    val allTasks: LiveData<List<Task>> = dao.getAllTasks()
    val activeTasks: LiveData<List<Task>> = dao.getActiveTasks()
    val completedTasks: LiveData<List<Task>> = dao.getCompletedTasks()

    suspend fun insert(task: Task) = withContext(Dispatchers.IO) {
        // Recalculate scheduler values before insert
        val existing = dao.getActiveTasksSync().toMutableList()
        existing.add(task)
        EEVDFScheduler.recalculate(existing)
        dao.insert(task)
        existing.forEach { dao.update(it) }
    }

    suspend fun update(task: Task) = withContext(Dispatchers.IO) {
        dao.update(task)
    }

    suspend fun delete(task: Task) = withContext(Dispatchers.IO) {
        dao.delete(task)
    }

    suspend fun markCompleted(task: Task) = withContext(Dispatchers.IO) {
        val updated = task.copy(isCompleted = true, isRunning = false, remainingSeconds = 0)
        dao.update(updated)
    }

    suspend fun stopAll() = withContext(Dispatchers.IO) {
        dao.stopAllRunning()
    }

    suspend fun clearCompleted() = withContext(Dispatchers.IO) {
        dao.clearCompleted()
    }

    suspend fun getTaskById(id: String): Task? = withContext(Dispatchers.IO) {
        dao.getTaskById(id)
    }

    suspend fun updateVruntimeAfterRun(task: Task, secondsRan: Long) = withContext(Dispatchers.IO) {
        EEVDFScheduler.updateVruntime(task, secondsRan)
        dao.update(task)
        // Recalculate all active tasks
        val allActive = dao.getActiveTasksSync()
        EEVDFScheduler.recalculate(allActive)
        allActive.forEach { dao.update(it) }
    }

    suspend fun selectNextTask(): Task? = withContext(Dispatchers.IO) {
        val activeTasks = dao.getActiveTasksSync()
        EEVDFScheduler.recalculate(activeTasks)
        EEVDFScheduler.selectNext(activeTasks)
    }

    suspend fun getScheduleOrder(): List<Task> = withContext(Dispatchers.IO) {
        val activeTasks = dao.getActiveTasksSync()
        EEVDFScheduler.getScheduleOrder(activeTasks)
    }
}
