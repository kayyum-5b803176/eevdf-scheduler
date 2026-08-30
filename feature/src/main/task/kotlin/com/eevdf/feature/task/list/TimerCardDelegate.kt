package com.eevdf.feature.task.list

import android.content.res.ColorStateList
import android.view.View
import androidx.core.content.ContextCompat
import com.eevdf.feature.R
import com.eevdf.feature.task.timer.TimerCardAction
import com.eevdf.platform.notification.NotificationHelper

/**
 * Renders the merged timer card from a single [TimerCardAction] and wires its
 * button clicks (Start/Pause, hold-to-pause-and-deselect, INT, Next/Auto).
 *
 * Extracted from MainActivity (Phase 10). No behavior changed — every line is
 * the original, moved as-is.
 */
internal class TimerCardDelegate(private val activity: MainActivity) {

    /**
     * Renders the timer card from a single [TimerCardAction].
     *
     * CLEAN/STABLE CONTRACT — this function never mutates text size, typeface, or
     * text color on any view. All styling lives in activity_main.xml, baked into
     * two independent blocks (layoutTimerContent and layoutAlarmContent). Here we
     * only ever:
     *   1. set the card's visibility,
     *   2. set the card's background color,
     *   3. choose which block is visible,
     *   4. write TEXT CONTENT into that block's own views.
     *
     * Because the countdown views and the alarm views are completely separate,
     * styling one state can never affect the other — there is no shared view whose
     * size/font/color could leak across states. The two blocks have matching
     * structure and heights, so switching them does not resize the card.
     *
     * Card visibility:
     *   Hidden  → GONE (no task selected)
     *   Expired → always VISIBLE (the alarm must be seen, even if manually hidden)
     *   others  → VISIBLE unless the user manually closed the card
     */
    fun renderTimerCard(action: TimerCardAction) {
        when (action) {
            is TimerCardAction.Hidden -> {
                activity.cardTimer.visibility = View.GONE
            }

            is TimerCardAction.Expired -> {
                activity.cardTimer.setCardBackgroundColor(ContextCompat.getColor(activity, R.color.expiredCardBackground))
                activity.cardTimer.visibility = View.VISIBLE
                activity.layoutTimerContent.visibility = View.GONE
                activity.layoutAlarmContent.visibility = View.VISIBLE

                // Text content only — styling is fixed in XML.
                activity.tvAlarmTaskName.text = action.taskName
                activity.tvAlarmElapsed.text  = NotificationHelper.formatElapsed(action.elapsedSeconds)
            }

            else -> {
                // Start / Pause / Cancel / Unavailable.
                activity.cardTimer.setCardBackgroundColor(ContextCompat.getColor(activity, R.color.timerCardBackground))
                activity.cardTimer.visibility = if (activity.isCardManuallyHidden) View.GONE else View.VISIBLE
                activity.layoutAlarmContent.visibility = View.GONE
                activity.layoutTimerContent.visibility = View.VISIBLE

                // Text content + enabled state only — styling is fixed in XML.
                activity.btnStartPause.text      = action.label
                activity.btnStartPause.isEnabled = action.enabled
                activity.btnStartPause.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(activity, action.colorRes))
                // Snap the new tint instantly. MaterialButton's background drawable
                // otherwise cross-fades between the old and new tint (light → dark),
                // producing a visible color-transition flash on Start↔Pause. Jumping
                // the drawable to its current state skips that animation so the color
                // changes immediately, with no fade.
                activity.btnStartPause.jumpDrawablesToCurrentState()
            }
        }
    }

    fun setupTimerCard() {
        // Remove the button's state-list animator so changing its tint never
        // triggers a state-driven elevation/color animation. Combined with
        // jumpDrawablesToCurrentState() in renderTimerCard(), this makes the
        // Start↔Pause color change instant — no light→dark cross-fade.
        activity.btnStartPause.stateListAnimator = null

        // ── Start / Pause / Cancel ────────────────────────────────────────────
        // CRITICAL: dispatch from timerCardAction — the pre-derived, already-settled
        // value — NEVER from viewModel.timerRunning.value or viewModel.noticePhase.value
        // read at click time.  Reading two separate LiveData at tap time is the root
        // cause of "button stuck at Start" and "button dispatches the wrong action":
        // if the tap lands between two LiveData dispatches, one value is stale.
        activity.btnStartPause.setOnClickListener {
            activity.haptic(it)
            when (activity.viewModel.timerCardAction.value) {
                TimerCardAction.Start          -> activity.viewModel.startTimer()
                TimerCardAction.Pause          -> activity.viewModel.pauseTimer()
                TimerCardAction.Cancel         -> activity.viewModel.cancelNotice()
                TimerCardAction.Unavailable    -> Unit   // disabled — no-op
                TimerCardAction.Hidden         -> Unit   // card not shown — no-op
                is TimerCardAction.Expired     -> Unit   // alarm block uses btnStopAlarm
                null                           -> Unit   // not yet derived — no-op
            }
        }

        // Hold Start/Pause → pause the running task, then close the card by
        // DESELECTING the task (not the UI-only hide used by the key1 hold).
        // pauseAndDeselect() pauses (crediting the partial session, preserving
        // progress) then clears currentTask; the currentTask observer closes the
        // card and clears the running highlight. Reselecting the task resumes it.
        activity.btnStartPause.setOnLongClickListener {
            if (activity.viewModel.currentTask.value == null) return@setOnLongClickListener false
            activity.haptic(it)
            activity.viewModel.pauseAndDeselect()
            true
        }

        // ── INT button ────────────────────────────────────────────────────────
        activity.btnInt.setOnClickListener      { activity.haptic(it); activity.viewModel.jumpToInterrupt() }
        activity.btnInt.setOnLongClickListener  { activity.haptic(it); activity.viewModel.toggleInterruptSlot(); true }

        // ── Next / Auto button ────────────────────────────────────────────────
        activity.btnScheduleNext.setOnClickListener    { activity.haptic(it); activity.viewModel.nextSibling(onQueueTab = activity.currentTab == 0) }
        activity.btnScheduleNext.setOnLongClickListener { activity.haptic(it); activity.viewModel.toggleAutoMode(); true }
    }
}
