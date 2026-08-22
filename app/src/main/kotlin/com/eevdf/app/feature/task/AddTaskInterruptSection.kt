package com.eevdf.app.feature.task

import android.view.View
import android.widget.ArrayAdapter
import com.eevdf.data.task.Task

/**
 * Interrupt slot section for [AddTaskActivity].
 *
 * Replaced the previous two-switch design (one switch per slot) with a single
 * "Assign as interrupt" toggle that reveals a slot picker dropdown (INT-A / INT-B)
 * and a single conflict-owner text when enabled.
 *
 * Domain:
 *   • [AddTaskActivity.setupInterruptSwitch]     — wires toggle, slot picker, and conflict text
 *   • [AddTaskActivity.populateInterruptSection] — restores state from existing task
 */

private val interruptSlotLabels = listOf("INT-A", "INT-B")

internal fun AddTaskActivity.setupInterruptSwitch() {
    val slotAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, interruptSlotLabels)
    actvInterruptSlot.setAdapter(slotAdapter)
    actvInterruptSlot.setText("INT-A", false)   // default slot

    // Toggle shows/hides slot picker
    switchIsInterrupt.setOnCheckedChangeListener { _, checked ->
        layoutInterruptSlotPicker.visibility = if (checked) View.VISIBLE else View.GONE
        if (!checked) tvInterruptOwner.visibility = View.GONE
        refreshInterruptConflict()
    }

    // Slot change refreshes conflict warning
    actvInterruptSlot.setOnItemClickListener { _, _, _, _ ->
        refreshInterruptConflict()
    }

    // Observe both slots so the conflict text stays current if another screen
    // assigns a task while this form is open
    viewModel.interruptTask.observe(this)  { refreshInterruptConflict() }
    viewModel.interruptTaskB.observe(this) { refreshInterruptConflict() }
}

/**
 * Checks whether the currently selected interrupt slot is already held by
 * another task and updates [tvInterruptOwner] accordingly.
 *
 * Called on toggle change, slot change, and LiveData updates.
 */
private fun AddTaskActivity.refreshInterruptConflict() {
    if (!switchIsInterrupt.isChecked) {
        tvInterruptOwner.visibility = View.GONE
        return
    }

    val slotIsB      = actvInterruptSlot.text.toString() == "INT-B"
    val slotKey      = if (slotIsB) "B" else "A"
    val holder       = if (slotIsB) viewModel.interruptTaskB.value else viewModel.interruptTask.value
    val editingSlot  = existingTask?.isInterrupt == true && existingTask?.interruptSlot == slotKey

    if (holder != null && holder.id != existingTaskId && !editingSlot) {
        tvInterruptOwner.text      = "Slot taken by: \"${holder.name}\""
        tvInterruptOwner.visibility = View.VISIBLE
    } else {
        tvInterruptOwner.visibility = View.GONE
    }
}

/** Restores interrupt toggle and slot picker from [task]. */
internal fun AddTaskActivity.populateInterruptSection(task: Task) {
    if (!task.isInterrupt) return
    switchIsInterrupt.isChecked       = true
    layoutInterruptSlotPicker.visibility = View.VISIBLE
    actvInterruptSlot.setText(
        if (task.interruptSlot == "B") "INT-B" else "INT-A",
        false
    )
}
