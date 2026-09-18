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
    private val _instanceRef = MutableLiveData<TaskInstanceRef?>(null)

    /** Read-only view for everything that just needs to observe/read it. */
    val current: LiveData<Task?> = _current

    /**
     * WHICH PLACEMENT of [current] is dispatched — see [TaskInstanceRef]'s
     * KDoc for why this exists alongside the bare task. Every call site that
     * knows which placement it's acting on (has a [com.eevdf.capabilities.taskstorage.TaskDisplayItem]
     * in hand, e.g. a row tap or a rotate-to landing) should pass a real
     * [TaskInstanceRef] to [set]/[setAsync]; call sites that only have a bare
     * [Task] get [TaskInstanceRef.real] as a safe default — correct for the
     * overwhelmingly common case (a real, unlinked task), and no worse than
     * before this type existed for the cases that still don't know better.
     */
    val instanceRef: LiveData<TaskInstanceRef?> = _instanceRef

    /**
     * Dispatches [task] (or `null` for "nothing dispatched") on the main
     * thread. Safe to call from any delegate that used to write
     * `vm._currentTask.value = ...` directly. [ref] defaults to the task's
     * real placement when the caller doesn't have a specific one in hand.
     *
     * [_instanceRef] is set BEFORE [_current] — not after, and this order
     * matters: `MutableLiveData.value = x` notifies observers SYNCHRONOUSLY,
     * inline, before the next line of code runs. An observer on [current]
     * (e.g. refreshing the quota-exhaustion indicator) that reads
     * [instanceRef].value would otherwise see the PREVIOUS placement for
     * one frame — exactly the bug this class exists to prevent, just moved
     * one field over. Setting the ref first means it's already correct by
     * the time anything reacts to the task changing.
     */
    fun set(task: Task?, ref: TaskInstanceRef? = task?.let { TaskInstanceRef.real(it) }) {
        val changed = task?.id != _current.value?.id
        _instanceRef.value = ref
        _current.value = task
        if (changed) publish(task)
    }

    /** Same as [set], but safe to call off the main thread (was `.postValue(...)`). */
    fun setAsync(task: Task?, ref: TaskInstanceRef? = task?.let { TaskInstanceRef.real(it) }) {
        val changed = task?.id != _current.value?.id
        _instanceRef.postValue(ref)
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
