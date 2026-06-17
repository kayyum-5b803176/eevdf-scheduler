package com.eevdf.feature.task

import com.eevdf.core.scheduler.SchedulerService
import com.eevdf.core.scheduler.model.SchedTask
import com.eevdf.core.scheduler.ports.TaskQueuePort
import kotlinx.coroutines.CoroutineScope

/**
 * The seam that breaks the reference's `viewmodel.task <-> viewmodel.{scheduler,
 * timer, notice, autoswitch}` cycles.
 *
 * In the reference, `TaskViewModel` created each delegate with `this`, and every
 * delegate imported `TaskViewModel` back — a 5-way dependency cycle wearing a
 * decomposed costume. Here a delegate depends only on this narrow interface; it
 * cannot reach into the concrete ViewModel, so the cycle is impossible and the
 * delegate is testable with a fake host.
 */
interface SchedulerHost {
    val scope: CoroutineScope
    val queue: TaskQueuePort
    val scheduler: SchedulerService
    fun now(): SchedulerService.Now
    suspend fun reloadTasks()
}

/**
 * Example delegate, now depending on [SchedulerHost] rather than the ViewModel.
 * The rotation / auto-next logic from the reference `TaskSchedulerDelegate`
 * ports onto this shape unchanged in spirit.
 */
class SchedulerDelegate(private val host: SchedulerHost) {

    suspend fun pickNext(): SchedTask? {
        val tasks = host.queue.activeTasks()
        return host.scheduler.selectNext(tasks, host.now())
    }

    suspend fun currentOrder(): List<SchedTask> {
        val tasks = host.queue.activeTasks()
        return host.scheduler.scheduleOrder(tasks, host.now())
    }
}
