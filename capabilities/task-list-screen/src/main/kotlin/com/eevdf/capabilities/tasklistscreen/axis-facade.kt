package com.eevdf.capabilities.tasklistscreen

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import com.eevdf.capabilities.taskstorage.AxisChecklist
import com.eevdf.capabilities.taskstorage.Axes
import com.eevdf.capabilities.taskstorage.Task
import com.eevdf.capabilities.taskstorage.TaskRepository

/**
 * Everything the screens need to know about axes, in one place so neither
 * [TaskViewModel] nor the activities grow per-axis code.
 *
 * Axis ids are opaque: nothing here knows what an axis means, or how many exist.
 * "Queue axis" is VIEW state only (which axis's definitions the Queue tab shows);
 * it never changes a task or how the next task is picked.
 */
class AxisFacade internal constructor(
    private val repository: TaskRepository,
    /** Called when the Queue axis changes: a drill frame of one axis means nothing on another. */
    private val onQueueAxisChanged: () -> Unit,
) {
    private val _queueAxis = MutableLiveData(Axes.DEFAULT)
    val queueAxis: LiveData<String> = _queueAxis
    val current: String get() = _queueAxis.value ?: Axes.DEFAULT

    /** Active (awake) entries of the Queue axis when it is not the first; empty for the first (it has its own lists). */
    internal val tasks: LiveData<List<Task>> = _queueAxis.switchMap { a ->
        if (a == Axes.DEFAULT) MutableLiveData<List<Task>>(emptyList()) else repository.activeTasksOfAxis(a)
    }

    fun select(axisId: String) {
        if (!Axes.isKnown(axisId) || axisId == current) return
        _queueAxis.value = axisId
        onQueueAxisChanged()
    }

    /** The Queue tab's label: plain for the first axis, "Queue·Y" for the others. */
    fun tabLabel(axisId: String): String =
        if (axisId == Axes.DEFAULT) "Queue" else "Queue\u00B7${axisId.uppercase()}"

    fun groupsOf(axisId: String): LiveData<List<Task>> = repository.activeGroupsOfAxis(axisId)

    // ── include / exclude checklist ──────────────────────────────────────
    suspend fun definitions(axisId: String): List<Task> = repository.axisDefinitions(axisId)
    suspend fun exclusions(outerKey: String, axisId: String): Set<String> = repository.axisExclusions(outerKey, axisId)

    /** Persist a checklist result (id -> checked) for [outerKey]; only the changed switches are written. */
    suspend fun saveInclusion(outerKey: String, axisId: String, defs: List<Task>, checked: Map<String, Boolean>) {
        val before = exclusions(outerKey, axisId)
        val after  = AxisChecklist.topmostExcluded(defs, checked)
        (after - before).forEach { repository.setAxisIncluded(outerKey, axisId, it, false) }
        (before - after).forEach { repository.setAxisIncluded(outerKey, axisId, it, true) }
    }
}
