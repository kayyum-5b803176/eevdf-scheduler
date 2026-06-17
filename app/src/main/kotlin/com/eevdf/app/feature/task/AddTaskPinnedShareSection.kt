package com.eevdf.app.feature.task

import android.view.View
import com.eevdf.data.task.Task
import com.eevdf.data.scheduler.EEVDFScheduler

/**
 * Realtime share and pinned share section for [AddTaskActivity].
 *
 * Separated so changes to the weight-calculation formula, validation logic,
 * or the slider-lock behaviour don't touch the core activity file.
 *
 * Domain:
 *   • [AddTaskActivity.setupRealtimeShare]       — wires the realtime share toggle
 *   • [AddTaskActivity.setupPinnedShare]         — wires TextWatcher on etPinnedShare
 *   • [AddTaskActivity.recalcWeightFromPinned]   — re-derives autoCalcWeight on any change
 *   • [AddTaskActivity.applySliderLock]          — dims slider when a pin is active
 *   • [AddTaskActivity.calcInternalWeight]       — delegates to EEVDFScheduler
 *   • [AddTaskActivity.validatePinnedShare]      — shows/hides warning text
 *   • [AddTaskActivity.populatePinnedShareSection] — restores switch + field from existing task
 */

internal fun AddTaskActivity.setupRealtimeShare() {
    switchRealtimeShare.setOnCheckedChangeListener { _, checked ->
        layoutRealtimeShareFields.visibility = if (checked) View.VISIBLE else View.GONE
        if (!checked) etPinnedShare.text?.clear()
    }
}

internal fun AddTaskActivity.setupPinnedShare() {
    etPinnedShare.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            validatePinnedShare(s?.toString()?.toDoubleOrNull())
            recalcWeightFromPinned()
        }
    })
}

/**
 * Re-runs the weight calculation from whatever is currently in [etPinnedShare].
 * Called both when the pinned share field changes AND when [viewModel.activeTasks]
 * emits (ensuring the calculation always uses a fresh sibling list, not a stale or
 * null snapshot).
 */
internal fun AddTaskActivity.recalcWeightFromPinned() {
    val value = etPinnedShare.text?.toString()?.toDoubleOrNull()
    autoCalcWeight = if (value != null && value in 0.01..99.99) calcInternalWeight(value) else null
    applySliderLock(value)
    updatePriorityDisplay()
}

/**
 * Locks or unlocks the priority slider based on whether a pinned share is set.
 * When locked the slider is visually dimmed so the user can see it is not interactive.
 */
internal fun AddTaskActivity.applySliderLock(pinnedValue: Double?) {
    val locked = pinnedValue != null
    sliderPriority.isEnabled = !locked
    sliderPriority.alpha     = if (locked) 0.38f else 1.0f
}

/**
 * Delegates to [EEVDFScheduler.calcPinnedWeight] using the task-editor's current
 * sibling context (parent spinner selection + live task list).
 *
 * Keeping this wrapper here lets the Activity pass the slider value as the
 * fallback weight (reasonable default for a task that has no float siblings yet).
 */
internal fun AddTaskActivity.calcInternalWeight(targetShare: Double): Double {
    val tasks = viewModel.activeTasks.value ?: emptyList()
    val selectedParentId: String? = if (groupsEnabled) {
        val idx = spinnerParent.selectedItemPosition
        groupsList.getOrNull(idx)?.id
    } else null
    return EEVDFScheduler.calcPinnedWeight(
        targetShare    = targetShare,
        parentId       = selectedParentId,
        excludeId      = existingTaskId,
        allTasks       = tasks,
        fallbackWeight = sliderPriority.value.toDouble()
    )
}

internal fun AddTaskActivity.validatePinnedShare(newValue: Double?) {
    if (newValue == null) {
        tvPinnedShareWarning.visibility = View.GONE
        return
    }
    val tasks = viewModel.activeTasks.value ?: emptyList()
    val selectedParentId: String? = if (groupsEnabled) {
        val idx = spinnerParent.selectedItemPosition
        groupsList.getOrNull(idx)?.id
    } else null
    val otherPinned = EEVDFScheduler.otherPinnedTotal(tasks, existingTaskId, selectedParentId)
    val total = otherPinned + newValue
    when {
        newValue < 0.01 || newValue > 99.99 -> {
            tvPinnedShareWarning.text = "Value must be between 0.01 and 99.99."
            tvPinnedShareWarning.visibility = View.VISIBLE
        }
        total > 100.0 -> {
            tvPinnedShareWarning.text =
                "Total pinned share would be ${"%.2f".format(total)}% " +
                "(siblings: ${"%.2f".format(otherPinned)}%). " +
                "Reduce this or sibling pinned tasks so total ≤ 100%."
            tvPinnedShareWarning.visibility = View.VISIBLE
        }
        total >= 99.99 -> {
            tvPinnedShareWarning.text =
                "Warning: all 100% is pinned. Floating tasks will receive 0% share."
            tvPinnedShareWarning.visibility = View.VISIBLE
        }
        else -> tvPinnedShareWarning.visibility = View.GONE
    }
}

/** Restores the realtime share toggle and pinned share field from [task]. */
internal fun AddTaskActivity.populatePinnedShareSection(task: Task) {
    // Restore auto-calc state: if the task already has an internalWeight it means
    // pinnedShare was previously set and the weight was derived from it.
    autoCalcWeight = if (task.pinnedShare != null) task.internalWeight else null
    applySliderLock(task.pinnedShare)
    // Note: updatePriorityDisplay() is called by populateCategoryPrioritySection after this

    task.pinnedShare?.let {
        switchRealtimeShare.isChecked        = true
        layoutRealtimeShareFields.visibility = View.VISIBLE
        etPinnedShare.setText("%.2f".format(it))
    }
}
