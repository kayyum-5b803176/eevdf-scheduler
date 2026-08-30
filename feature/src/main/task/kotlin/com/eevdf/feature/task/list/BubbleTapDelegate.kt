package com.eevdf.feature.task.list

import android.app.Application
import com.eevdf.feature.shared.prefs.AutoSwitchPrefs

/**
 * Handles a tap on the hover bubble during a phone call — pause/resume the
 * call task if it's already active, or interrupt whatever's running and
 * switch to it otherwise.
 *
 * Extracted from TaskViewModel (Phase 10) — third cluster, after
 * [TaskCrudDelegate] and [AlarmOverrunDelegate]. Same shape as both: calls
 * into not-yet-extracted timer lifecycle only through TaskViewModel's public
 * `pauseTimer()`/`startTimer()`, never timer internals directly. No behavior
 * changed — every line is the original, moved as-is.
 */
internal class BubbleTapDelegate(private val vm: TaskViewModel) {

    /**
     * Called from [com.eevdf.feature.shared.signals.BubbleEventBus.onBubbleTap]
     * when the user taps the hover bubble during a call.
     *
     * Behaviour depends on which task is currently active:
     *
     *   Case A — Call-assigned task IS the active timer (bubble dot = green):
     *     Toggle pause/resume of the call task, same as before.
     *
     *   Case B — Another task timer is running (bubble dot = blue):
     *     Interrupt the current task and switch to the call-assigned task.
     *     This mirrors what `CallSwitchDelegate.handleCallStarted` does
     *     automatically, but triggered manually by the user mid-call when they
     *     forgot to switch (e.g. they were already in a timer when the call came
     *     in and declined the auto-switch, or the feature fired before they
     *     picked up).
     *
     *   Case C — No timer is running (bubble dot = blue, timer paused):
     *     Start the call-assigned task timer.
     *
     * The [AutoSwitchPrefs.getCallTaskId] value is the single source of truth
     * for "which task is the call task".
     */
    fun handleBubbleTap() {
        val ctx        = vm.getApplication<Application>()
        val callTaskId = AutoSwitchPrefs.getCallTaskId(ctx)

        // No call task configured — fall back to simple toggle (safe default)
        if (callTaskId == null) {
            if (vm._timerRunning.value == true) vm.pauseTimer() else vm.startTimer()
            return
        }

        val current = vm._currentTask.value

        if (current?.id == callTaskId) {
            // Case A: call task is already active — toggle pause/resume
            if (vm._timerRunning.value == true) vm.pauseTimer() else vm.startTimer()
        } else {
            // Case B / C: switch to call task, interrupting whatever is running
            val callTask = vm.activeTasks.value
                ?.firstOrNull { it.id == callTaskId && !it.isCompleted }
                ?: run {
                    vm._toastMessage.value = "Call task not found — check Auto Switch settings"
                    return
                }

            // Pause the currently running task first (no-op if nothing is running)
            if (vm._timerRunning.value == true) vm.pauseTimer()

            // Switch to the call task and start it
            vm._currentTask.value  = callTask
            vm._timerSeconds.value = callTask.remainingSeconds
            vm.startTimer()
            vm._toastMessage.value = "Switched to \"${callTask.name}\""
        }
    }
}
