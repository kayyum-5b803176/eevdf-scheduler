package com.eevdf.feature.task.list

import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.eevdf.feature.R
import com.eevdf.feature.shared.prefs.AutoSwitchPrefs
import com.eevdf.feature.shared.prefs.DisplayPrefs
import com.eevdf.feature.shared.signals.BubbleEventBus
import com.eevdf.feature.shared.signals.CallEvents
import com.eevdf.feature.task.notice.NoticePhase
import com.eevdf.feature.task.timer.TimerCardAction
import com.eevdf.platform.notification.NotificationHelper

/**
 * Wires every LiveData observer.
 *
 * SPLIT DELIBERATELY. This was one 213-line function inside MainActivity, and
 * it was the single worst merge-conflict surface in the app: any feature that
 * observed anything edited it, so two people working in parallel collided here
 * constantly, and the conflicts were the nasty kind — both sides valid.
 *
 * Each concern already had its own function (observeCallEvents, observeSync,
 * etc.) before Phase 10; this delegate is that same split, moved out of
 * MainActivity entirely so the merge-conflict surface these functions cause
 * no longer lands on the file every other MainActivity change also touches.
 * No behavior changed — every line is the original, moved as-is.
 */
internal class ObserverDelegate(private val activity: MainActivity) {

    fun setupObservers() {
        observeCallEvents()
        observeTaskLists()
        observeCurrentTask()
        observeTimerCard()
        observeNoticePhase()
        observeStatsAndToasts()
        observeDisplayToggles()
        observeSync()
        observeActionButtons()
        observeDrillNavigation()
    }

    /** Breadcrumb bar visibility/text — refreshes whenever either tab's drill
     *  stack changes, or either tab's style setting flips (switching back to
     *  FLAT_OUTLINE clears that tab's stack, which fires through here too). */
    private fun observeDrillNavigation() {
        activity.viewModel.queueDrillState.observe(activity)    { if (activity.currentTab == 0) activity.updateBreadcrumb() }
        activity.viewModel.scheduleDrillState.observe(activity) { if (activity.currentTab == 1) activity.updateBreadcrumb() }
        activity.viewModel.queueListStyle.observe(activity)     { if (activity.currentTab == 0) activity.updateBreadcrumb() }
        activity.viewModel.scheduleListStyle.observe(activity)  { if (activity.currentTab == 1) activity.updateBreadcrumb() }
    }

    /** Auto-switch call detection. Keeps the ViewModel's in-memory call state in
     * sync when the Activity is alive; CallSwitchService owns the DB writes. */
    private fun observeCallEvents() {
        // ── Auto Switch — Call Detection ──────────────────────────────────────
        CallEvents.event.observe(activity) { type ->
            if (type == null) return@observe
            val slot = AutoSwitchPrefs.getCallSlot(activity) ?: return@observe
            when (type) {
                CallEvents.Type.CALL_STARTED -> {
                    // CallSwitchService has already written the DB switch and
                    // started the bubble. We call handleCallStarted here only
                    // to keep the ViewModel in-memory state (savedTaskBeforeCall,
                    // wasTimerRunning) in sync so CALL_ENDED can restore correctly
                    // if the Activity is alive for the whole call.
                    activity.viewModel.handleCallStarted(slot)
                }
                CallEvents.Type.CALL_ENDED -> activity.viewModel.handleCallEnded()
            }
            CallEvents.event.value = null   // consume
        }
    }

    /** The three tab lists: queue, schedule order and completed.
     *
     *  Queue/Schedule observe the *display* projection (queueDisplayList /
     *  scheduleDisplayList), not flatActiveTasks/flatScheduleOrder directly —
     *  those two remain the full tree for scheduling; the display lists are
     *  what actually renders and switch to a single drill level when that
     *  tab's style setting is DRILL_DOWN. See ListBuilderDelegate.setup(). */
    private fun observeTaskLists() {
        // Queue tab — flat group-aware list, or one drill-down level
        activity.viewModel.listBuilder.queueDisplayList.observe(activity) { items ->
            activity.activeAdapter.submitList(items)
            activity.activeAdapter.setRunningTask(activity.viewModel.currentTask.value?.id)
            activity.updateEmptyView()
            activity.updateScheduleRankBadge()
        }

        // Schedule tab — flat group-aware list, or one drill-down level
        activity.viewModel.listBuilder.scheduleDisplayList.observe(activity) { items ->
            activity.scheduleAdapter.submitList(items)
            activity.scheduleAdapter.setRunningTask(activity.viewModel.currentTask.value?.id)
            activity.updateScheduleRankBadge()
        }

        // Completed tab — flat (no group hierarchy for completed)
        activity.viewModel.completedTasks.observe(activity) { tasks ->
            activity.completedAdapter.submitList(tasks.map {
                com.eevdf.data.task.TaskDisplayItem(it, 0)
            })
            activity.updateEmptyView()
        }
    }

