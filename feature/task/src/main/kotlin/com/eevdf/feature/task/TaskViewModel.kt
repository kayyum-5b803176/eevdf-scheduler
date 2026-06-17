package com.eevdf.feature.task

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eevdf.core.scheduler.SchedulerService
import com.eevdf.core.scheduler.model.SchedTask
import com.eevdf.core.scheduler.ports.TaskQueuePort
import com.eevdf.core.time.Clock
import com.eevdf.core.time.WallClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Task feature ViewModel — the cleaned counterpart of the reference 931-line
 * `TaskViewModel`. Key differences:
 *
 *  • Dependencies are injected (queue, scheduler, clock) instead of `new`-ed
 *    against an Application + SharedPreferences inside the class.
 *  • It depends DOWN on core ports only — no `ui.alarm.*` imports (the reference
 *    had `viewmodel -> ui`, an upward dependency). Platform effects arrive via
 *    core platform ports passed in by the composition root.
 *  • It implements [SchedulerHost], so its delegates depend on the interface,
 *    not the concrete class → no cycles.
 *
 * This is a skeleton: the full feature ports the reference delegates
 * (interrupt, call-switch, group-expand, notice state machine) onto the same
 * host pattern. See README "Remaining work".
 */
class TaskViewModel(
    override val queue: TaskQueuePort,
    override val scheduler: SchedulerService,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel(), SchedulerHost {

    override val scope: CoroutineScope get() = viewModelScope

    private val _tasks = MutableLiveData<List<SchedTask>>(emptyList())
    val tasks: LiveData<List<SchedTask>> = _tasks

    private val schedulerDelegate = SchedulerDelegate(this)

    override fun now(): SchedulerService.Now {
        val epochMs = clock.nowEpochMillis()
        val local = WallClock.localize(epochMs, zone)
        return SchedulerService.Now(
            epochSeconds = epochMs / 1_000L,
            dayOfWeekIndex = local.dayOfWeekIndex,
            secondOfDay = local.secondOfDay,
            prevDayOfWeekIndex = WallClock.previousDayIndex(local.dayOfWeekIndex),
        )
    }

    override suspend fun reloadTasks() {
        _tasks.postValue(schedulerDelegate.currentOrder())
    }

    fun refresh() = scope.launch { reloadTasks() }

    fun nextUp(onResult: (SchedTask?) -> Unit) = scope.launch { onResult(schedulerDelegate.pickNext()) }
}
