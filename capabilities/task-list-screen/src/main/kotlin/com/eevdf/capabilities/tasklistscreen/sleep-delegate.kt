package com.eevdf.capabilities.tasklistscreen

import androidx.lifecycle.viewModelScope
import com.eevdf.capabilities.taskstorage.SleepRules
import com.eevdf.capabilities.taskstorage.Task
import com.eevdf.capabilities.taskstorage.TaskDisplayItem
import com.eevdf.capabilities.taskstorage.scheduling.EEVDFScheduler
import com.eevdf.capabilities.tasklistscreen.output.formatQuota
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Task sleep / wake: the moon button, the wake pass, and the Sleep-tab list.
 *
 * The rules themselves (what counts as asleep, how a wake is placed) live in
 * task-storage ([SleepRules], TaskRepository.sleepTask / wakeExpired) so every
 * consumer shares one definition; this delegate only drives them from the UI.
 *
 * There is deliberately no alarm: a sleeping task is hidden while its stored
 * wake time is ahead, and the wake pass runs from the foreground 1 s tick and
 * on resume. Nothing runs while the app is closed, so nothing can have moved —
 * placing the task when the app next looks gives the same result as placing it
 * at the exact expiry.
 */
internal class SleepDelegate(private val vm: TaskViewModel) {

    private val waking = AtomicBoolean(false)

    /**
     * Moon button. Awake → sleep for the task's saved sleep time; already asleep
     * (the Sleep tab's moon) → wake it now.
     */
    fun toggleSleep(task: Task) = vm.viewModelScope.launch {
        if (task.isAsleep) {
            vm.repository.wakeNow(task.id)
            afterChange()
            vm._toastMessage.postValue("\"${task.name}\" woke up")
            return@launch
        }
        val seconds = task.sleepDurationSeconds
        if (seconds <= 0L) {
            vm._toastMessage.postValue("Set a sleep time in Edit first")
            return@launch
        }

        // Sleeping a task (or a group) stops its timer first. A selected-but-paused
        // task is deselected too — a hidden task must not stay the current one.
        val awake   = vm.repository.getActiveTasksSync()
        val subtree = subtreeIds(task.id, awake)
        val current = vm.currentTask.value
        val running = vm.repository.getRunningTask()
        if ((current != null && current.id in subtree) || (running != null && running.id in subtree)) {
            vm.pauseAndDeselect()
            awaitPauseSettled(subtree)
        }

        vm.repository.sleepTask(task.id, vm.clock.nowEpochMillis() + seconds * 1_000L)
        afterChange()
        vm._toastMessage.postValue("\"${task.name}\" sleeping for ${formatQuota(seconds)}")
    }

    /**
     * Called from the 1 s foreground tick and on resume. Cheap: a pure in-memory
     * check of the observed task list; the database is only touched when
     * something is actually due. Guarded so passes never overlap.
     */
    fun tick() {
        val all = vm.activeTasksIncludingSleeping.value ?: return
        if (SleepRules.dueForWake(all, vm.clock.nowEpochMillis()).isEmpty()) return
        if (!waking.compareAndSet(false, true)) return
        vm.viewModelScope.launch {
            try {
                val woken = vm.repository.wakeExpired(vm.clock.nowEpochMillis())
                if (woken > 0) afterChange()
            } finally {
                waking.set(false)
            }
        }
    }

    /** Sleep-tab rows: every task whose own sleep is active (soonest wake first), groups followed by their contents. */
    fun buildSleepList(all: List<Task>): List<TaskDisplayItem> {
        val byParent = all.groupBy { it.parentId }
        fun counts(group: Task): Pair<Int, Int> {
            var groups = 0; var leaves = 0
            fun walk(id: String) {
                byParent[id].orEmpty().forEach { c ->
                    if (c.isGroup) { groups++; walk(c.id) } else leaves++
                }
            }
            walk(group.id)
            return groups to leaves
        }
        val out = mutableListOf<TaskDisplayItem>()
        fun emit(task: Task, depth: Int) {
            val (g, l) = if (task.isGroup) counts(task) else 0 to 0
            out += TaskDisplayItem(task, depth, childGroupCount = g, childTaskCount = l)
            if (task.isGroup) byParent[task.id].orEmpty().sortedBy { it.name.lowercase() }.forEach { emit(it, depth + 1) }
        }
        SleepRules.sleepRoots(all).forEach { emit(it, 0) }
        return out
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private fun subtreeIds(rootId: String, all: List<Task>): Set<String> {
        val byParent = all.groupBy { it.parentId }
        val ids = HashSet<String>().apply { add(rootId) }
        val queue = ArrayDeque(listOf(rootId))
        while (queue.isNotEmpty()) {
            byParent[queue.removeFirst()].orEmpty().forEach { if (ids.add(it.id)) queue.addLast(it.id) }
        }
        return ids
    }

    /**
     * pauseAndDeselect() persists the paused row and credits the session
     * asynchronously. Wait (bounded) until the database no longer reports a
     * running task inside the subtree before writing the sleep columns, so the
     * run's vruntime is in the lag we save and no late full-row write races it.
     */
    private suspend fun awaitPauseSettled(subtree: Set<String>) {
        repeat(30) {
            val r = vm.repository.getRunningTask()
            if (r == null || r.id !in subtree) return@repeat
            delay(50)
        }
        delay(200)
    }

    /** Pinned shares are a pool per level — re-derive them after a sleep/wake changes who is in it. */
    private suspend fun afterChange() {
        val tasks   = vm.repository.getActiveTasksSync()
        val changed = EEVDFScheduler.syncPinnedWeights(tasks)
        if (changed.isNotEmpty()) vm.repository.updateBatch(changed)
        vm.refreshSchedule()
        // (No sync call here: sleepTask / wakeExpired publish task.saved, which
        // multi-device-sync listens to — see TaskRepository.)
    }
}