    /** Current task identity and the raw countdown text.
     *
     * Card VISIBILITY is deliberately NOT handled here — that belongs solely to
     * [observeTimerCard], which is the single source of truth. */
    private fun observeCurrentTask() {
        activity.viewModel.currentTask.observe(activity) { task ->
            if (task != null) {
                // Content only — card VISIBILITY is owned solely by the
                // timerCardAction observer below (single source of truth).
                activity.tvCurrentTaskName.text = task.name
                activity.tvTimerPriority.text = if (task.category == "None") "Priority ${task.priority}"
                                       else "Priority ${task.priority} · ${task.category}"
                activity.tvTimerDisplay.text = task.remainingDisplay
                activity.activeAdapter.setRunningTask(task.id)
                activity.scheduleAdapter.setRunningTask(task.id)
                if (activity.viewModel.autoScrollEnabled.value == true) activity.scrollToTask(task.id)
            } else {
                activity.activeAdapter.setRunningTask(null)
                activity.scheduleAdapter.setRunningTask(null)
                activity.tvTimerDisplay.text = "00:00"
            }
            activity.updateScheduleRankBadge()
        }

        activity.viewModel.timerSeconds.observe(activity) { seconds ->
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            activity.tvTimerDisplay.text = if (h > 0)
                String.format("%d:%02d:%02d", h, m, s)
            else
                String.format("%02d:%02d", m, s)
        }
    }

    /** The merged timer card: visibility, which child layout shows, tint, button
     * and alarm fields, all from one atomic [TimerCardAction].
     *
     * One observer drives the whole card, so it is structurally impossible to
     * show the countdown and the alarm at the same time. */
    private fun observeTimerCard() {
        // ── Merged timer card — SINGLE source of truth ────────────────────────
        //
        // One observer on timerCardAction drives the ENTIRE card: visibility,
        // which child layout is shown (countdown vs expired/alarm), the card
        // tint, the button, and the alarm fields. The ViewModel has already
        // combined currentTask + noticePhase + timerRunning + alarm state into
        // this one atomic value.
        //
        // Bug 3 fix: mutual exclusivity between the (former) two cards is no
        // longer enforced by hand-toggling cardTimer.visibility from a separate
        // alarm observer. There is one card and one observer; impossible to show
        // both the countdown and the alarm at once.
        activity.viewModel.timerCardAction.observe(activity) { action ->
            activity.timerCardDelegate.renderTimerCard(action)

            // Dot reflects timer state only when the card is manually hidden.
            // When card is visible the card itself shows the state — dot stays grey.
            activity.menuSyncDelegate.updateScheduleNextDot()

            // Keep the hover bubble dot in sync via the in-process volatile bus.
            val isRunning = action is TimerCardAction.Pause || action is TimerCardAction.Cancel
            val callSlot2 = AutoSwitchPrefs.getCallSlot(activity)
            val callTask2 = when (callSlot2) {
                "B"  -> activity.viewModel.interruptTaskB.value
                else -> activity.viewModel.interruptTask.value
            }
            BubbleEventBus.timerRunning    = isRunning
            BubbleEventBus.anyTimerRunning = isRunning
            BubbleEventBus.callTaskRunning = isRunning &&
                callSlot2 != null && callTask2 != null &&
                activity.viewModel.currentTask.value?.id == callTask2.id
        }
    }

    /** Phase-status bar and the adapters' segmented notice progress.
     *
     * Driven separately from the timer card because it needs NoticePhase's
     * remainingSecs detail, which TimerCardAction intentionally omits. */
    private fun observeNoticePhase() {
        // Phase-status bar — depends on NoticePhase subtype detail (remainingSecs)
        // that TimerCardAction intentionally omits, so driven separately here.
        activity.viewModel.noticePhase.observe(activity) { phase ->
            when (phase) {
                is NoticePhase.Delay -> {
                    activity.viewPhaseStatus.setBackgroundColor(android.graphics.Color.parseColor("#FFB300"))
                    activity.viewPhaseStatus.visibility = View.VISIBLE
                    val m = phase.remainingSecs / 60; val s = phase.remainingSecs % 60
                    activity.tvTimerDisplay.text = "%02d:%02d".format(m, s)
                }
                is NoticePhase.Wait -> {
                    activity.viewPhaseStatus.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                    activity.viewPhaseStatus.visibility = View.VISIBLE
                    val m = phase.remainingSecs / 60; val s = phase.remainingSecs % 60
                    activity.tvTimerDisplay.text = "%02d:%02d".format(m, s)
                }
                else -> activity.viewPhaseStatus.visibility = View.GONE
            }
            // Forward phase to adapters so the notice segmented bar shows live progress.
            // This fires on every Wait tick (postValue) and on Execute phase entry,
            // giving second-by-second fills.  Execute fill is driven by task.progressPercent
            // which updates via timerSeconds → currentTask → setRunningTask → notifyItemChanged.
            val noticeTaskId = activity.viewModel.currentTask.value?.id
            activity.activeAdapter.setNoticeState(noticeTaskId, phase)
            activity.scheduleAdapter.setNoticeState(noticeTaskId, phase)
        }
    }

