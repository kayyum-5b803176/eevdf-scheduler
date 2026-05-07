package com.eevdf.scheduler.viewmodel

/**
 * Shared sort utility used by [TaskListBuilderDelegate] (Queue list) and
 * [TaskSchedulerDelegate] (sibling / global rotation).
 *
 * Keeping it here means neither delegate duplicates the regex or the extraction
 * logic, and a future change to the sort key only needs one edit.
 */
internal object TaskSortHelper {

    private val numberRegex = Regex("""(\d+(?:\.\d+)?)""")

    /**
     * Extracts the first number found in [name] for natural-number sorting.
     * Tasks with no number sort after numbered ones (returns [Double.MAX_VALUE]).
     *
     * Examples: "icon 1" → 1.0 | "task 1.1" → 1.1 | "clean 4.4" → 4.4 | "no-num" → MAX
     */
    fun extractNumber(name: String): Double =
        numberRegex.find(name)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: Double.MAX_VALUE
}
