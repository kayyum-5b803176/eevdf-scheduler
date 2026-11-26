package com.eevdf.capabilities.addtaskscreen

import android.view.View
import android.widget.Toast
import com.eevdf.capabilities.taskstorage.IdentityRules
import com.eevdf.capabilities.taskstorage.PickerDrill
import com.eevdf.capabilities.taskstorage.Task
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
            .sortedWith(SortHelper.taskNameComparator)
        groupsList.addAll(filteredSorted)

        selectedParentId?.let { pid ->
            val match = filteredSorted.firstOrNull { it.id == pid }
            if (match != null) actvParentGroup.setText(parentFieldText(match), false)
        }
    }

    actvParentGroup.setOnClickListener {
        val dialog = PickerDialog().apply {
            allGroups      = allowedParentGroups()      // only the kinds this task's kind may sit in
            pathContext    = groupsList.filterNotNull() // every group, so a repeated name can be told apart by where it is
            currentGroupId = selectedParentId
            onGroupSelected = { chosen -> applyParentChoice(chosen) }
        }
        dialog.show(supportFragmentManager, "group_picker")
    }
}

/**
 * The groups the picker offers: all of them while the task's kind is Undefined (or has no rule), otherwise only
 * groups of a kind it may sit in — a User is offered only groups of kind Group, a Thread only Processes, and so on
 * ([IdentityRules.allowedParentKinds]). It follows [selectedIdentityKind], so changing the kind changes the list.
 */
internal fun AddTaskActivity.allowedParentGroups(): List<Task> =
    groupsList.filterNotNull().filter {
        IdentityRules.canBeParent(selectedIdentityKind, IdentityRules.kindOf(it.identity))
    }

/**
 * Called when the kind changes: if the group the task is in is no longer one its new kind may sit in, the task
 * is moved back to the root, exactly as if the person had picked "None" — with a note, since it changed on its own.
 */
internal fun AddTaskActivity.revalidateParentForKind() {
    val pid = selectedParentId ?: return
    val parent = groupsList.filterNotNull().firstOrNull { it.id == pid } ?: return
    if (IdentityRules.canBeParent(selectedIdentityKind, IdentityRules.kindOf(parent.identity))) return
    applyParentChoice(null)
    Toast.makeText(this, "A ${selectedIdentityKind.label.lowercase()} can't sit in \"${parent.name}\" — moved to the root",
        Toast.LENGTH_SHORT).show()
}

/**
 * What the parent field shows for [parent]: its name, or — when other groups the picker offers share that name — the
 * path as identity badges that tells them apart (`104.pgroup › process1`, see [PickerDrill.label]).
 */
internal fun AddTaskActivity.parentFieldText(parent: Task?): String {
    if (parent == null) return "None (root level)"
    return PickerDrill.label(parent, allowedParentGroups(), groupsList.filterNotNull().associateBy { it.id })
}

/** Everything that follows from choosing [chosen] as the parent (null = root): the field, and the inherited defaults. */
internal fun AddTaskActivity.applyParentChoice(chosen: Task?) {
    selectedParentId = chosen?.id
    actvParentGroup.setText(parentFieldText(chosen), false)

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

internal fun AddTaskActivity.populateGroupSection(task: Task) {
    if (groupsEnabled) {
        switchIsGroup.isChecked = task.isGroup
    }
    val pid = task.parentId
    if (pid != null) {
        selectedParentId = pid
        val match = groupsList.filterNotNull().firstOrNull { it.id == pid }
        actvParentGroup.setText(match?.let { parentFieldText(it) } ?: actvParentGroup.text.toString(), false)
    }
}
