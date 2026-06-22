package com.eevdf.app.feature.task.timer

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.eevdf.app.feature.task.timer.IntButtonState
import com.eevdf.data.task.Task
import kotlinx.coroutines.launch
import com.eevdf.app.feature.task.TaskViewModel

/**
 * Owns INT-A / INT-B interrupt slot assignment, navigation, and the derived
 * [intButtonState] mediator that the UI reads for button label + colour.
 *
 * Adding a new interrupt slot (e.g. INT-C):
 *  1. Add _interruptTaskC field + assign/clear/jump methods here.
 *  2. The per-tab return-to persistence is slot-agnostic (keyed by "tab|slot"),
 *     so no new saved-state field is needed — just pass "C" as the slot label.
 *  3. Update [intButtonState] to include the new slot.
 *  No timer logic, CRUD, or scheduler code is touched.
 */
internal class TaskInterruptDelegate(private val vm: TaskViewModel) {

    // ── Active slot ───────────────────────────────────────────────────────────

    private val _activeInterruptSlot = MutableLiveData<String>("A")
    val activeInterruptSlot: LiveData<String> = _activeInterruptSlot

    /** Toggles the active INT button slot between "A" and "B". */
    fun toggleInterruptSlot() {
        _activeInterruptSlot.value =
            if (_activeInterruptSlot.value == "A") "B" else "A"
    }

    // ── Interrupt task LiveData ───────────────────────────────────────────────

    private val _interruptTask  = MutableLiveData<Task?>(null)
    val interruptTask: LiveData<Task?> = _interruptTask    // kept for compat (= INT-A)

    private val _interruptTaskB = MutableLiveData<Task?>(null)
    val interruptTaskB: LiveData<Task?> = _interruptTaskB

    // ── Saved-card state (per TAB + per SLOT, DB-backed) ──────────────────────
    //
    // The "return-to" task for an interrupt jump is now tracked per (tab, slot):
    //   ("queue","A") ("queue","B") ("schedule","A") ("schedule","B")
    //
    // This in-memory map is a fast cache mirroring the interrupt_return DB table.
    // On a cold start it is empty; the first INT-back tap in a cell reads the DB
    // (restorePersistedReturn) so the saved task survives an app reboot.
    //
    // RULE: only NON-interrupt tasks are ever stored here. An INT task can never
    // become another slot's return-to target.
    private val savedReturnByCell = HashMap<String, Task?>()

    private fun cellKey(tab: Int, slot: String): String {
        val tabName = if (tab == 1) com.eevdf.data.task.InterruptReturnEntry.TAB_SCHEDULE
                      else          com.eevdf.data.task.InterruptReturnEntry.TAB_QUEUE
        return "$tabName|$slot"
    }

    private fun tabName(tab: Int): String =
        if (tab == 1) com.eevdf.data.task.InterruptReturnEntry.TAB_SCHEDULE
        else          com.eevdf.data.task.InterruptReturnEntry.TAB_QUEUE

    /**
     * Records [task] as the return-to for the current tab + [slot], in memory and
     * in the DB — but ONLY when [task] is a real, non-interrupt task. A null task
     * or an interrupt task clears the cell instead (rule 3 & 4).
     */
    private fun saveReturn(slot: String, task: Task?) {
        val tab = vm.activeTab
        val key = cellKey(tab, slot)
        if (task == null || task.isInterrupt) {
            savedReturnByCell[key] = null
            vm.viewModelScope.launch { vm.repository.clearInterruptReturn(tabName(tab), slot) }
            return
        }
        savedReturnByCell[key] = task
        val id = task.id
        vm.viewModelScope.launch { vm.repository.saveInterruptReturn(tabName(tab), slot, id) }
    }

    /**
     * Reads the return-to for the current tab + [slot]. Uses the in-memory cache
     * first; on a miss (e.g. fresh after reboot) it loads from the DB, resolves
     * the live Task row, caches it, and invokes [onReady]. If nothing is stored
     * or the task no longer exists / is completed / is itself an interrupt, the
     * cell is cleared and [onReady] receives null.
     */
    private fun withSavedReturn(slot: String, onReady: (Task?) -> Unit) {
        val tab = vm.activeTab
        val key = cellKey(tab, slot)
        if (savedReturnByCell.containsKey(key)) {
            onReady(savedReturnByCell[key])
            return
        }
        vm.viewModelScope.launch {
            val savedId = vm.repository.getInterruptReturnTaskId(tabName(tab), slot)
            val task = savedId?.let { vm.repository.getTaskById(it) }
            val usable = task?.takeIf { !it.isCompleted && !it.isInterrupt }
            if (usable == null && savedId != null) {
                vm.repository.clearInterruptReturn(tabName(tab), slot)
            }
            savedReturnByCell[key] = usable
            onReady(usable)
        }
    }

    // ── Derived button state ──────────────────────────────────────────────────

