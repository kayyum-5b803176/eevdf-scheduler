package com.eevdf.scheduler.ui.task

import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import com.eevdf.scheduler.model.task.Task

/**
 * Category input and priority slider section for [AddTaskActivity].
 *
 * Separated so changes to category behaviour or priority band labels don't
 * touch the core activity file.
 *
 * Domain:
 *   • [AddTaskActivity.setupCategoryInput]          — wires the autocomplete field
 *   • [AddTaskActivity.setupPrioritySlider]         — configures the 1–7 slider
 *   • [AddTaskActivity.updatePriorityDisplay]       — refreshes label + info text
 *   • [AddTaskActivity.populateCategoryPrioritySection] — restores from existing task
 *
 * Category suggestions are drawn live from [TaskViewModel.distinctCategories], which
 * queries SELECT DISTINCT category FROM tasks.  Every category string the user has
 * ever saved is therefore available as a suggestion when editing any other task.
 * The field accepts free text — any new string is stored as-is and automatically
 * appears as a suggestion on the next edit.
 *
 * Import / export: category is serialised by [BackupManager] via [Task.category]
 * (already handled — no extra work required here).
 */

/**
 * Wires the category [AutoCompleteTextView]:
 *
 *  1. Creates an [ArrayAdapter] backed by the live [TaskViewModel.distinctCategories]
 *     list and attaches it to [AddTaskActivity.etCategoryInput].
 *  2. Sets [completionThreshold] to 1 so suggestions appear after the first keystroke.
 *  3. Keeps [AddTaskActivity.selectedCategory] in sync via [TextWatcher] so
 *     [AddTaskSaveHandler] can read it without changes.
 *  4. Pre-fills the field with the current [AddTaskActivity.selectedCategory] (default
 *     "General" for new tasks; overwritten by [populateCategoryPrioritySection] for
 *     edits).
 *
 * The observer rebuilds the adapter list whenever a new category is saved to the DB,
 * so the suggestions are always fresh without any manual refresh.
 */
internal fun AddTaskActivity.setupCategoryInput() {
    val adapter = ArrayAdapter<String>(
        this,
        android.R.layout.simple_dropdown_item_1line,
        mutableListOf()
    )
    etCategoryInput.setAdapter(adapter)
    etCategoryInput.threshold = 1

    // Pre-fill with the default (or whatever populateCategoryPrioritySection set before
    // this call — in practice setupCategoryInput is called before loadExistingTask so
    // this writes the default "General"; the edit path overwrites it afterward).
    etCategoryInput.setText(selectedCategory, false)

    // Keep selectedCategory in sync so AddTaskSaveHandler reads the correct value.
    etCategoryInput.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val typed = s?.toString()?.trim() ?: ""
            selectedCategory = typed.ifEmpty { "General" }
        }
    })

    // Rebuild suggestions whenever the DB category set changes (e.g. after saving a
    // task with a new category string in another session or tab).
    viewModel.distinctCategories.observe(this) { categories ->
        adapter.clear()
        adapter.addAll(categories)
        adapter.notifyDataSetChanged()
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

/**
 * Restores category input and priority slider from [task].
 *
 * [AutoCompleteTextView.setText] is called with `filter = false` to set the
 * text without triggering the suggestion dropdown or re-running the adapter
 * filter — the user has not typed anything yet so no suggestions should appear.
 */
internal fun AddTaskActivity.populateCategoryPrioritySection(task: Task) {
    selectedCategory = task.category
    etCategoryInput.setText(task.category, false)
    // Priority slider is set in populateBasicFields; only display refresh needed here
    // because autoCalcWeight may have just been set by populatePinnedShareSection.
    updatePriorityDisplay()
}
