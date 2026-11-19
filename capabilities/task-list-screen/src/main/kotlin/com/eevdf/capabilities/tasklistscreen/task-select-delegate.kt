package com.eevdf.capabilities.tasklistscreen

import com.eevdf.kernel.eventbus.TaskSelectRequest
import com.eevdf.kernel.eventbus.Topics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Answers [Topics.TASK_SELECT_REQUESTED]: "make this task the current one".
 *
 * The only piece of the scheduled wake that selects anything, and not wake-specific: any
 * capability that wants a task shown can publish the same request. The list owns the current
 * task ([CurrentTaskOwner]), so the list is the one that changes it.
 *
 * It calls [TaskViewModel.setCurrentTask] — the very function a tap on a task's row uses — and
 * nothing of its own. That matters: setting the current task directly skipped what a selection
 * has to do (pause the running timer and save its time, reset the notice state, reopen a
 * hand-closed timer card, remember the selection), so the task was "selected" with nothing to
 * show for it. It never STARTS a timer; the person presses Start. A task that is gone, finished,
 * asleep or queued behind a semaphore is ignored: a hidden task must not become current.
 */
internal class TaskSelectDelegate(private val vm: TaskViewModel, capabilityId: String) {

    init {
        vm.bus.subscribe(Topics.TASK_SELECT_REQUESTED, capabilityId) { request -> select(request) }
    }

    private suspend fun select(request: TaskSelectRequest) = withContext(Dispatchers.Main) {
        val task = vm.repository.getActiveTasksSync().firstOrNull { it.id == request.taskId } ?: return@withContext
        vm.setCurrentTask(task, TaskInstanceRef(task.id, request.symlinkId, request.membershipId))
        request.message?.let { vm._toastMessage.value = it }
    }
}
