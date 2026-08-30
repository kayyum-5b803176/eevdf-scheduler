package com.eevdf.feature.task

import android.view.View
import androidx.lifecycle.lifecycleScope
import com.eevdf.data.task.Task
import com.eevdf.data.task.TaskLoadFactor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Load Factor section for [AddTaskActivity] — human effort model.
 *
 * ── Layout (see section_add_task_loadfactor.xml) ────────────────────────────
 * Row 1 (always): "Load Factor" title + toggle switch
 * Row 2 (always): (auto) badge left | spacer | Combined Load right
 * Row 3 (toggle ON only): divider + 3 sliders
 *
 * Combined Load is always visible so the user sees the inherited or live
 * value without needing to enable the toggle.
 *
 * ── Toggle behaviour ────────────────────────────────────────────────────────
 * OFF (default) — inherited from nearest ancestor with toggle ON, or mid-point
 *                 default (50) when no ancestor is configured.
 *                 (auto) badge visible.  Sliders hidden.
 *                 Combined Load shows the inherited/default value.
 *
 * ON — sliders visible, pre-filled with the currently inherited slider values
 *      so the user starts from the parent state rather than a blank mid-point.
 *      (auto) badge hidden.  Combined Load updates live on every drag.
 *
 * ── Bug fix (this version) ───────────────────────────────────────────────────
 * When the task has no [TaskLoadFactor] side table entry (created before
 * v4.11.0, or propagation has not run yet), slider values are approximated
 * from [Task.loadFactor] via [approximateSlider] so enabling the toggle always
 * shows a meaningful starting point rather than reverting to 4/4/4.
 */

// ── Public entry points ───────────────────────────────────────────────────────

