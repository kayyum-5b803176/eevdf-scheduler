package com.eevdf.app.feature.task

import android.view.View
import com.eevdf.data.task.Task

/**
 * Parent group picker section for [AddTaskActivity].
 *
 * Replaces the old plain [android.widget.Spinner] with [GroupPickerDialog],
 * which provides:
 *   • Pattern-match search (contains, case-insensitive — same as category field)
 *   • "Recent" section showing the last [RecentGroupPrefs.MAX] selected groups
 *   • "All Groups" full sorted list
 *
 * Selected state is held in [AddTaskActivity.selectedParentId] (null = root).
 * [AddTaskSaveHandler] reads that field directly instead of calling
 * spinnerParent.selectedItemPosition.
 *
 * Domain:
 *   • [AddTaskActivity.setupGroupSection]    — observes activeGroups, wires picker
 *   • [AddTaskActivity.populateGroupSection] — restores selection from existing task
 */

internal fun AddTaskActivity.setupGroupSection() {
    if (!groupsEnabled) {
        groupSection.visibility     = View.GONE
        groupTypeSection.visibility = View.GONE
        return
    }
    groupSection.visibility     = View.VISIBLE
    groupTypeSection.visibility = View.VISIBLE

    // Observe available groups and rebuild the list shown in the picker dialog.
    viewModel.activeGroups.observe(this) { groups ->
        groupsList.clear()
        groupsList.add(null)  // index 0 = no parent (kept for save-handler compat)

        val filteredSorted = groups
            .filter { it.id != existingTaskId }
            .sortedWith(TaskSortHelper.taskNameComparator)
        groupsList.addAll(filteredSorted)

        // If a parent was already restored (selectedParentId set by
        // populateGroupSection) but the label couldn't be resolved yet because
        // groupsList was still empty at that point, resolve it now.
        selectedParentId?.let { pid ->
            val match = filteredSorted.firstOrNull { it.id == pid }
            if (match != null) tvParentGroupLabel.text = match.name
        }
    }

    // Tapping the picker button opens GroupPickerDialog as a sheet-style dialog.
    btnParentGroupPicker.setOnClickListener {
        val dialog = GroupPickerDialog().apply {
            allGroups     = groupsList.filterNotNull()
            currentGroupId = selectedParentId
            onGroupSelected = { chosen ->
                selectedParentId        = chosen?.id
                tvParentGroupLabel.text = chosen?.name ?: "None (root level)"
            }
        }
        dialog.show(supportFragmentManager, "group_picker")
    }
}

/**
 * Restores the isGroup switch and parent selection from [task].
 *
 * Called from [AddTaskActivity.populateFields] after [task] has been loaded
 * asynchronously. Sets [AddTaskActivity.selectedParentId] directly so the
 * picker reflects the correct selection regardless of whether the
 * activeGroups LiveData observer (registered earlier in setupGroupSection)
 * has already fired by this point.
 */
internal fun AddTaskActivity.populateGroupSection(task: Task) {
    if (groupsEnabled) {
        switchIsGroup.isChecked = task.isGroup
    }
    val pid = task.parentId
    if (pid != null) {
        selectedParentId = pid
        // Prefer the name from groupsList if already populated (gives the
        // correct display name immediately); fall back to a lookup once the
        // activeGroups observer fires and rebuilds groupsList.
        val match = groupsList.filterNotNull().firstOrNull { it.id == pid }
        tvParentGroupLabel.text = match?.name ?: tvParentGroupLabel.text
    }
}
