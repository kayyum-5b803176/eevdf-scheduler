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
 * STRICT, with the way up kept: a task is a MATCH only if its OWN kind is the picked one —
 * nothing else qualifies, and a non-matching sibling or child is never listed. Where a
 * match lives does not matter, but so it is never orphaned the groups ABOVE it are shown
 * too, all the way up to the root group (rows flagged
 * [com.eevdf.capabilities.taskstorage.TaskDisplayItem.isFilterContextOnly]) — the same
 * way the Schedule tab's class filter keeps the path to the root. EVERY match is always
 * shown: the groups above it are open here whatever their collapsed state in the normal
 * Queue, so nothing is hidden under a collapsed group. It is a numbered outline,
 * whichever list style the Queue tab uses: no drill-down and no collapsing while a kind
 * is picked.
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
