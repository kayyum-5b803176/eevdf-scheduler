package com.eevdf.capabilities.tasklistscreen

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.eevdf.capabilities.taskstorage.AxisScheduleRows
import com.eevdf.capabilities.taskstorage.Task
import com.eevdf.capabilities.taskstorage.TaskDisplayItem

/**
 * Feeds the Schedule tab's RENDERED list: the ordinary first-axis rows with each leaf's
 * later-axis entries attached beneath it (see [AxisScheduleRows]).
 *
 * Deliberately a SEPARATE list from [ListBuilderDelegate.scheduleDisplayList]: that one is what
 * Next / Auto / rotate read to choose something to run, and a later-axis row is a shared
 * definition carrying one scope's runtime — never something to start from here. Only the
 * Schedule adapter reads this list.
 *
 * Expand state: a leaf's children start CLOSED (every leaf holds its own copy of the next axis,
 * so rows multiply); a group inside a scope starts OPEN like any group. Both survive restarts.
 */
internal class AxisScheduleDelegate(private val vm: TaskViewModel) {

    private val leafOpen  = HashMap<String, Boolean>()   // key = the scope a leaf's children live under
    private val groupOpen = HashMap<String, Boolean>()   // key = "<scope>|<groupId>"
    private val trigger   = MutableLiveData(0)

    lateinit var scheduleRenderList: MediatorLiveData<List<TaskDisplayItem>>
        private set

    init {
        vm.prefs.all.forEach { (k, v) ->
            if (v !is Boolean) return@forEach
            when {
                k.startsWith(LEAF_PREFIX)  -> leafOpen[k.removePrefix(LEAF_PREFIX)]   = v
                k.startsWith(GROUP_PREFIX) -> groupOpen[k.removePrefix(GROUP_PREFIX)] = v
            }
        }
    }

    fun isLeafExpanded(childScope: String): Boolean = leafOpen[childScope] ?: false
    fun isGroupExpanded(scope: String, groupId: String): Boolean = groupOpen["$scope|$groupId"] ?: true

    fun toggleLeaf(childScope: String) {
        val next = !isLeafExpanded(childScope)
        leafOpen[childScope] = next
        vm.prefs.edit().putBoolean(LEAF_PREFIX + childScope, next).apply()
        trigger.value = (trigger.value ?: 0) + 1
    }

    fun toggleGroup(scope: String, groupId: String) {
        val next = !isGroupExpanded(scope, groupId)
        groupOpen["$scope|$groupId"] = next
        vm.prefs.edit().putBoolean(GROUP_PREFIX + "$scope|$groupId", next).apply()
        trigger.value = (trigger.value ?: 0) + 1
    }

    /** Called once from [TaskViewModel.init], after [ListBuilderDelegate.setup]. */
    fun setup() {
        val base: LiveData<List<TaskDisplayItem>> = vm.listBuilder.scheduleDisplayList
        val later = vm.repository.laterAxisTasks
        val excl  = vm.repository.allAxisExclusions
        val inst  = vm.repository.allAxisInstances
        scheduleRenderList = MediatorLiveData<List<TaskDisplayItem>>().apply {
            fun rebuild() {
                val rows   = base.value ?: return
                val filter = vm.scheduleClassFilter.value ?: ScheduleClassFilter.SCHEDULE
                value = AxisScheduleRows.expand(
                    base            = rows,
                    later           = later.value.orEmpty(),
                    exclusions      = excl.value.orEmpty(),
                    instances       = inst.value.orEmpty(),
                    nowMs           = vm.clock.nowEpochMillis(),
                    leafExpanded    = { scope -> isLeafExpanded(scope) },
                    groupExpanded   = { scope, id -> isGroupExpanded(scope, id) },
                    // The class filter applies at every level, by each entry's OWN class.
                    keep            = if (filter == ScheduleClassFilter.SCHEDULE) null
                                      else ({ t: Task -> t.ownScheduleClass() == filter }),
                )
            }
            addSource(base)                      { rebuild() }
            addSource(later)                     { rebuild() }
            addSource(excl)                      { rebuild() }
            addSource(inst)                      { rebuild() }
            addSource(trigger)                   { rebuild() }
            addSource(vm.scheduleClassFilter)    { rebuild() }
        }
    }

    private companion object {
        const val LEAF_PREFIX  = "aleaf_"
        const val GROUP_PREFIX = "agrp_"
    }
}
