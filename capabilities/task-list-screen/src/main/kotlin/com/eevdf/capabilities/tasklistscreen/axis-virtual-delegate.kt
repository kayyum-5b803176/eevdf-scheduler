package com.eevdf.capabilities.tasklistscreen

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MediatorLiveData
import com.eevdf.capabilities.taskstorage.AxisVirtualEngine
import com.eevdf.capabilities.taskstorage.AxisVirtualList
import com.eevdf.capabilities.taskstorage.Task

/**
 * The task list the screens are BUILT FROM: the real rows, plus the untouched copies of the places the person has opened.
 *
 * Copies are copy-on-write (see [AxisVirtualEngine]): an untouched copy is not a row, so it is derived here, one open place
 * at a time, and handed to the ordinary list builders as ordinary tasks — the builders, Next, rotate and the rest need no
 * special case. A place with untouched entries is drawn as a group that is CLOSED until the person opens it; opening it derives
 * its entries. Memory and work therefore follow what is open on screen, never places × entries ([AxisVirtualList] also caps it).
 *
 * Bursts of changes (a dozen inserts, a batch of rules) are COALESCED into one rebuild: this list feeds every other list, and
 * rebuilding it per data change was what buried the UI thread.
 *
 * Touching a derived row (running it, completing it, sleeping it) writes it as a real row with the SAME id
 * ([com.eevdf.capabilities.taskstorage.TaskRepository.materialize]), so the row on screen simply becomes real.
 *
 * With no later-axis entries defined the list is the repository's list, immediately and untouched.
 */
internal class AxisVirtualDelegate(private val vm: TaskViewModel) {

    lateinit var listTasks: MediatorLiveData<List<Task>>
        private set

    /** The ids of the derived rows in the current list — lets the UI tell an untouched copy from a real one. */
    var virtualIds: Set<String> = emptySet()
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var pending = false

    fun setup() {
        val repo = vm.repository
        listTasks = MediatorLiveData<List<Task>>().apply {
            fun request() {
                // Nothing derived at all (the common case): hand the real list through right now.
                if (repo.laterAxisTasks.value.isNullOrEmpty()) {
                    virtualIds = emptySet()
                    value = vm.activeTasks.value ?: emptyList()
                    return
                }
                if (pending) return                       // a rebuild is already queued: it will see the latest data
                pending = true
                handler.postDelayed({ pending = false; value = build() }, COALESCE_MS)
            }
            addSource(vm.activeTasks)                       { request() }
            addSource(repo.laterAxisTasks)                  { request() }
            addSource(repo.allAxisNodes)                    { request() }
            addSource(repo.allAxisContainers)               { request() }
            addSource(repo.allAxisAcl)                      { request() }
            addSource(repo.allAxisDoors)                    { request() }
            addSource(repo.allAxisEntryRoutes)              { request() }
            addSource(vm.groupExpand.queueExpandTrigger)    { request() }
            addSource(vm.groupExpand.scheduleExpandTrigger) { request() }
        }
    }

    /** Called from [TaskViewModel.onCleared]. */
    fun stop() { handler.removeCallbacksAndMessages(null) }

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

        val queue    = vm.groupExpand.queueExpandState
        val schedule = vm.groupExpand.scheduleExpandState
        val result = AxisVirtualList.build(
            real   = real,
            engine = engine,
            // Only an EXPLICIT open derives anything — never a default, so existing places do not all open at once.
            isOpen = { id -> queue[id] == true || schedule[id] == true },
            // A place with untouched entries starts closed (the builders' own default is open).
            onClosedPlace = { id ->
                if (!queue.containsKey(id))    queue[id] = false
                if (!schedule.containsKey(id)) schedule[id] = false
            },
        )
        virtualIds = result.derivedIds
        return result.tasks
    }

    private companion object {
        /** Long enough to fold a burst of writes into one rebuild, short enough to feel immediate. */
        const val COALESCE_MS = 80L
    }
}
