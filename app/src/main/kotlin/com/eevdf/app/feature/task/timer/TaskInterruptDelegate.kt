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
 *  1. Add _interruptTaskC + savedTaskBeforeInterruptC fields here.
 *  2. Add assignInterruptTaskC / clearInterruptTaskC / jumpToInterruptC methods.
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

    // ── Saved-card state (one per slot) ──────────────────────────────────────

    private var savedTaskBeforeInterrupt:  Task? = null
    private var savedTaskBeforeInterruptB: Task? = null

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

    fun jumpToInterruptA() = jumpToInterruptSlot(
        interruptLiveData = _interruptTask,
        savedSlotField    = { savedTaskBeforeInterrupt },
        setSavedSlot      = { savedTaskBeforeInterrupt = it },
        slotLabel         = "A"
    )

    fun jumpToInterruptB() = jumpToInterruptSlot(
        interruptLiveData = _interruptTaskB,
        savedSlotField    = { savedTaskBeforeInterruptB },
        setSavedSlot      = { savedTaskBeforeInterruptB = it },
        slotLabel         = "B"
    )

    /**
     * Core jump logic shared by both slots.
     *
     * Behaviour:
     *  - Not on the interrupt task → save current card, jump to interrupt.
     *  - Already on the interrupt task → jump back to the saved card.
     *    If no saved card existed (jumped from empty state), close the card
     *    and reset so the next tap enters the JUMP branch again (bug-fix path).
     */
    private fun jumpToInterruptSlot(
        interruptLiveData: MutableLiveData<Task?>,
        savedSlotField:    () -> Task?,
        setSavedSlot:      (Task?) -> Unit,
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
            val back = savedSlotField()
            setSavedSlot(null)
            if (back != null) {
                vm.pauseTimer()
                vm._currentTask.value?.let { paused -> interruptLiveData.value = paused }
                vm._currentTask.value  = back
                vm._timerSeconds.value = back.remainingSeconds
                vm._toastMessage.value = "Returned to \"${back.name}\""
            } else {
                // BUG FIX: No saved card → close the timer card so the next INT tap
                // enters the JUMP branch instead of getting stuck in an infinite loop.
                vm.pauseTimer()
                interruptLiveData.value = interrupt   // preserve interrupt reference
                vm._currentTask.value   = null        // close card, exits stuck loop
                vm._toastMessage.value  = "No saved task to return to"
            }
        } else {
            // pauseTimer FIRST — this flushes _timerSeconds into _currentTask.remainingSeconds
            // and persists it to the repository.  Only then capture the saved slot so
            // "back.remainingSeconds" on the return trip reflects the live countdown at
            // the moment the user tapped INT, not the stale value from the last DB load.
            vm.pauseTimer()
            setSavedSlot(vm._currentTask.value ?: current)
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
