package com.eevdf.app.feature.task

import android.content.SharedPreferences
import com.eevdf.data.task.Task

/**
 * Stores and retrieves the last-run task per group for the Queue tab.
 *
 * One SharedPreferences entry per group:
 *   "queue_lastrun_{groupId}"  →  directChildId
 *
 * The stored value is always the DIRECT child of the group — a task id or a
 * sub-group id — never a deep leaf.  Following the chain to the final leaf is
 * deferred to [getLastRunLeaf] at read time, so each entry stays a single hop.
 *
 * Adding a second per-group memory (e.g. a Schedule-tab variant):
 *  1. Add a new prefix constant.
 *  2. Add a second update / getLastRunLeaf pair that uses it.
 *  No other class needs to change.
 */
internal class QueueLastRunDelegate(
    private val prefs: SharedPreferences,
) {
    private val PREFIX = "queue_lastrun_"

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Called when a task's timer starts.  Walks from [task].parentId up to the
     * root, writing one direct-child pointer per ancestor group in a single
     * [SharedPreferences.Editor.apply] call.
     *
     * Example — Task A3a (parentId = Group A3, grandparent = Group A):
     *   queue_lastrun_GroupA3 = Task A3a
     *   queue_lastrun_GroupA  = Group A3
     *
     * Root-level leaf tasks (parentId == null) produce no writes.
     */
    fun update(task: Task, allTasks: List<Task>) {
        if (task.parentId == null) return

        val editor   = prefs.edit()
        var childId  = task.id
        var parentId = task.parentId

        while (parentId != null) {
            editor.putString(PREFIX + parentId, childId)
            val parent = allTasks.find { it.id == parentId }
            childId  = parentId
            parentId = parent?.parentId
        }
        editor.apply()
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Follows the stored chain from [groupId] to the last-run leaf.
     *
     * At each hop:
     *   no preference stored     → null  (no history for this group)
     *   id absent from allTasks  → null  (task was deleted)
     *   task.isCompleted         → null  (task was completed)
     *   task is a leaf           → return task
     *   task is a sub-group      → recurse into that sub-group
     *
     * Any null at any hop terminates immediately and drops the group from
     * [TaskSchedulerDelegate.rotateGlobal]'s representatives list.
     * Stale preference entries are never cleaned up — they either get bypassed
     * because the chain detours around them, or they produce null here.
     */
    fun getLastRunLeaf(groupId: String, allTasks: List<Task>): Task? {
        val childId = prefs.getString(PREFIX + groupId, null) ?: return null
        val child   = allTasks.find { it.id == childId }     ?: return null
        if (child.isCompleted) return null
        if (!child.isGroup)    return child
        return getLastRunLeaf(child.id, allTasks)
    }
}
