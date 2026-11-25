package com.eevdf.capabilities.tasklistscreen

import com.eevdf.capabilities.taskstorage.IdentityKind
import com.eevdf.capabilities.taskstorage.IdentityRules
import com.eevdf.capabilities.taskstorage.Task

/**
 * The Queue tab's identity filter: tapping the Queue tab again opens a menu of the
 * identity kinds (User, Group, Process, Thread, ...), and picking one narrows the tab
 * to tasks of exactly that kind — under Process only `process:…` tasks show.
 *
 * Same family as [ScheduleClassFilter] and [CompletedTabMode]: a VIEW concept only. It
 * never changes a task's identity, its scheduling, or what "Next" picks.
 *
 * STRICT: a row shows only if its OWN kind matches. Where a matching task lives does not
 * matter — one inside a collapsed group, or inside a group of another kind, is still
 * listed — and a group that does not match is not shown. The result is one flat list
 * (no nesting, no drill-down), whichever list style the Queue tab uses.
 *
 * There is no "All" entry in the menu: no filter (the tab simply reads "Queue") is the
 * starting state, and choosing the kind that is already selected clears the filter.
 * Like the Schedule filter it is not saved — it resets when the app restarts.
 */

/**
 * This task's own kind, exactly as its card shows it. A task whose identity has not been
 * assigned yet reads as "undefined" on its card, so it counts as Undefined here too.
 */
internal fun Task.ownIdentityKind(): IdentityKind =
    IdentityRules.parse(identity)?.kind ?: IdentityKind.UNDEFINED
