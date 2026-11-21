package com.eevdf.capabilities.addtaskscreen

import android.view.View
import androidx.lifecycle.lifecycleScope
import com.eevdf.capabilities.taskstorage.Axes
import com.eevdf.capabilities.taskstorage.AxisMove
import com.eevdf.capabilities.taskstorage.Task
import kotlinx.coroutines.launch
import com.eevdf.capabilities.grouppicker.output.PickerDialog
import com.eevdf.capabilities.taskstorage.logic.SortHelper

/**
 * Parent group picker section for [AddTaskActivity].
 *
 * Changes from the previous version:
 *   • [applyParentLoadFactor] now accepts a [Task] instead of a raw Double so
 *     the load factor section can async-fetch the parent's [TaskLoadFactor] side
 *     table entry and mirror the full slider state, not just the computed value.
 *   • Parent-deselect resets load factor via [resetLoadFactorToDefault] instead
 *     of writing to the now-removed [etLoadFactor] field.
 *   • [suppressLoadFactorWatcher] and [etLoadFactor] references removed throughout.
 */

/**
 * The parent group field's text: the group's name, with the axis first when it is not the first axis
 * ("Y · Project B", "Y · None (root level)") — the axis the row will be on is part of where it goes.
 */
internal fun parentLabel(axis: String, groupName: String?): String {
    val shown = groupName ?: "None (root level)"
    return if (axis == Axes.ALL.first()) shown else "${axis.uppercase()} · $shown"
}

internal fun AddTaskActivity.setupGroupSection() {
    if (!groupsEnabled) {
        groupSection.visibility     = View.GONE
        groupTypeSection.visibility = View.GONE
        return
    }
    groupSection.visibility     = View.VISIBLE
    groupTypeSection.visibility = View.VISIBLE

    val groupSource = if (axisId == com.eevdf.capabilities.taskstorage.Axes.DEFAULT) viewModel.activeGroups else viewModel.axes.groupsOf(axisId)
    groupSource.observe(this) { groups ->
        groupsList.clear()
        groupsList.add(null)

        val filteredSorted = groups
            .filter { it.id != existingTaskId }
            .sortedWith(SortHelper.taskNameComparator)
        groupsList.addAll(filteredSorted)

        selectedParentId?.let { pid ->
            val match = filteredSorted.firstOrNull { it.id == pid }
            if (match != null && targetAxisId == axisId) actvParentGroup.setText(parentLabel(axisId, match.name), false)
        }
    }

    actvParentGroup.setOnClickListener {
        lifecycleScope.launch {
            // Every axis's groups, so choosing one on another axis moves the row there. A row cannot be
            // moved under itself, so it and everything inside it are left out.
            val perAxis = Axes.ALL.associateWith { AxisMove.withoutSubtree(viewModel.axes.pickerGroups(it), existingTaskId) }
                .mapValues { (_, groups) -> groups.sortedWith(SortHelper.taskNameComparator) }
            val row = existingTask
            val locked = row != null && (row.isRunning || row.isInterrupt)
            val dialog = PickerDialog().apply {
                axisIds         = Axes.ALL
                axisGroups      = perAxis
                currentAxisId   = targetAxisId
                currentGroupId  = selectedParentId
                axisLocked      = locked
                axisLockedHint  = if (row?.isInterrupt == true) "Interrupt tasks stay on ${Axes.ALL.first().uppercase()}."
                                  else "Stop it to move it to another axis."
                onAxisGroupSelected = { axis, chosen ->
                    targetAxisOverride = axis
                    selectedParentId = chosen?.id
                    actvParentGroup.setText(parentLabel(axis, chosen?.name), false)

                    when {
                        // Parent picked while already in auto mode → sync to new parent
                        chosen != null && (isLoadFactorInherited || isTimeSliceInherited) -> {
                            if (isLoadFactorInherited) applyParentLoadFactor(chosen)   // ← Task, not Double
                            if (isTimeSliceInherited)  applyParentTimeSlice(chosen.timeSliceSeconds)
                        }

                        // Parent deselected while in auto mode → reset to defaults
                        chosen == null && (isLoadFactorInherited || isTimeSliceInherited) -> {
                            if (isLoadFactorInherited) resetLoadFactorToDefault()      // ← new helper
                            if (isTimeSliceInherited) {
                                isTimeSliceInherited = false
                                tvTimeSliceAutoLabel.visibility = View.GONE
                            }
                        }

                        // New task picking a parent for the first time → inherit both
                        chosen != null && existingTaskId == null -> {
                            applyParentLoadFactor(chosen)                              // ← Task, not Double
                            applyParentTimeSlice(chosen.timeSliceSeconds)
                        }
                    }
                    recalcWeightFromPinned()   // siblings changed with the axis / parent
                }
            }
            dialog.show(supportFragmentManager, "group_picker")
        }
    }
}

internal fun AddTaskActivity.populateGroupSection(task: Task) {
    if (groupsEnabled) {
        switchIsGroup.isChecked = task.isGroup
    }
    val pid = task.parentId
    if (pid != null) {
        selectedParentId = pid
        val match = groupsList.filterNotNull().firstOrNull { it.id == pid }
        actvParentGroup.setText(
            if (match != null) parentLabel(axisId, match.name) else actvParentGroup.text.toString(), false,
        )
    } else if (axisId != Axes.ALL.first()) {
        actvParentGroup.setText(parentLabel(axisId, null), false)   // "Y · None (root level)"
    }
}
