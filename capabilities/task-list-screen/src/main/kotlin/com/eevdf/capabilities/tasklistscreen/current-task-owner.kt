package com.eevdf.capabilities.tasklistscreen

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.eevdf.capabilities.taskstorage.Task
import com.eevdf.kernel.eventbus.CurrentTaskState
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The single owner of "which task is currently dispatched."
 *
 * Before this, every delegate that wanted to change the running/selected task
 * wrote directly to `TaskViewModel._currentTask` — 30+ call sites across 7
 * files, with no single place enforcing what a legal transition even means.
 * That's the exact shape of problem [Topics.TIMER_RUNNING_CHANGED] already
 * solved for `BubbleEventBus`'s three flags — just never applied to the one
 * fact that actually matters most: which task is running right now.
 *
 * Every delegate now calls [set] / [setAsync] instead of writing to a
 * LiveData directly. This class:
 *   1. Is the only thing that mutates the underlying LiveData.
 *   2. Publishes [Topics.CURRENT_TASK_CHANGED] on the bus — but ONLY when the
 *      dispatched task's *identity* actually changes (a different id, or a
 *      transition to/from no task). A tick that just refreshes
 *      `remainingSeconds` on the task that's already current is not a
 *      context switch and must not spam the bus once a second — that
 *      distinction is enforced HERE, once, so no call site has to get it
 *      right on its own.
 *
 * Modeled directly on `TaskViewModel.timerState` (the [Topics.TIMER_RUNNING_CHANGED]
 * precedent): a local, synchronously-readable value, changes announced over
 * the bus rather than reached into by other capabilities.
 */
internal class CurrentTaskOwner(
    private val bus: EventBus,
    private val scope: CoroutineScope,
    private val publisherId: String,
) {
    private val _current = MutableLiveData<Task?>(null)

    /** Read-only view for everything that just needs to observe/read it. */
    val current: LiveData<Task?> = _current

    /**
     * Dispatches [task] (or `null` for "nothing dispatched") on the main
     * thread. Safe to call from any delegate that used to write
     * `vm._currentTask.value = ...` directly.
     */
    fun set(task: Task?) {
        val changed = task?.id != _current.value?.id
        _current.value = task
        if (changed) publish(task)
    }

    /** Same as [set], but safe to call off the main thread (was `.postValue(...)`). */
    fun setAsync(task: Task?) {
        val changed = task?.id != _current.value?.id
        _current.postValue(task)
        if (changed) publish(task)
    }

    private fun publish(task: Task?) {
        scope.launch {
            bus.publish(
                Topics.CURRENT_TASK_CHANGED,
                CurrentTaskState(taskId = task?.id, taskName = task?.name, isRunning = task?.isRunning == true),
                publisherId,
            )
        }
    }
}
