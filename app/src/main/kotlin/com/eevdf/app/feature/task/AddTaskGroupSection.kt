package com.eevdf.app.feature.task

import android.view.View
import android.widget.ArrayAdapter
import com.eevdf.data.task.Task
import com.eevdf.app.feature.task.TaskSortHelper

/**
 * Parent group spinner section for [AddTaskActivity].
 *
 * Separated so changes to group filtering, sorting, or the spinner adapter
 * (e.g. adding group icons or subtext) don't touch the core activity file.
 *
 * Domain:
 *   • [AddTaskActivity.setupGroupSection]   — observes activeGroups and populates spinner
 *   • [AddTaskActivity.populateGroupSection] — restores parent selection from existing task
 */

internal fun AddTaskActivity.setupGroupSection() {
    if (!groupsEnabled) {
        groupSection.visibility     = View.GONE
        groupTypeSection.visibility = View.GONE
        return
    }
    groupSection.visibility     = View.VISIBLE
    groupTypeSection.visibility = View.VISIBLE
    // Observe available groups and populate spinner
    viewModel.activeGroups.observe(this) { groups ->
        groupsList.clear()
        groupsList.add(null)  // index 0 = no parent

        // Sort groups with the same rule as the Queue tab: numbers → letters → symbols
        val filteredSorted = groups
            .filter { it.id != existingTaskId }
            .sortedWith(TaskSortHelper.taskNameComparator)
        groupsList.addAll(filteredSorted)

        val labels = listOf("None (root level)") + filteredSorted.map { it.name }

        spinnerParent.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        // Restore existing parent selection when editing
        existingTask?.parentId?.let { pid ->
            val idx = groupsList.indexOfFirst { it?.id == pid }
            if (idx >= 0) spinnerParent.setSelection(idx)
        }
    }

    // Hide the parent spinner when "this is a group" is toggled — a group's
    // parent can still be another group, so we keep it visible.
    switchIsGroup.setOnCheckedChangeListener { _, _ ->
        // No extra behaviour needed; just cosmetic feedback is enough.
    }
}

/** Restores the isGroup switch and parent selection from [task]. */
internal fun AddTaskActivity.populateGroupSection(task: Task) {
    if (groupsEnabled) {
        switchIsGroup.isChecked = task.isGroup
    }
    // Parent spinner selection is restored inside the activeGroups observer above,
    // once the group list is available. existingTask must be set before then.
}
