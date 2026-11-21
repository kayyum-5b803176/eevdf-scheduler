package com.eevdf.capabilities.tasklistscreen

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.eevdf.capabilities.taskstorage.AxisVirtualEngine
import com.eevdf.capabilities.taskstorage.Task

/**
 * The task list the screens are BUILT FROM: the real rows, plus the untouched copies of the places that are open.
 *
 * Copies are copy-on-write (see [AxisVirtualEngine]): an untouched copy is not a row, so it is derived here, one open place
 * at a time, and handed to the ordinary list builders as ordinary tasks — the builders, Next, rotate and the rest need no
 * special case. A place that has untouched entries is shown as a group that starts COLLAPSED; opening it derives its entries
 * (and, opening one of those that holds more, the next axis's, and so on). Memory and work therefore follow what is open on
 * screen, not places × entries, however many exist.
 *
 * Touching a derived row (running it, completing it, sleeping it) writes it as a real row with the SAME id
 * ([com.eevdf.capabilities.taskstorage.TaskRepository.materialize]), so the row on screen simply becomes real.
 *
 * With no later-axis entries defined the list is the repository's list, untouched.
 */
internal class AxisVirtualDelegate(private val vm: TaskViewModel) {

    lateinit var listTasks: MediatorLiveData<List<Task>>
        private set

    /** The ids of the derived rows in the current list — lets the UI tell an untouched copy from a real one. */
    var virtualIds: Set<String> = emptySet()
        private set

    fun setup() {
        val repo = vm.repository
        listTasks = MediatorLiveData<List<Task>>().apply {
            fun rebuild() { value = build() }
            addSource(vm.activeTasks)                     { rebuild() }
            addSource(repo.laterAxisTasks)                { rebuild() }
            addSource(repo.allAxisNodes)                  { rebuild() }
            addSource(repo.allAxisContainers)             { rebuild() }
            addSource(repo.allAxisAcl)                    { rebuild() }
            addSource(repo.allAxisDoors)                  { rebuild() }
            addSource(repo.allAxisEntryRoutes)            { rebuild() }
            addSource(vm.groupExpand.queueExpandTrigger)    { rebuild() }
            addSource(vm.groupExpand.scheduleExpandTrigger) { rebuild() }
        }
    }

    private fun build(): List<Task> {
        val real = vm.activeTasks.value ?: emptyList()
        val repo = vm.repository
        val defs = repo.laterAxisTasks.value.orEmpty()
        if (defs.isEmpty()) { virtualIds = emptySet(); return real }

        val byParent = real.groupBy { it.parentId }
        val engine = AxisVirtualEngine(
            laterDefinitions = defs,
            nodes            = repo.allAxisNodes.value.orEmpty(),
            containers       = repo.allAxisContainers.value.orEmpty().mapTo(HashSet()) { it.taskId },
            acl              = repo.allAxisAcl.value.orEmpty(),
            doors            = repo.allAxisDoors.value.orEmpty(),
            routes           = repo.allAxisEntryRoutes.value.orEmpty().associate { it.definitionId to it.route },
            globalHideNew    = repo.hideNewByDefault(),
            realChildrenOf   = { byParent[it].orEmpty() },
        )
        if (engine.isEmpty) { virtualIds = emptySet(); return real }

        val queue    = vm.groupExpand.queueExpandState
        val schedule = vm.groupExpand.scheduleExpandState

        /** A place with untouched entries looks like a group. Collapsed until opened, unless it already holds touched ones. */
        fun present(place: Task): Task {
            if (!engine.hasVirtual(place)) return place
            if (!place.isGroup) {                     // a plain leaf: closed by default (the builders' own default is open)
                if (!queue.containsKey(place.id))    queue[place.id] = false
                if (!schedule.containsKey(place.id)) schedule[place.id] = false
                return place.copy(isGroup = true)
            }
            return place
        }

        fun open(place: Task): Boolean {
            val fallback = place.isGroup && engine.isPlace(place)      // already a container: open like any group
            return (queue[place.id] ?: fallback) || (schedule[place.id] ?: fallback)
        }

        val out = ArrayList<Task>(real.size + 16)
        val derived = HashSet<String>()

        fun addOpen(place: Task, depth: Int) {
            if (depth > 4 || !engine.hasVirtual(place) || !open(place)) return
            for (kid in engine.childrenOf(place)) {
                derived += kid.id
                val shown = present(kid)
                out += shown
                addOpen(shown, depth + 1)
            }
        }

        for (t in real) out += present(t)
        for (t in real) addOpen(t, 0)
        virtualIds = derived
        return out
    }
}
