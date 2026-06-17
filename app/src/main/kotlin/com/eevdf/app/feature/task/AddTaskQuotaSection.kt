package com.eevdf.app.feature.task

import android.view.View
import com.eevdf.data.task.Task

/**
 * Quota limit section for [AddTaskActivity].
 *
 * Separated so changes to quota parsing format, validation rules, or the
 * preview display don't touch the core activity file.
 *
 * Domain:
 *   • [AddTaskActivity.setupQuotaSection]   — toggle + live preview TextWatchers
 *   • [parseQuotaInput]                     — human duration string → seconds (pure)
 *   • [formatQuotaDuration]                 — seconds → human duration string (pure)
 *   • [AddTaskActivity.populateQuotaSection] — restores fields from existing task
 *
 * Both [parseQuotaInput] and [formatQuotaDuration] are package-level so they can
 * be called directly from [AddTaskSaveHandler] without an activity receiver.
 */

internal fun AddTaskActivity.setupQuotaSection() {
    // Toggle visibility of the detail fields
    switchQuotaEnabled.setOnCheckedChangeListener { _, checked ->
        layoutQuotaFields.visibility = if (checked) View.VISIBLE else View.GONE
        tvQuotaError.visibility = View.GONE
    }

    // Live preview for quota field
    etQuota.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            val secs = parseQuotaInput(s?.toString() ?: "")
            tvQuotaPreview.text = if (secs > 0) formatQuotaDuration(secs) else ""
        }
    })

    // Live preview for period field
    etPeriod.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            val secs = parseQuotaInput(s?.toString() ?: "")
            tvPeriodPreview.text = if (secs > 0) formatQuotaDuration(secs) else ""
        }
    })

    // Initialise period preview with default value
    tvPeriodPreview.text = formatQuotaDuration(86400L)
}

/**
 * Parses a human-readable duration string into seconds.
 *
 * Accepted formats (case-insensitive, spaces optional):
 *   • "NdNhNmNs"  e.g.  "1d", "2h30m", "7d12h", "30m", "90s", "365d"
 *
 * Returns the clamped value in [1, 365 * 86400] (1 second to 365 days).
 * Returns 0 if the input is empty or cannot be parsed.
 */
internal fun parseQuotaInput(raw: String): Long {
    val s = raw.trim().lowercase()
    if (s.isEmpty()) return 0L
    val d   = Regex("""(\d+)\s*d""").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val h   = Regex("""(\d+)\s*h""").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val m   = Regex("""(\d+)\s*m(?!o)""").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val sec = Regex("""(\d+)\s*s""").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val total = d * 86_400L + h * 3_600L + m * 60L + sec
    // Bare number with no unit → treat as seconds
    val bare = if (d == 0L && h == 0L && m == 0L && sec == 0L) s.toLongOrNull() else null
    val result = bare ?: total
    if (result <= 0L) return 0L
    return result.coerceIn(1L, 365L * 86_400L)
}

internal fun formatQuotaDuration(totalSec: Long): String {
    if (totalSec <= 0L) return "0s"
    var rem = totalSec
    val d = rem / 86_400L; rem %= 86_400L
    val h = rem /  3_600L; rem %=  3_600L
    val m = rem /     60L
    val s = rem %     60L
    val parts = buildList {
        if (d > 0) add("${d}d")
        if (h > 0) add("${h}h")
        if (m > 0) add("${m}m")
        if (s > 0) add("${s}s")
    }
    return parts.joinToString(" ").ifEmpty { "0s" }
}

/** Restores quota switch and duration fields from [task]. */
internal fun AddTaskActivity.populateQuotaSection(task: Task) {
    if (!task.isQuotaEnabled) return
    switchQuotaEnabled.isChecked    = true
    layoutQuotaFields.visibility    = View.VISIBLE
    etQuota.setText(formatQuotaDuration(task.quotaSeconds))
    etPeriod.setText(formatQuotaDuration(task.quotaPeriodSeconds))
}
