package com.eevdf.capabilities.addtaskscreen

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import com.eevdf.capabilities.taskstorage.Task

/**
 * Sleep section for [AddTaskActivity].
 *
 * Laid out like Quota Limit: a header switch that reveals one text field with
 * the same `NdNhNmNs` format, range and live preview. [parseQuotaInput] /
 * [formatQuotaDuration] are reused as-is, so the accepted range (1 s – 365 d)
 * can never drift from the other duration fields.
 *
 * The value is the length of sleep the moon button on the task's card applies.
 *   • switch OFF — no sleep time (stored as 0); the moon asks the user to set one.
 *   • switch ON  — the field must hold a valid duration, otherwise saving shows
 *                  an inline error (same rule as Quota Limit).
 *
 * Domain:
 *   • [AddTaskActivity.setupSleepSection]    — toggle + live preview TextWatcher
 *   • [AddTaskActivity.populateSleepSection] — restores switch and field from a saved task
 *   • [readSleepSeconds]                     — validation + parse used by SaveHandler
 */
internal fun AddTaskActivity.setupSleepSection() {
    // Toggle visibility of the detail field
    switchSleepEnabled.setOnCheckedChangeListener { _, checked ->
        layoutSleepFields.visibility = if (checked) View.VISIBLE else View.GONE
        tvSleepError.visibility = View.GONE
    }

    etSleep.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val secs = parseQuotaInput(s?.toString() ?: "")
            tvSleepPreview.text = if (secs > 0) formatQuotaDuration(secs) else ""
            tvSleepError.visibility = View.GONE
        }
    })
}

/** Restores the switch and field from [task]. A task with no sleep time leaves the card collapsed. */
internal fun AddTaskActivity.populateSleepSection(task: Task) {
    if (task.sleepDurationSeconds <= 0L) return
    switchSleepEnabled.isChecked = true
    layoutSleepFields.visibility = View.VISIBLE
    etSleep.setText(formatQuotaDuration(task.sleepDurationSeconds))
}

/**
 * Validates and parses the Sleep card.
 *
 * @return the duration in seconds (0 = switch off / unset), or null when the
 *         switch is on but the field is empty or not a valid duration — in which
 *         case the inline error is shown and the caller must abort the save.
 */
internal fun AddTaskActivity.readSleepSeconds(): Long? {
    if (!switchSleepEnabled.isChecked) {
        tvSleepError.visibility = View.GONE
        return 0L
    }
    val secs = parseQuotaInput(etSleep.text?.toString().orEmpty())
    if (secs <= 0L) {
        tvSleepError.text = "Enter a sleep time — try 30m, 2h, 1d4h (1s–365d)"
        tvSleepError.visibility = View.VISIBLE
        layoutSleepFields.visibility = View.VISIBLE
        etSleep.requestFocus()
        return null
    }
    tvSleepError.visibility = View.GONE
    return secs
}
