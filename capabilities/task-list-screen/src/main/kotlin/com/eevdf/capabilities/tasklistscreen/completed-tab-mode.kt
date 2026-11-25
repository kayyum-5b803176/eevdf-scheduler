package com.eevdf.capabilities.tasklistscreen

/**
 * What the third tab shows. Same mechanism as [ScheduleClassFilter] on the
 * Schedule tab: tapping the already-selected tab opens a pop-up, and the tab's
 * own label renames itself to the choice.
 *
 *   COMPLETED — finished tasks (Revert / Delete), the tab's original content.
 *   SLEEP     — tasks currently asleep, soonest wake first (view-only narrowing:
 *               picking it never changes any task's state).
 *   WAIT      — tasks waiting for a group-semaphore slot, oldest arrival first
 *               (also view-only; the same "hidden, behaves as if it did not exist"
 *               state as sleep, but it ends when a slot frees up, not at a time).
 *   WAKE      — tasks with an armed scheduled wake (task-wake), soonest due first.
 *               View-only like the others, but unlike sleep / wait an armed wake hides
 *               nothing: the task stays where it is in Queue / Schedule and is only
 *               LISTED here, with when its wake is due.
 */
enum class CompletedTabMode(val label: String) {
    COMPLETED("Completed"),
    SLEEP("Sleep"),
    WAIT("Wait"),
    WAKE("Wake"),
}
