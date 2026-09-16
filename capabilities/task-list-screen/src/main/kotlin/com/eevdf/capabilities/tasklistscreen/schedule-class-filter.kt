package com.eevdf.capabilities.tasklistscreen

import com.eevdf.capabilities.taskstorage.Task

/**
 * Which scheduler-class tab the Schedule screen is currently narrowed to.
 *
 * This is a VIEW concept only — it never touches [Task.schedulerClass] itself
 * or how "what runs next" is decided. A task's class is its own permanent
 * property, never derived from or changed by what's nested inside/above it
 * (see current-task-owner.kt / task-repository.kt's selectNextCgroup for the
 * actual scheduling decision — this filter has no effect on it whatsoever).
 */
enum class ScheduleClassFilter(val label: String) {
    SCHEDULE("Schedule"),
    DEADLINE("Deadline"),
    REALTIME("Realtime"),
    FAIR("Fair");

    companion object {
        /** Strict urgency order, matching class precedence: DL > RT > FAIR. */
        val URGENCY_ORDER = listOf(DEADLINE, REALTIME, FAIR)
    }
}

/** A task's own scheduler class, as a [ScheduleClassFilter] value (never ALL). */
internal fun Task.ownScheduleClass(): ScheduleClassFilter = when (schedulerClass) {
    "dl_sched_class" -> ScheduleClassFilter.DEADLINE
    "rt_sched_class" -> ScheduleClassFilter.REALTIME
    else             -> ScheduleClassFilter.FAIR
}
