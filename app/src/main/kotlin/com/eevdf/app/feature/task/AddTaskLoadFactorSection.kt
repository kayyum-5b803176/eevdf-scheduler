package com.eevdf.app.feature.task

import android.view.View
import androidx.lifecycle.lifecycleScope
import com.eevdf.data.task.Task
import com.eevdf.data.task.TaskLoadFactor
import kotlinx.coroutines.launch

/**
 * Load Factor section for [AddTaskActivity] — human effort model.
 *
 * The section uses a toggle switch (same pattern as Quota and Scheduler Class):
 *   OFF → inherited from nearest ancestor that has the toggle ON, or midpoint
 *         default (50 / 100) when no ancestor is configured.  The (auto) badge
 *         is shown and the sliders are hidden.
 *   ON  → three NASA-TLX-derived Material Sliders (1–7) are visible.
 *         The combined load (0–100) updates live as the user drags.
 *
 * ── Domain functions ────────────────────────────────────────────────────────
 *   [setupLoadFactorSection]     — wires the switch and three slider listeners.
 *   [updateLoadFactorPreview]    — recomputes and displays "Combined Load: X / 100".
 *   [applyParentLoadFactor]      — called by [AddTaskGroupSection] when a parent
 *                                  group is selected; async-fetches the parent's
 *                                  [TaskLoadFactor] and mirrors it into the form
 *                                  as an inherited starting point.
 *   [populateLoadFactorSection]  — restores full state from an existing task's
 *                                  [Task] + [TaskLoadFactor] on edit open.
 *
 * ── Inheritance flow ─────────────────────────────────────────────────────────
 *   New task, no parent      → switch OFF, sliders at 4/4/4, badge shown.
 *   New task + parent picked → [applyParentLoadFactor] copies parent's sliders,
 *                              keeps switch OFF, badge shown.
 *   User enables switch      → sliders visible with inherited values pre-filled
 *                              as the starting point; [isLoadFactorInherited] cleared.
 *   User disables switch     → sliders hidden; badge shown; [isLoadFactorInherited]
 *                              restored so repository propagation resumes.
 *   Edit existing task       → [populateLoadFactorSection] reads the side table
 *                              entry and restores exactly what was last saved.
 */

/** Wires the switch toggle and the three slider change listeners. */
internal fun AddTaskActivity.setupLoadFactorSection() {

    // Toggle: show/hide sliders and (auto) badge
    switchLoadFactorEnabled.setOnCheckedChangeListener { _, checked ->
        layoutLoadFactorSliders.visibility = if (checked) View.VISIBLE else View.GONE
        tvLoadFactorAutoLabel.visibility   = if (checked) View.GONE    else View.VISIBLE
        // Enabling manually clears inheritance; disabling re-enables it.
        isLoadFactorInherited = !checked
    }

    // Slider listeners: update the numeric label + preview on every drag step
    sliderCognitive.addOnChangeListener { _, value, _ ->
        tvCognitiveValue.text = value.toInt().toString()
        updateLoadFactorPreview()
    }
    sliderPhysical.addOnChangeListener { _, value, _ ->
        tvPhysicalValue.text = value.toInt().toString()
        updateLoadFactorPreview()
    }
    sliderEmotional.addOnChangeListener { _, value, _ ->
        tvEmotionalValue.text = value.toInt().toString()
        updateLoadFactorPreview()
    }
}

/**
 * Recomputes and displays "Combined Load: X / 100" using the current slider positions.
 * Called on every slider change and after any programmatic slider update.
 */
internal fun AddTaskActivity.updateLoadFactorPreview() {
    val combined = TaskLoadFactor.compute(
        cognitive = sliderCognitive.value.toInt(),
        physical  = sliderPhysical.value.toInt(),
        emotional = sliderEmotional.value.toInt(),
    )
    tvLoadFactorCombined.text = "Combined Load: ${combined.toInt()} / 100"
}

/**
 * Sets sliders to the given values without triggering inheritance-clear logic.
 * Used internally by [applyParentLoadFactor] and [populateLoadFactorSection].
 */
