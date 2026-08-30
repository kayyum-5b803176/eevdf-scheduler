package com.eevdf.feature.task

import android.view.View
import com.eevdf.data.task.Task

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

internal fun AddTaskActivity.setupGroupSection() {
    if (!groupsEnabled) {
        groupSection.visibility     = View.GONE
        groupTypeSection.visibility = View.GONE
        return
    }
    groupSection.visibility     = View.VISIBLE
    groupTypeSection.visibility = View.VISIBLE

    viewModel.activeGroups.observe(this) { groups ->
        groupsList.clear()
        groupsList.add(null)

        val filteredSorted = groups
            .filter { it.id != existingTaskId }
            .sortedWith(TaskSortHelper.taskNameComparator)
        groupsList.addAll(filteredSorted)

        selectedParentId?.let { pid ->
            val match = filteredSorted.firstOrNull { it.id == pid }
            if (match != null) actvParentGroup.setText(match.name, false)
        }
    }

    actvParentGroup.setOnClickListener {
        val dialog = GroupPickerDialog().apply {
            allGroups      = groupsList.filterNotNull()
            currentGroupId = selectedParentId
            onGroupSelected = { chosen ->
                selectedParentId = chosen?.id
                actvParentGroup.setText(chosen?.name ?: "None (root level)", false)

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
            }
        }
        dialog.show(supportFragmentManager, "group_picker")
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
        actvParentGroup.setText(match?.name ?: actvParentGroup.text.toString(), false)
    }
}
