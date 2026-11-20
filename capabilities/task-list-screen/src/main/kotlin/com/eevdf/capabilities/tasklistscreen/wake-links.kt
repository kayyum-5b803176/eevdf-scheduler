package com.eevdf.capabilities.tasklistscreen

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.eevdf.kernel.eventbus.TaskSelectRequest
import com.eevdf.kernel.eventbus.Topics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The two ways the scheduled wake reaches the main screen — and nothing else of it does:
 *
 *  • `wake.armed-changed` — which tasks have an armed wake, so their icon blinks.
 *  • `scheduler.task-select-requested` — "make this task the current one" (the wake card's Preempt /
 *    Select, and the return when a preempting task ends). Answered by calling
 *    [TaskViewModel.setCurrentTask], the very function a tap on a task's row uses, so the running timer
 *    is paused with its time saved, the notice state is reset, a hand-closed timer card is reopened and
 *    the selection is remembered. It never STARTS a timer; the person presses Start. A task that is
 *    gone, finished, asleep or queued behind a semaphore is ignored: a hidden task must not become
 *    current.
 *
 * WHY THIS IS NOT IN THE VIEWMODEL (v6.51.1). These used to be subscribed by the ViewModel under the
 * screen's capability id, and `MainActivity.onStop()` calls `bus.unsubscribeAll(CAPABILITY_ID)` — which
 * removes EVERY subscription under that id, the ViewModel's included, and nothing puts the ViewModel's
 * back. So the first time the screen stopped (switching apps, locking the phone, opening the Add
 * screen) both links died for the rest of the process: the card still appeared and its buttons still
 * reached the wake, but the icon never changed and Select / Preempt selected nothing, until the app
 * was closed and reopened.
 *
 * Here they live under an id of their own, registered when the screen is created and removed only when
 * it is DESTROYED — a stop, and the start that follows, never touch them. One instance listens at a
 * time ([activeOwner]), so a recreated screen does not leave two, and the Add screen's ViewModel (a
 * second [TaskViewModel] instance that never had a card or a task list to act on) does not answer
 * selection requests too.
 */
internal class WakeLinks(private val activity: MainActivity) {

    private val vm get() = activity.viewModel

    fun start() {
        activeOwner = this
        // One subscription set for this id however many times the screen is recreated.
        vm.bus.unsubscribeAll(SUBSCRIBER_ID)
        vm.bus.subscribe(Topics.WAKE_ARMED_CHANGED, SUBSCRIBER_ID) { ids -> vm.setWakeArmedIds(ids) }
        vm.bus.subscribe(Topics.TASK_SELECT_REQUESTED, SUBSCRIBER_ID) { request -> select(request) }

        // Whatever was armed before this screen existed (or while it was destroyed): the topic is retained.
        vm.bus.getLast(Topics.WAKE_ARMED_CHANGED)?.let { vm.setWakeArmedIds(it) }

        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (activeOwner === this@WakeLinks) {
                    vm.bus.unsubscribeAll(SUBSCRIBER_ID)
                    activeOwner = null
                }
            }
        })
    }

    private suspend fun select(request: TaskSelectRequest) = withContext(Dispatchers.Main) {
        val task = vm.repository.getActiveTasksSync().firstOrNull { it.id == request.taskId } ?: return@withContext
        vm.setCurrentTask(task, TaskInstanceRef(task.id, request.symlinkId, request.membershipId))
        request.message?.let { vm._toastMessage.value = it }
    }

    private companion object {
        /** Its own id: NOT the screen's CAPABILITY_ID, which onStop wipes. */
        const val SUBSCRIBER_ID = "task-list-screen.wake"

        /** The instance currently listening, so a recreated screen does not leave two. */
        @Volatile var activeOwner: WakeLinks? = null
    }
}