private fun AddTaskActivity.applySliderValues(cognitive: Int, physical: Int, emotional: Int) {
    sliderCognitive.value  = cognitive.toFloat()
    sliderPhysical.value   = physical.toFloat()
    sliderEmotional.value  = emotional.toFloat()
    tvCognitiveValue.text  = cognitive.toString()
    tvPhysicalValue.text   = physical.toString()
    tvEmotionalValue.text  = emotional.toString()
    updateLoadFactorPreview()
}

/**
 * Called by [AddTaskGroupSection] when a parent group is selected.
 *
 * Asynchronously fetches the parent's [TaskLoadFactor] side table entry and
 * mirrors its slider values into this form.  The switch is kept OFF (inherited)
 * so the sliders are hidden — they serve as a pre-filled starting point if the
 * user later enables the toggle.
 *
 * @param parentTask the newly selected parent [Task].
 */
internal fun AddTaskActivity.applyParentLoadFactor(parentTask: Task) {
    isLoadFactorInherited = true
    // Ensure switch is OFF and badge is visible
    if (switchLoadFactorEnabled.isChecked) {
        switchLoadFactorEnabled.isChecked  = false
        layoutLoadFactorSliders.visibility = View.GONE
    }
    tvLoadFactorAutoLabel.visibility = View.VISIBLE

    // Fetch the parent's side table entry to mirror its slider values
    lifecycleScope.launch {
        val entry = viewModel.getLoadFactor(parentTask.id)
        applySliderValues(
            cognitive = entry?.cognitive ?: TaskLoadFactor.DEFAULT_COGNITIVE,
            physical  = entry?.physical  ?: TaskLoadFactor.DEFAULT_PHYSICAL,
            emotional = entry?.emotional ?: TaskLoadFactor.DEFAULT_EMOTIONAL,
        )
    }
}

/**
 * Called by [AddTaskGroupSection] when the parent group is deselected while
 * [isLoadFactorInherited] is true.  Resets to midpoint defaults and re-shows
 * the badge, leaving the switch OFF so subsequent parent picks can inherit again.
 */
internal fun AddTaskActivity.resetLoadFactorToDefault() {
    isLoadFactorInherited            = true
    tvLoadFactorAutoLabel.visibility = View.VISIBLE
    if (switchLoadFactorEnabled.isChecked) {
        switchLoadFactorEnabled.isChecked  = false
        layoutLoadFactorSliders.visibility = View.GONE
    }
    applySliderValues(
        cognitive = TaskLoadFactor.DEFAULT_COGNITIVE,
        physical  = TaskLoadFactor.DEFAULT_PHYSICAL,
        emotional = TaskLoadFactor.DEFAULT_EMOTIONAL,
    )
}

/**
 * Restores load factor state when editing an existing task.
 *
 * Asynchronously reads the [TaskLoadFactor] side table entry and populates:
 *   • switch position (ON / OFF)
 *   • slider values (from entry, or midpoint defaults if no entry exists)
 *   • (auto) badge visibility
 *   • live preview label
 *
 * Called from [AddTaskActivity.populateBasicFields].
 */
internal fun AddTaskActivity.populateLoadFactorSection(task: Task) {
    isLoadFactorInherited = task.loadFactorInherited
    lifecycleScope.launch {
        val entry   = task.id.takeIf { it.isNotEmpty() }?.let { viewModel.getLoadFactor(it) }
        val enabled = entry?.enabled ?: false

        // Restore switch without triggering its listener (listener reads state after set)
        switchLoadFactorEnabled.isChecked  = enabled
        layoutLoadFactorSliders.visibility = if (enabled) View.VISIBLE else View.GONE
        tvLoadFactorAutoLabel.visibility   = if (enabled) View.GONE    else View.VISIBLE

        applySliderValues(
            cognitive = entry?.cognitive ?: TaskLoadFactor.DEFAULT_COGNITIVE,
            physical  = entry?.physical  ?: TaskLoadFactor.DEFAULT_PHYSICAL,
            emotional = entry?.emotional ?: TaskLoadFactor.DEFAULT_EMOTIONAL,
        )
    }
}
