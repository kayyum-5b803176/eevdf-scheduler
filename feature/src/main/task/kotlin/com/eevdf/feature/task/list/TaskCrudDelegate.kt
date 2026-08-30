package com.eevdf.feature.task.list

import com.eevdf.data.task.Task
import com.eevdf.data.task.TaskLoadFactor
import com.eevdf.data.task.timer.TaskTimerState
import com.eevdf.data.task.timer.withTimerState
import com.eevdf.data.scheduler.EEVDFScheduler
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * Task CRUD operations: add / update / delete / revert / complete, plus the
 * pinned-weight resync every mutation triggers and the load-factor side table.
 *
 * Extracted from TaskViewModel (Phase 10) — the lowest-risk cluster of its
 * undelegated logic: touches the repository and a couple of shared LiveData
 * fields, but never the timer/alarm state machine directly (it calls into
 * [TaskViewModel.pauseTimer]/[TaskViewModel.stopTimer] the same way MainActivity
 * or any other external caller would, through the public/internal surface,
 * not by reaching into timer internals). No behavior changed — every line is
 * the original, moved as-is.
 */
internal class TaskCrudDelegate(private val vm: TaskViewModel) {

    /**
     * After any task mutation the float-pool changes for every sibling.
     * Re-derives internalWeight for all pinned tasks and batch-persists only
     * the ones that actually changed.
     */
    private suspend fun syncPinnedWeights() {
        val tasks   = vm.repository.getActiveTasksSync()
        val changed = EEVDFScheduler.syncPinnedWeights(tasks)
        if (changed.isNotEmpty()) vm.repository.updateBatch(changed)
    }

    fun addTask(task: Task) = vm.viewModelScope.launch {
        vm.repository.insert(task)
        syncPinnedWeights()
        vm.refreshSchedule()
        vm.triggerSyncExport()
        vm._toastMessage.postValue("Task \"${task.name}\" added to scheduler")
    }

    fun updateTask(task: Task) = vm.viewModelScope.launch {
        vm.repository.update(task)
        syncPinnedWeights()
        vm.refreshSchedule()
        vm.triggerSyncExport()
    }

    fun deleteTask(task: Task) = vm.viewModelScope.launch {
        if (task.id == vm._currentTask.value?.id) {
            vm.pauseTimer()
            vm._currentTask.postValue(null)
            vm.clearPersistedSelection()
        }
        vm.repository.delete(task)
        syncPinnedWeights()
        vm.refreshSchedule()
        vm.triggerSyncExport()
        vm._toastMessage.postValue("Task \"${task.name}\" deleted")
    }

    /** Moves a completed task back to the active queue, restoring its timer slice. */
    fun revertTask(task: Task) = vm.viewModelScope.launch {
        val reverted = task.copy(isCompleted = false).withTimerState(TaskTimerState.reset())
        vm.repository.update(reverted)
        syncPinnedWeights()
    }

    fun markCompleted(task: Task) = vm.viewModelScope.launch {
        vm.triggerSyncExport()               // notify other users: task completed
        if (task.id == vm._currentTask.value?.id) vm.stopTimer(completed = true)
        else vm.repository.markCompleted(task)
        syncPinnedWeights()
        vm.refreshSchedule()
    }

    fun clearCompleted() = vm.viewModelScope.launch { vm.repository.clearCompleted() }

    fun clearToast() { vm._toastMessage.value = null }

    fun toggleGroupExpanded(group: Task) = vm.viewModelScope.launch {
        val updated = group.copy(isGroupExpanded = !group.isGroupExpanded)
        vm.repository.update(updated)
    }

    /** Direct DB lookup used by AddTaskActivity to reliably load a task for editing. */
    suspend fun getTaskById(id: String): Task? = vm.repository.getTaskById(id)

    /**
     * Fetches the [TaskLoadFactor] side table entry for [taskId].
     * Returns null when the task has never had its load factor configured
     * (form treats missing row as disabled / midpoint defaults 4,4,4 → 50).
     */
    suspend fun getLoadFactor(taskId: String): TaskLoadFactor? =
        vm.repository.getLoadFactor(taskId)

    /**
     * Persists a [TaskLoadFactor] side table entry (insert or replace).
     * Called from [SaveHandler] after the Task row has been written
     * so the side table always references a valid taskId.
     */
    fun saveLoadFactor(entry: TaskLoadFactor) = vm.viewModelScope.launch {
        vm.repository.saveLoadFactor(entry)
    }
}
