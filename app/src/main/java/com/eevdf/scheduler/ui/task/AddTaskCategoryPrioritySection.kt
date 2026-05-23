package com.eevdf.scheduler.ui.task

import android.view.View
import com.eevdf.scheduler.model.task.Task
import com.google.android.material.chip.Chip

/**
 * Category chips and priority slider section for [AddTaskActivity].
 *
 * Separated so changes to category list or priority band labels (e.g. adding a
 * new category or renaming a band) don't touch the core activity file.
 *
 * Domain:
 *   • [taskCategories]                           — source-of-truth category list
 *   • [AddTaskActivity.setupCategoryChips]       — inflates chips into chipGroupCategory
 *   • [AddTaskActivity.setupPrioritySlider]      — configures the 1–7 slider
 *   • [AddTaskActivity.updatePriorityDisplay]    — refreshes label + info text
 *   • [AddTaskActivity.populateCategoryPrioritySection] — restores from existing task
 */

internal val taskCategories = listOf(
    "Work", "Study", "Health", "Personal", "Project", "Meeting", "General"
)

internal fun AddTaskActivity.setupCategoryChips() {
    taskCategories.forEach { cat ->
        val chip = Chip(this)
        chip.text        = cat
        chip.isCheckable = true
        chip.isChecked   = cat == selectedCategory
        chip.setOnCheckedChangeListener { _, checked -> if (checked) selectedCategory = cat }
        chipGroupCategory.addView(chip)
    }
}

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

/** Restores category chip selection and priority slider from [task]. */
internal fun AddTaskActivity.populateCategoryPrioritySection(task: Task) {
    selectedCategory = task.category
    for (i in 0 until chipGroupCategory.childCount) {
        val chip = chipGroupCategory.getChildAt(i) as? Chip
        chip?.isChecked = chip?.text == task.category
    }
    // Priority slider is set in populateBasicFields; only display refresh needed here
    // because autoCalcWeight may have just been set by populatePinnedShareSection.
    updatePriorityDisplay()
}
