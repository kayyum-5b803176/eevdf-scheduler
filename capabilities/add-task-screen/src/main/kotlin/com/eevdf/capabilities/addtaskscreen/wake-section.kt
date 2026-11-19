package com.eevdf.capabilities.addtaskscreen

import android.view.View
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import com.eevdf.capabilities.taskstorage.Task
import com.eevdf.kernel.eventbus.Topics
import kotlinx.coroutines.launch

/**
 * Wake card for [AddTaskActivity]: the sound / vibration profile of the task's scheduled wake.
 *
 * This screen knows nothing about the wake itself. It ASKS over the bus which profile the task
 * has (`wake.profile-query`, answered by task-wake) and, on Save, TELLS it the choice
 * (`wake.profile-set`) — and only when the person actually changed it, so saving a task never
 * creates a wake row on its own. The four profiles are the Task Config ones ([taskTypeLabels]).
 *
 * The card is for leaf tasks: it is hidden while "Assign as group" is on.
 *
 * Domain:
 *   • [AddTaskActivity.setupWakeSection]    — the dropdown
 *   • [AddTaskActivity.populateWakeSection] — loads the task's current profile over the bus
 *   • [AddTaskActivity.updateWakeCardVisibility] — follows the container switch
 */
internal fun AddTaskActivity.setupWakeSection() {
    actvWakeProfile.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, taskTypeLabels))
    actvWakeProfile.setText(taskTypeLabels[taskTypeValues.indexOf(selectedWakeProfile).coerceAtLeast(0)], false)
    actvWakeProfile.setOnItemClickListener { _, _, pos, _ ->
        selectedWakeProfile = taskTypeValues.getOrElse(pos) { WAKE_PROFILE_DEFAULT }
    }
    updateWakeCardVisibility()
}

/** A wake belongs to a leaf task: hide the card for a container. */
internal fun AddTaskActivity.updateWakeCardVisibility() {
    cardWake.visibility = if (switchIsGroup.isChecked) View.GONE else View.VISIBLE
}

/** Asks task-wake which profile [task] has and shows it. Until the answer arrives the default is shown. */
internal fun AddTaskActivity.populateWakeSection(task: Task) {
    lifecycleScope.launch {
        val profile = bus.request(Topics.WAKE_PROFILE_QUERY, task.id, "add-task-screen") ?: WAKE_PROFILE_DEFAULT
        loadedWakeProfile   = profile
        selectedWakeProfile = profile
        actvWakeProfile.setText(taskTypeLabels[taskTypeValues.indexOf(profile).coerceAtLeast(0)], false)
    }
}

/** The profile a task has when nobody chose one: Alert. */
internal const val WAKE_PROFILE_DEFAULT = "ALARM"
