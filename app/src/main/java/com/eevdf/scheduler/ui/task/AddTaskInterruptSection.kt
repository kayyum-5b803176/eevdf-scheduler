package com.eevdf.scheduler.ui.task

import android.view.View
import com.eevdf.scheduler.model.task.Task

/**
 * Interrupt slot A/B section for [AddTaskActivity].
 *
 * Separated so changes to interrupt-slot assignment logic (e.g. adding a third
 * slot or changing mutual-exclusion rules) don't touch the core activity file.
 *
 * Domain:
 *   • [AddTaskActivity.setupInterruptSwitch]     — observes interrupt LiveData and wires mutual-exclusion
 *   • [AddTaskActivity.populateInterruptSection] — restores switch state from existing task
 */

internal fun AddTaskActivity.setupInterruptSwitch() {
    // ── Bug fix: observe LiveData instead of reading .value once ──────────
    // Previously used lifecycleScope.launch { viewModel.interruptTask.value }
    // which races with the ViewModel's own startup coroutine — value is null
    // on first open but populated after a rotation (ViewModel is retained).
    // Observing the LiveData means we always get the correct value as soon as
    // it is posted, whether on first open or after rotation.

    viewModel.interruptTask.observe(this) { currentA ->
        val isEditingA = existingTask?.interruptSlot == "A" && existingTask?.isInterrupt == true
        if (currentA != null && currentA.id != existingTaskId) {
            tvInterruptOwner.text = "Currently assigned to: \"${currentA.name}\""
            tvInterruptOwner.visibility  = View.VISIBLE
            switchIsInterrupt.isEnabled  = false   // must clear INT-A first
        } else {
            tvInterruptOwner.visibility  = View.GONE
            switchIsInterrupt.isEnabled  = true
        }
        // When editing the INT-A task itself, show it checked
        if (isEditingA) {
            switchIsInterrupt.isChecked  = true
            switchIsInterrupt.isEnabled  = true
            tvInterruptOwner.visibility  = View.GONE
        }
    }

    viewModel.interruptTaskB.observe(this) { currentB ->
        val isEditingB = existingTask?.interruptSlot == "B" && existingTask?.isInterrupt == true
        if (currentB != null && currentB.id != existingTaskId) {
            tvInterruptOwnerB.text = "Currently assigned to: \"${currentB.name}\""
            tvInterruptOwnerB.visibility  = View.VISIBLE
            switchIsInterruptB.isEnabled  = false  // must clear INT-B first
        } else {
            tvInterruptOwnerB.visibility  = View.GONE
            switchIsInterruptB.isEnabled  = true
        }
        if (isEditingB) {
            switchIsInterruptB.isChecked  = true
            switchIsInterruptB.isEnabled  = true
            tvInterruptOwnerB.visibility  = View.GONE
        }
    }

    // Mutual-exclusion: can't assign the same task to both slots at once
    switchIsInterrupt.setOnCheckedChangeListener  { _, checked -> if (checked) switchIsInterruptB.isChecked = false }
    switchIsInterruptB.setOnCheckedChangeListener { _, checked -> if (checked) switchIsInterrupt.isChecked  = false }
}

/** Restores interrupt switch state from [task]. */
internal fun AddTaskActivity.populateInterruptSection(task: Task) {
    if (!task.isInterrupt) return
    if (task.interruptSlot == "B") {
        switchIsInterruptB.isChecked  = true
        switchIsInterruptB.isEnabled  = true
        tvInterruptOwnerB.visibility  = View.GONE
    } else {
        switchIsInterrupt.isChecked   = true
        switchIsInterrupt.isEnabled   = true
        tvInterruptOwner.visibility   = View.GONE
    }
}

/** Inline helper — kept for binary compatibility; use LiveData observer instead. */
internal suspend fun AddTaskActivity.repository_getInterrupt(): Task? =
    viewModel.interruptTask.value
