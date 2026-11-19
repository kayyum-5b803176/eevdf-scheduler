package com.eevdf.capabilities.tasklistscreen

import com.eevdf.kernel.eventbus.TaskSelectRequest
import com.eevdf.kernel.eventbus.Topics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Answers [Topics.TASK_SELECT_REQUESTED]: "make this task the current one".
 *
 * This is the only piece of the scheduled wake that lives in the task list, and it is not
 * wake-specific — any capability that wants a task shown can publish the same request. The
 * list owns the current task ([CurrentTaskOwner]), so the list is the one that changes it.
 *
 * It does what INT-A does when it seats the interrupt task: optionally pause the running
 * timer first (so its remaining time is saved), then SELECT the task — it never starts a
 * timer; the person presses Start. A task that is gone, finished, asleep or queued behind a
 * semaphore is ignored: a hidden task must not become current.
 */
internal class TaskSelectDelegate(private val vm: TaskViewModel, capabilityId: String) {

    init {
        vm.bus.subscribe(Topics.TASK_SELECT_REQUESTED, capabilityId) { request -> select(request) }
    }

    private suspend fun select(request: TaskSelectRequest) = withContext(Dispatchers.Main) {
        if (request.pauseCurrent) vm.pauseTimer()
        val task = vm.repository.getActiveTasksSync().firstOrNull { it.id == request.taskId } ?: return@withContext
        vm.currentTaskOwner.set(task, TaskInstanceRef(task.id, request.symlinkId, request.membershipId))
        vm._timerSeconds.value = task.remainingSeconds
        request.message?.let { vm._toastMessage.value = it }
    }
}
