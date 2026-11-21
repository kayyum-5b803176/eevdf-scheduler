package com.eevdf.capabilities.tasklistscreen

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import com.eevdf.capabilities.taskstorage.AxisEditTarget
import com.eevdf.capabilities.taskstorage.AxisNode
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

    /** Entries of any axis for edit-screen validation (siblings). The first axis uses the ordinary list. */
    fun tasksOf(axisId: String): LiveData<List<Task>> = repository.activeTasksOfAxis(axisId)

    // ── copies: real rows that follow a later-axis definition ─────────────
    /** Every real copy; changes only when copies are created or removed, never on a timer tick. */
    val nodes: LiveData<List<AxisNode>> = repository.allAxisNodes

    /** The axis label ("Y", "Z", …) for a row that is a copy of a later-axis definition, else null. */
    fun badgeOf(taskId: String): String? = nodes.value?.firstOrNull { it.taskId == taskId }?.axisId?.uppercase()

    /** The definition a copy follows, as (definition id, its axis) — an edit of a copy opens this instead. */
    fun definitionOf(taskId: String): Pair<String, String>? =
        nodes.value?.firstOrNull { it.taskId == taskId }?.let { it.definitionId to it.axisId }

    /** What the edit screen should show for [task] — see [TaskRepository.axisEditTarget]. */
    suspend fun editTarget(task: Task): AxisEditTarget = repository.axisEditTarget(task)
}