    /** Header statistics, one-shot toasts, and the alarm overrun counter. */
    private fun observeStatsAndToasts() {
        activity.viewModel.stats.observe(activity) { stats ->
            activity.tvStats.text    = "Active: ${stats.activeTasks}  |  Done: ${stats.completedTasks}"
            activity.tvFairness.text = "Fairness: ${"%.0f".format(stats.fairnessScore * 100)}%  |  load: ${"%.2f".format(stats.systemLoad)}"
        }

        activity.viewModel.toastMessage.observe(activity) { msg ->
            if (!msg.isNullOrEmpty()) {
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                activity.viewModel.clearToast()
            }
        }

        // The elapsed overrun counter ticks once per second via its own LiveData.
        // It writes the alarm block's own tvAlarmElapsed view directly so the
        // counter animates smoothly without re-running renderTimerCard.
        activity.viewModel.alarmElapsedSeconds.observe(activity) { elapsed ->
            activity.tvAlarmElapsed.text = NotificationHelper.formatElapsed(elapsed)
        }
    }

    /** Menu checkmarks and FAB visibility for the display preference toggles. */
    private fun observeDisplayToggles() {
        // Sync groups menu checkmark
        activity.viewModel.groupsEnabled.observe(activity) { enabled ->
            activity.groupsMenuItem?.isChecked = enabled
        }
        activity.viewModel.globalRotateEnabled.observe(activity) { enabled ->
            activity.globalRotateMenuItem?.isChecked = enabled
        }
        activity.viewModel.allowEditEnabled.observe(activity) { enabled ->
            activity.allowEditMenuItem?.isChecked = enabled
            // FAB visibility and RecyclerView bottom padding are both managed by
            // applyFabVisibility so the compact-profile gate is applied consistently.
            val autoAdj = DisplayPrefs.isAutoAdjustEnabled(activity)
            val widthDp = activity.resources.configuration.screenWidthDp
            val heightDp = activity.resources.configuration.screenHeightDp
            val matched = DisplayPrefs.matchProfile(activity, widthDp, heightDp)
            val suppress = autoAdj && matched != null &&
                DisplayPrefs.isCompactProfile(matched)
            activity.displayScaleDelegate.applyFabVisibility(suppress)
        }
        activity.viewModel.autoScrollEnabled.observe(activity) { enabled ->
            activity.autoScrollMenuItem?.isChecked = enabled
        }
    }

    /** Sync status dot, and the restart-after-remote-import path. */
    private fun observeSync() {
        // ── Sync state → toolbar dot color ────────────────────────────────────
        activity.viewModel.syncState.observe(activity) { state -> activity.menuSyncDelegate.updateSyncIcon(state) }

        // ── Remote sync import → restart app so Room opens the new DB cleanly ─
        activity.viewModel.restartNeeded.observe(activity) {
            Toast.makeText(
                activity, "Sync received — reloading…", Toast.LENGTH_SHORT
            ).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)!!
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
                activity.startActivity(intent)
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 600)
        }
    }

    /** The Next/Auto and INT buttons.
     *
     * INT is a single observer on intButtonState rather than three on slot,
     * taskA and taskB: interleaved dispatches used to render colour and label
     * from mismatched values for one frame. */
    private fun observeActionButtons() {
        // ── Next / Auto button ────────────────────────────────────────────────
        activity.viewModel.nextButtonState.observe(activity) { state ->
            activity.btnScheduleNext.text = state.label
        }
        // Global Rotate is a fully independent preference — no longer locked
        // by anything Auto-related, since Auto is now a one-shot manual
        // action, not a persistent mode it used to conflict with.

        // ── INT button ────────────────────────────────────────────────────────
        //
        // Previously three separate observers (slot, taskA, taskB) each read
        // the other two LiveData via .value at dispatch time — if dispatches
        // interleaved, the color and label could be set from mismatched values
        // for one frame.  Single observer on intButtonState fixes that.
        activity.viewModel.intButtonState.observe(activity) { state ->
            activity.btnInt.text = state.label
            val color = if (state.textColorHex.isNotEmpty())
                android.graphics.Color.parseColor(state.textColorHex)
            else
                ContextCompat.getColor(activity, R.color.colorPrimary)
            activity.btnInt.setTextColor(color)
            activity.btnInt.jumpDrawablesToCurrentState()
        }
    }
}
