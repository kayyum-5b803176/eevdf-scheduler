package com.eevdf.scheduler.viewmodel.task

import android.content.SharedPreferences
import androidx.lifecycle.MutableLiveData
import com.eevdf.scheduler.model.task.Task

/**
 * Manages per-tab group expand / collapse state for the Queue and Schedule tabs.
 *
 * Each tab has an independent [MutableMap] backed by [SharedPreferences] so
 * the state survives process death.  A [MutableLiveData] trigger is bumped
 * on every change so [TaskListBuilderDelegate] can rebuild the flat list.
 *
 * Adding a new tab with its own expand state:
 *  1. Add a new expandState map + trigger pair.
 *  2. Add toggle / deepToggle / toggleAll methods following the existing pattern.
 *  No other class needs to change.
 */
internal class TaskGroupExpandDelegate(
    private val prefs: SharedPreferences,
    private val vm: TaskViewModel
) {
    private fun getActiveTasks(): List<Task> = vm.activeTasks.value ?: emptyList()

    // ── Prefs key prefixes ────────────────────────────────────────────────────

    private val QUEUE_EXPAND_PREFIX    = "qexpand_"
    private val SCHEDULE_EXPAND_PREFIX = "sexpand_"

    // ── In-memory expand maps (loaded from prefs on init) ─────────────────────

    /** Queue tab: true = expanded (default), false = collapsed. */
    val queueExpandState: MutableMap<String, Boolean> = run {
        val map = mutableMapOf<String, Boolean>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(QUEUE_EXPAND_PREFIX) && value is Boolean)
                map[key.removePrefix(QUEUE_EXPAND_PREFIX)] = value
        }
        map
    }

    /** Schedule tab: true = expanded (default), false = collapsed. */
    val scheduleExpandState: MutableMap<String, Boolean> = run {
        val map = mutableMapOf<String, Boolean>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(SCHEDULE_EXPAND_PREFIX) && value is Boolean)
                map[key.removePrefix(SCHEDULE_EXPAND_PREFIX)] = value
        }
        map
    }

    /** Incrementing counter — list builder observes this to trigger a rebuild. */
    val queueExpandTrigger    = MutableLiveData(0)
    val scheduleExpandTrigger = MutableLiveData(0)

    // ── Expand state accessors ────────────────────────────────────────────────

    fun getQueueExpanded(taskId: String):    Boolean = queueExpandState[taskId]    ?: true
    fun getScheduleExpanded(taskId: String): Boolean = scheduleExpandState[taskId] ?: true

    // ── Single-group toggles ──────────────────────────────────────────────────

    fun toggleQueueGroupExpanded(group: Task) {
        val next = !(queueExpandState[group.id] ?: true)
        queueExpandState[group.id] = next
        prefs.edit().putBoolean(QUEUE_EXPAND_PREFIX + group.id, next).apply()
        queueExpandTrigger.value = (queueExpandTrigger.value ?: 0) + 1
    }

    fun toggleScheduleGroupExpanded(group: Task) {
        val next = !(scheduleExpandState[group.id] ?: true)
        scheduleExpandState[group.id] = next
        prefs.edit().putBoolean(SCHEDULE_EXPAND_PREFIX + group.id, next).apply()
        scheduleExpandTrigger.value = (scheduleExpandTrigger.value ?: 0) + 1
    }

    // ── Deep toggles (group + all descendants) ────────────────────────────────

    /**
     * Long-press on Queue tab: toggles [group] AND every descendant group
     * to the same new state in one atomic prefs write.
     */
    fun deepToggleQueueGroupExpanded(group: Task) {
        val next     = !(queueExpandState[group.id] ?: true)
        val allTasks = getActiveTasks()
        val targets  = listOf(group.id) + collectDescendantGroupIds(group.id, allTasks)
        val editor   = prefs.edit()
        for (id in targets) {
            queueExpandState[id] = next
            editor.putBoolean(QUEUE_EXPAND_PREFIX + id, next)
        }
        editor.apply()
        queueExpandTrigger.value = (queueExpandTrigger.value ?: 0) + 1
    }

    /** Long-press on Schedule tab: same rule as [deepToggleQueueGroupExpanded]. */
    fun deepToggleScheduleGroupExpanded(group: Task) {
        val next     = !(scheduleExpandState[group.id] ?: true)
        val allTasks = getActiveTasks()
        val targets  = listOf(group.id) + collectDescendantGroupIds(group.id, allTasks)
        val editor   = prefs.edit()
        for (id in targets) {
            scheduleExpandState[id] = next
            editor.putBoolean(SCHEDULE_EXPAND_PREFIX + id, next)
        }
        editor.apply()
        scheduleExpandTrigger.value = (scheduleExpandTrigger.value ?: 0) + 1
    }

    // ── Toggle-all (hold Next while no timer card) ────────────────────────────

    /**
     * Queue tab: if ANY root group is collapsed → open all; if ALL open → close all.
     * Applies to every root group and all their descendants.
     */
    fun toggleAllQueueGroupsExpanded() {
        val allTasks   = getActiveTasks()
        val rootGroups = allTasks.filter { it.isGroup && it.parentId == null }
        if (rootGroups.isEmpty()) return
        val anyCollapsed = rootGroups.any { !(queueExpandState[it.id] ?: true) }
        val next   = anyCollapsed
        val editor = prefs.edit()
        for (group in rootGroups) {
            val targets = listOf(group.id) + collectDescendantGroupIds(group.id, allTasks)
            for (id in targets) {
                queueExpandState[id] = next
                editor.putBoolean(QUEUE_EXPAND_PREFIX + id, next)
            }
        }
        editor.apply()
        queueExpandTrigger.value = (queueExpandTrigger.value ?: 0) + 1
    }

    /** Schedule tab: same open/close rule as [toggleAllQueueGroupsExpanded]. */
    fun toggleAllScheduleGroupsExpanded() {
        val allTasks   = getActiveTasks()
        val rootGroups = allTasks.filter { it.isGroup && it.parentId == null }
        if (rootGroups.isEmpty()) return
        val anyCollapsed = rootGroups.any { !(scheduleExpandState[it.id] ?: true) }
        val next   = anyCollapsed
        val editor = prefs.edit()
        for (group in rootGroups) {
            val targets = listOf(group.id) + collectDescendantGroupIds(group.id, allTasks)
            for (id in targets) {
                scheduleExpandState[id] = next
                editor.putBoolean(SCHEDULE_EXPAND_PREFIX + id, next)
            }
        }
        editor.apply()
        scheduleExpandTrigger.value = (scheduleExpandTrigger.value ?: 0) + 1
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Recursively collects IDs of every descendant group of [parentId].
     * Internal but exposed so [TaskListBuilderDelegate] can traverse the tree.
     */
    internal fun collectDescendantGroupIds(parentId: String, allTasks: List<Task>): List<String> {
        val result = mutableListOf<String>()
        val directChildren = allTasks.filter { it.parentId == parentId && it.isGroup }
        for (child in directChildren) {
            result += child.id
            result += collectDescendantGroupIds(child.id, allTasks)
        }
        return result
    }
}
