package com.eevdf.capabilities.tasklistscreen

/**
 * What the third tab shows. Same mechanism as [ScheduleClassFilter] on the
 * Schedule tab: tapping the already-selected tab opens a pop-up, and the tab's
 * own label renames itself to the choice.
 *
 *   COMPLETED — finished tasks (Revert / Delete), the tab's original content.
 *   SLEEP     — tasks currently asleep, soonest wake first (view-only narrowing:
 *               picking it never changes any task's state).
 */
enum class CompletedTabMode(val label: String) {
    COMPLETED("Completed"),
    SLEEP("Sleep"),
}
