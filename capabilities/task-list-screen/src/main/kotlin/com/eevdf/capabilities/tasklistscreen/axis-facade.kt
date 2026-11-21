package com.eevdf.capabilities.tasklistscreen

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import com.eevdf.capabilities.taskstorage.AxisAcl
import com.eevdf.capabilities.taskstorage.AxisCopy
import com.eevdf.capabilities.taskstorage.AxisEditTarget
import com.eevdf.capabilities.taskstorage.AxisMovePreview
import com.eevdf.capabilities.taskstorage.AxisPath
import com.eevdf.capabilities.taskstorage.AxisVirtualId
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
    fun badgeOf(taskId: String): String? {
        nodes.value?.firstOrNull { it.taskId == taskId }?.let { return it.axisId.uppercase() }
        // An untouched copy is not a row yet: its axis is its definition's.
        val definitionId = AxisVirtualId.parse(taskId)?.second ?: return null
        return repository.laterAxisTasks.value.orEmpty().firstOrNull { it.id == definitionId }?.axisId?.uppercase()
    }

    /** The definition a copy follows, as (definition id, its axis) — an edit of a copy opens this instead. */
    fun definitionOf(taskId: String): Pair<String, String>? =
        nodes.value?.firstOrNull { it.taskId == taskId }?.let { it.definitionId to it.axisId }

    /** What the edit screen should show for [task] — see [TaskRepository.axisEditTarget]. */
    suspend fun editTarget(task: Task): AxisEditTarget = repository.axisEditTarget(task)

    // ── moving a row to another axis (the parent group picker) ────────────
    /** The groups [axisId] offers as a parent. */
    suspend fun pickerGroups(axisId: String): List<Task> = repository.axisPickerGroups(axisId)

    /** What moving [task] to [targetAxis] would cost — null-loss means no confirmation is needed. [route] re-places it (path mode). */
    suspend fun movePreview(task: Task, targetAxis: String, route: String? = null): AxisMovePreview =
        repository.axisMovePreview(task, targetAxis, route)

    /**
     * Save [edited] and move it (and, for a group, what is inside it) to [Task.axisId]. With a [route] (path mode) it is
     * also placed ONLY where that pattern matches. False when something is running.
     */
    suspend fun moveToAxis(edited: Task, route: String? = null, copyRules: List<AxisAcl> = emptyList()): Boolean =
        repository.moveToAxis(edited, route, copyRules)

    // ── the "copy it?" prompt ──────────────────────────────────────────────
    /** The offer to copy a NEW later-axis entry into the places it could appear; null when there are none. */
    suspend fun copyOfferForEntry(entryId: String, entryAxis: String, parentId: String?, name: String): AxisCopy.Offer? =
        repository.axisCopyOfferForEntry(entryId, entryAxis, parentId, name)

    /** The offer to copy the existing entries of the next axis into a NEW first-axis leaf; null when there are none. */
    suspend fun copyOfferForPlace(placeId: String, name: String): AxisCopy.Offer? = repository.axisCopyOfferForPlace(placeId, name)

    /** Write the '+' rules of an accepted offer, before the row is saved. */
    suspend fun writeCopyRules(rules: List<AxisAcl>) = repository.writeAxisRules(rules)

    // ── path mode ─────────────────────────────────────────────────────────
    /** Everything the picker's path mode lists. */
    suspend fun pathSnapshot(): AxisPath.Snapshot = repository.axisPathSnapshot()

    /** Back to the default for [taskId]: copied wherever it is included. */
    suspend fun clearPlacement(taskId: String) = repository.clearAxisPlacement(taskId)

    /** Place [taskId] only where [route] matches. Write before the entry is saved. */
    suspend fun setPlacement(taskId: String, route: String) = repository.setAxisRoute(taskId, route)
}
