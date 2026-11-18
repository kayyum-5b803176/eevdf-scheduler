package com.eevdf.capabilities.addtaskscreen

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import com.eevdf.capabilities.taskstorage.SemaphoreRules
import com.eevdf.capabilities.taskstorage.Task

/**
 * Semaphore section for [AddTaskActivity].
 *
 * A card in the same style as Quota / Sleep: a header switch that reveals one
 * number field (N slots, 1–64, default 4). The rules themselves — who waits, who
 * is admitted, what releases a slot — live in task-storage ([SemaphoreRules],
 * TaskRepository); this file only edits the two settings on the group.
 *
 * Visibility
 *   The card is shown only while "Assign as group (container)" is ON (and groups
 *   are enabled in settings). Turning the container OFF merely hides the card: the
 *   stored switch and N are NOT touched — and neither are the children's slots —
 *   so turning the container back on needs no reconfiguring. The only way to
 *   release the queue is to switch the semaphore itself off here.
 *
 * Domain:
 *   • [AddTaskActivity.setupSemaphoreSection]    — switch, field, container-switch listener
 *   • [AddTaskActivity.populateSemaphoreSection] — restores switch and N from a saved task
 *   • [AddTaskActivity.readSemaphore]            — validation + parse used by SaveHandler
 */
internal fun AddTaskActivity.setupSemaphoreSection() {
    switchSemaphoreEnabled.setOnCheckedChangeListener { _, checked ->
        layoutSemaphoreFields.visibility = if (checked) View.VISIBLE else View.GONE
        tvSemaphoreError.visibility = View.GONE
    }

    etSemaphoreLimit.setText(SemaphoreRules.DEFAULT_LIMIT.toString())
    etSemaphoreLimit.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { tvSemaphoreError.visibility = View.GONE }
    })

    // The card follows the container switch (the user can flip it on this very screen).
    switchIsGroup.setOnCheckedChangeListener { _, _ -> updateSemaphoreCardVisibility() }
    updateSemaphoreCardVisibility()
}

/** Shows the card only for a container, and only when groups are enabled in settings. */
internal fun AddTaskActivity.updateSemaphoreCardVisibility() {
    cardSemaphore.visibility = if (groupsEnabled && switchIsGroup.isChecked) View.VISIBLE else View.GONE
}

/** Restores the switch and N from [task] — even when the card is hidden, so the values survive a save. */
internal fun AddTaskActivity.populateSemaphoreSection(task: Task) {
    switchSemaphoreEnabled.isChecked = task.semaphoreEnabled
    layoutSemaphoreFields.visibility = if (task.semaphoreEnabled) View.VISIBLE else View.GONE
    etSemaphoreLimit.setText(SemaphoreRules.clampLimit(task.semaphoreLimit).toString())
    updateSemaphoreCardVisibility()
}

/** What the Semaphore card holds, ready to be saved on the group. */
internal data class SemaphoreInput(val enabled: Boolean, val limit: Int)

/**
 * Validates and parses the Semaphore card.
 *
 * @return the settings to save, or null when the switch is on but N is empty or
 *         outside 1–64 — in which case the inline error is shown and the caller
 *         must abort the save.
 *
 * Card hidden (not a container): the stored values are returned untouched, so a
 * save from a non-container never changes — or resets — a semaphore that is still
 * holding children back. Switch off: the switch is saved as off and N is kept.
 */
internal fun AddTaskActivity.readSemaphore(): SemaphoreInput? {
    val stored = existingTask
    val storedLimit = SemaphoreRules.clampLimit(stored?.semaphoreLimit ?: SemaphoreRules.DEFAULT_LIMIT)

    if (cardSemaphore.visibility != View.VISIBLE) {
        return SemaphoreInput(stored?.semaphoreEnabled ?: false, storedLimit)
    }

    val typed = etSemaphoreLimit.text?.toString().orEmpty().trim().toIntOrNull()

    if (!switchSemaphoreEnabled.isChecked) {
        tvSemaphoreError.visibility = View.GONE
        return SemaphoreInput(false, typed?.takeIf { it in SemaphoreRules.MIN_LIMIT..SemaphoreRules.MAX_LIMIT } ?: storedLimit)
    }

    if (typed == null || typed !in SemaphoreRules.MIN_LIMIT..SemaphoreRules.MAX_LIMIT) {
        tvSemaphoreError.text = "Enter a whole number from ${SemaphoreRules.MIN_LIMIT} to ${SemaphoreRules.MAX_LIMIT}"
        tvSemaphoreError.visibility = View.VISIBLE
        layoutSemaphoreFields.visibility = View.VISIBLE
        etSemaphoreLimit.requestFocus()
        return null
    }
    tvSemaphoreError.visibility = View.GONE
    return SemaphoreInput(true, typed)
}
