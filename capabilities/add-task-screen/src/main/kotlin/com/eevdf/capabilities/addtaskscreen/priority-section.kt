package com.eevdf.capabilities.addtaskscreen

import com.eevdf.capabilities.taskstorage.Task

/**
 * Priority slider section for [AddTaskActivity].
 *
 * Split out of the old CategoryPrioritySection when Category became Identity
 * (see IdentitySection.kt): the slider never had anything to do with category, it
 * only shared the file.
 *
 * Domain:
 *   • [AddTaskActivity.setupPrioritySlider]         — configures the 1–7 slider
 *   • [AddTaskActivity.updatePriorityDisplay]       — refreshes label + info text
 *   • [AddTaskActivity.populatePrioritySection]     — refreshes the display after a restore
 */

internal fun AddTaskActivity.setupPrioritySlider() {
    sliderPriority.valueFrom = 1f
    sliderPriority.valueTo   = 7f
    sliderPriority.stepSize  = 1f
    sliderPriority.value     = 4f
    updatePriorityDisplay()
    sliderPriority.addOnChangeListener { _, _, _ -> updatePriorityDisplay() }
}

/**
 * Updates the priority label and description.
 *
 * • When [autoCalcWeight] is set (pinnedShare driven): shows the calculated
 *   decimal weight ("Priority: 34.44 (auto)") and a short explanation.
 * • Otherwise: shows the slider integer and its human-readable band label.
 */
internal fun AddTaskActivity.updatePriorityDisplay() {
    val w = autoCalcWeight
    if (w != null) {
        tvPriorityLabel.text = "Priority: ${"%.2f".format(w)} (auto)"
        tvPriorityInfo.text  = "Weight auto-calculated from pinned share. " +
            "Matches your target allocation if the pin is later removed."
    } else {
        val p = sliderPriority.value.toInt()
        tvPriorityLabel.text = "Priority: $p"
        tvPriorityInfo.text  = when (p) {
            7    -> "Critical — Maximum weight. Runs first."
            6    -> "High — Prioritized over most tasks."
            5    -> "Above Average — Scheduled before medium tasks."
            4    -> "Medium — Balanced scheduling weight."
            3    -> "Below Average — Slightly deprioritized."
            2    -> "Low — Runs after higher-priority tasks."
            else -> "Minimal — Runs only when nothing else is pending."
        }
    }
}

@Deprecated("Use updatePriorityDisplay()", ReplaceWith("updatePriorityDisplay()"))
internal fun AddTaskActivity.updatePriorityInfo(@Suppress("UNUSED_PARAMETER") priority: Int) =
    updatePriorityDisplay()

/**
 * Refreshes the priority label after the task is restored.
 * The slider value itself is set in populateBasicFields; only the display refresh is
 * needed here because [autoCalcWeight] may have just been set by populatePinnedShareSection.
 */
@Suppress("UNUSED_PARAMETER")
internal fun AddTaskActivity.populatePrioritySection(task: Task) {
    updatePriorityDisplay()
}