    /**
     * Combines activeInterruptSlot + interrupt tasks into a single settled value
     * that the UI observes.  No race window between two separate LiveData reads.
     */
    val intButtonState: MediatorLiveData<IntButtonState> =
        MediatorLiveData<IntButtonState>().apply {
            fun derive() {
                val slot    = _activeInterruptSlot.value ?: "A"
                val hasTask = if (slot == "A") _interruptTask.value != null
                              else              _interruptTaskB.value != null
                value = IntButtonState(slot = slot, hasTask = hasTask)
            }
            addSource(_activeInterruptSlot) { derive() }
            addSource(_interruptTask)       { derive() }
            addSource(_interruptTaskB)      { derive() }
        }

    // ── DB init helpers ───────────────────────────────────────────────────────

    /** Called from ViewModel init{} after DB lookup. */
    fun postInterruptTask(task: Task?)  { _interruptTask.postValue(task)  }
    fun postInterruptTaskB(task: Task?) { _interruptTaskB.postValue(task) }

    // ── Assignment ────────────────────────────────────────────────────────────

    fun assignInterruptTask(task: Task) = vm.viewModelScope.launch {
        vm.repository.setInterruptTask(task, slot = "A")
        _interruptTask.postValue(task.copy(isInterrupt = true, interruptSlot = "A"))
        vm.refreshSchedule()
    }

    fun assignInterruptTaskB(task: Task) = vm.viewModelScope.launch {
        vm.repository.setInterruptTask(task, slot = "B")
        _interruptTaskB.postValue(task.copy(isInterrupt = true, interruptSlot = "B"))
        vm.refreshSchedule()
    }

    fun clearInterruptTask() = vm.viewModelScope.launch {
        vm.repository.clearInterruptTask(slot = "A")
        _interruptTask.postValue(null)
        vm.refreshSchedule()
    }

    fun clearInterruptTaskB() = vm.viewModelScope.launch {
        vm.repository.clearInterruptTask(slot = "B")
        _interruptTaskB.postValue(null)
        vm.refreshSchedule()
    }

    // ── Jump logic ────────────────────────────────────────────────────────────

    /** Dispatches to the currently active slot. */
    fun jumpToInterrupt() {
        if (_activeInterruptSlot.value == "B") jumpToInterruptB() else jumpToInterruptA()
    }

    fun jumpToInterruptA() = jumpToInterruptSlot(_interruptTask,  "A")
    fun jumpToInterruptB() = jumpToInterruptSlot(_interruptTaskB, "B")

    /**
     * Core jump logic shared by both slots, now per-tab + per-slot.
     *
     * Behaviour:
     *  - Not on the interrupt task → save the current (non-interrupt) card as the
     *    return-to for THIS tab + slot, then jump to the interrupt task.
     *  - Already on the interrupt task → return to the saved card for THIS tab +
     *    slot (read from the in-memory cache, falling back to the DB so it works
     *    after a reboot). If none, close the card so the next tap jumps again.
     */
    private fun jumpToInterruptSlot(
        interruptLiveData: MutableLiveData<Task?>,
        slotLabel:         String
    ) {
        val interrupt = interruptLiveData.value
            ?: vm.activeTasks.value?.firstOrNull {
                it.isInterrupt && it.interruptSlot == slotLabel && !it.isCompleted
            }
            ?: run {
                vm._toastMessage.value = "No INT-$slotLabel task assigned"
                return
            }
        if (interruptLiveData.value == null) interruptLiveData.value = interrupt

        val current = vm._currentTask.value
        if (current?.id == interrupt.id) {
            // ── INT-back: return to the saved card for this tab + slot ──────────
            withSavedReturn(slotLabel) { back ->
                // Clear the cell either way — a return-to is consumed once used.
                saveReturn(slotLabel, null)
                if (back != null) {
                    vm.pauseTimer()
                    // Preserve the interrupt reference with its live remaining time.
                    vm._currentTask.value?.let { paused -> interruptLiveData.value = paused }
                    vm._currentTask.value  = back
                    vm._timerSeconds.value = back.remainingSeconds
                    vm._toastMessage.value = "Returned to \"${back.name}\""
                } else {
                    // No saved card → close the timer card so the next INT tap
                    // enters the JUMP branch instead of looping.
                    vm.pauseTimer()
                    interruptLiveData.value = interrupt
                    vm._currentTask.value   = null
                    vm._toastMessage.value  = "No saved task to return to"
                }
            }
        } else {
            // ── JUMP: save current card (if non-interrupt) then seat the INT task ─
            // pauseTimer FIRST so _timerSeconds is flushed into
            // _currentTask.remainingSeconds and persisted, making the saved
            // return-to reflect the live countdown at tap time.
            vm.pauseTimer()
            // Only a non-interrupt task is eligible as a return-to (rule 3 & 4);
            // saveReturn() enforces this and clears the cell otherwise.
            saveReturn(slotLabel, vm._currentTask.value ?: current)
            val freshInterrupt = vm.activeTasks.value
                ?.firstOrNull { it.isInterrupt && it.interruptSlot == slotLabel && !it.isCompleted }
                ?: interrupt
            interruptLiveData.value = freshInterrupt
            vm._currentTask.value   = freshInterrupt
            vm._timerSeconds.value  = freshInterrupt.remainingSeconds
            vm._toastMessage.value  = "Jumped to INT-$slotLabel: \"${freshInterrupt.name}\""
        }
    }
}