/** Wires the toggle switch and the three slider change listeners. */
internal fun AddTaskActivity.setupLoadFactorSection() {

    switchLoadFactorEnabled.setOnCheckedChangeListener { _, checked ->
        layoutLoadFactorSliders.visibility = if (checked) View.VISIBLE else View.GONE
        tvLoadFactorAutoLabel.visibility   = if (checked) View.GONE    else View.VISIBLE
        isLoadFactorInherited              = !checked
        // Combined Load (tvLoadFactorCombined) stays visible regardless — no toggle
    }

    // Sliders: update numeric label + combined preview on every step
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
 * Recomputes and displays "Combined Load: X / 100".
 * Called on every slider change and after any programmatic slider update.
 * Also called when the section is populated in disabled state so the inherited
 * value appears in Row 2 without the user needing to enable the toggle.
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
 * Sets all three sliders and their numeric labels, then refreshes the preview.
 * Used internally — does NOT change toggle or visibility state.
 */
private fun AddTaskActivity.applySliderValues(cognitive: Int, physical: Int, emotional: Int) {
    sliderCognitive.value = cognitive.toFloat()
    sliderPhysical.value  = physical.toFloat()
    sliderEmotional.value = emotional.toFloat()
    tvCognitiveValue.text = cognitive.toString()
    tvPhysicalValue.text  = physical.toString()
    tvEmotionalValue.text = emotional.toString()
    updateLoadFactorPreview()
}

/**
 * Approximates equal slider values from a combined [loadFactor] (0–100).
 * Used when no [TaskLoadFactor] side table entry exists for a task.
 *
 * Inverse of [TaskLoadFactor.dimensionPercent]:
 *   loadFactor = 0   → slider 1  (zero contribution)
 *   loadFactor = 50  → slider 4  (mid-intensity)
 *   loadFactor = 100 → slider 7  (maximum)
 */
private fun approximateSlider(loadFactor: Double): Int =
    ((loadFactor / 100.0) * 6.0 + 1.0).roundToInt().coerceIn(1, 7)

// ── Inheritance ───────────────────────────────────────────────────────────────

/**
 * Walks up the ancestor chain starting from [startTask] to find the nearest
 * task whose [TaskLoadFactor] has [TaskLoadFactor.enabled] == true (manually
 * configured by the user).  Returns that entry's three slider values as a
 * Triple(cognitive, physical, emotional).
 *
 * Fallback rules (in order):
 *   1. Nearest ancestor with enabled == true  → its slider values (per-dimension)
 *   2. No such ancestor exists (all auto / no entries) → DEFAULT (4, 4, 4)
 *
 * This guarantees the correct per-dimension values propagate down the whole
 * chain even when intermediate ancestors are also set to auto.
 */
private suspend fun AddTaskActivity.resolveEffectiveSliders(
    startTask: Task,
): Triple<Int, Int, Int> {
    var current: Task? = startTask
    while (current != null) {
        val entry = viewModel.getLoadFactor(current.id)
        if (entry != null && entry.enabled) {
            // Found a manually-configured ancestor — use its exact per-dimension values
            return Triple(entry.cognitive, entry.physical, entry.emotional)
        }
        // This task is also auto (or has no entry) — walk up to its parent
        val parentId = current.parentId ?: break
        current = viewModel.getTaskById(parentId)
    }
    // No manually-configured ancestor exists — fall back to mid-point defaults
    return Triple(
        TaskLoadFactor.DEFAULT_COGNITIVE,
        TaskLoadFactor.DEFAULT_PHYSICAL,
        TaskLoadFactor.DEFAULT_EMOTIONAL,
    )
}

/**
 * Called by [AddTaskGroupSection] when a parent group is selected.
 *
 * Resolves the effective inherited slider values by walking up the ancestor
 * chain via [resolveEffectiveSliders] — so if the immediate parent is also
 * set to auto, we look further up until we find a manually-configured ancestor.
 * If no ancestor is manually configured, sliders default to (4, 4, 4).
 *
 * The toggle is kept OFF — sliders are hidden but pre-filled.  Combined Load
 * in Row 2 updates to reflect the inherited value immediately.
 */
internal fun AddTaskActivity.applyParentLoadFactor(parentTask: Task) {
    isLoadFactorInherited = true
    if (switchLoadFactorEnabled.isChecked) {
        switchLoadFactorEnabled.isChecked  = false
        layoutLoadFactorSliders.visibility = View.GONE
    }
    tvLoadFactorAutoLabel.visibility = View.VISIBLE

    lifecycleScope.launch {
        val (c, p, e) = resolveEffectiveSliders(parentTask)
        applySliderValues(c, p, e)
    }
}

/**
 * Called by [AddTaskGroupSection] when the parent group is deselected while
 * [isLoadFactorInherited] is true.  Resets to mid-point defaults with
 * toggle OFF so subsequent parent picks can inherit again cleanly.
 */
internal fun AddTaskActivity.resetLoadFactorToDefault() {
    isLoadFactorInherited = true
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

// ── Populate on edit ─────────────────────────────────────────────────────────

/**
 * Restores load factor state when editing an existing task.
 *
 * Reads the [TaskLoadFactor] side table entry for the task and restores:
 *   • Toggle position (ON = enabled, OFF = inherited)
 *   • Slider values — resolved differently depending on toggle state:
 *       ON  → this task's own manually-set slider values (from the entry)
 *       OFF → walk up the ancestor chain via [resolveEffectiveSliders] to find
 *             the nearest ancestor with [TaskLoadFactor.enabled] == true and
 *             use its per-dimension values.  Falls back to (4, 4, 4) when no
 *             manually-configured ancestor exists.
 *   • (auto) badge visibility
 *   • Combined Load label (always updated, visible in both states)
 *
 * Called from [AddTaskActivity.populateBasicFields].
 */
internal fun AddTaskActivity.populateLoadFactorSection(task: Task) {
    isLoadFactorInherited = task.loadFactorInherited
    lifecycleScope.launch {
        val entry   = task.id.takeIf { it.isNotEmpty() }?.let { viewModel.getLoadFactor(it) }
        val enabled = entry?.enabled ?: false

        val (c, p, e) = if (enabled) {
            // Toggle ON: use this task's own manually-set slider values directly.
            Triple(entry!!.cognitive, entry.physical, entry.emotional)
        } else {
            // Toggle OFF (inherited): walk up the ancestor chain to find the nearest
            // task with enabled == true and use its per-dimension slider values.
            // This is correct even when intermediate ancestors are also auto —
            // we always trace back to the actual manually-configured source.
            val parentId = task.parentId
            if (parentId != null) {
                val parentTask = viewModel.getTaskById(parentId)
                if (parentTask != null) {
                    resolveEffectiveSliders(parentTask)
                } else {
                    // Parent no longer exists in the DB (deleted?) — best-effort fallback:
                    // use the task's own stored entry values if available, otherwise default.
                    when {
                        entry != null -> Triple(entry.cognitive, entry.physical, entry.emotional)
                        task.loadFactor > 0.0 -> {
                            val approx = approximateSlider(task.loadFactor)
                            Triple(approx, approx, approx)
                        }
                        else -> Triple(
                            TaskLoadFactor.DEFAULT_COGNITIVE,
                            TaskLoadFactor.DEFAULT_PHYSICAL,
                            TaskLoadFactor.DEFAULT_EMOTIONAL,
                        )
                    }
                }
            } else {
                // No parent — task is at root level in auto mode; use mid-point defaults.
                Triple(
                    TaskLoadFactor.DEFAULT_COGNITIVE,
                    TaskLoadFactor.DEFAULT_PHYSICAL,
                    TaskLoadFactor.DEFAULT_EMOTIONAL,
                )
            }
        }

        // Apply toggle state (fires listener → sets isLoadFactorInherited and visibility)
        switchLoadFactorEnabled.isChecked  = enabled
        layoutLoadFactorSliders.visibility = if (enabled) View.VISIBLE else View.GONE
        tvLoadFactorAutoLabel.visibility   = if (enabled) View.GONE    else View.VISIBLE

        // Apply slider values — pre-fills sliders even in disabled state
        // so enabling the toggle immediately shows the inherited values
        applySliderValues(c, p, e)
    }
}
