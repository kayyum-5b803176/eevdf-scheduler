package com.eevdf.scheduler.model.timer

import androidx.annotation.ColorRes
import com.eevdf.scheduler.R

/**
 * Single source of truth for every button in the timer card.
 *
 * RULES:
 *  1. The ViewModel derives ONE value per button from ALL relevant LiveData using
 *     a MediatorLiveData. That derivation is the only place that contains the
 *     "what should the button do right now?" logic.
 *
 *  2. The UI observer sets label + color from this value — no other LiveData
 *     should touch the button's appearance or enabled state.
 *
 *  3. The click handler reads THIS value (already observed, already settled) to
 *     dispatch the action — it never reads .value from timerRunning, noticePhase,
 *     or any other LiveData directly. This eliminates the race window where two
 *     LiveData updates are in-flight simultaneously.
 *
 * Why a sealed class instead of flags or enums:
 *  - Each subtype carries exactly the data needed to render AND dispatch it.
 *  - Adding a new state (e.g. "Resetting") only requires adding a subtype here;
 *    the compiler then forces exhaustive when-branches everywhere it's used.
 *  - Impossible combinations (running=true AND phase=Expired) cannot be expressed.
 */
sealed class TimerCardAction {

    /** Human-readable button label. */
    abstract val label: String

    /** Button tint colour resource. */
    @get:ColorRes
    abstract val colorRes: Int

    /** Whether the button should be enabled (clickable). */
    open val enabled: Boolean = true

    // ── Concrete actions ──────────────────────────────────────────────────────

    /**
     * Timer is idle — tapping starts the countdown.
     * Shown when: task is selected, timer is not running, no notice phase active.
     */
    object Start : TimerCardAction() {
        override val label    = "Start"
        override val colorRes = R.color.timerGreen
    }

    /**
     * Timer is running (execute phase for both normal and Notice tasks) —
     * tapping pauses it and saves elapsed progress.
     */
    object Pause : TimerCardAction() {
        override val label    = "Pause"
        override val colorRes = R.color.timerYellow
    }

    /**
     * A Notice task's Delay or Wait phase is active — tapping aborts that phase
     * and returns the task to Idle (cancels the whole notice cycle, no slice consumed).
     */
    object Cancel : TimerCardAction() {
        override val label    = "Cancel"
        override val colorRes = R.color.timerYellow
    }

    /**
     * Timer card is in an unactionable state (alarm banner visible, task expired,
     * no task selected). Button is shown but disabled so the layout doesn't jump.
     */
    object Unavailable : TimerCardAction() {
        override val label    = "—"
        override val colorRes = R.color.timerGreen
        override val enabled  = false
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Single source of truth for the INT button's label and colour.
 *
 * Derived from three LiveData (activeInterruptSlot, interruptTask, interruptTaskB)
 * by a MediatorLiveData in TaskViewModel. The UI observer and click handler both
 * read this one object — no LiveData value is read directly at click time.
 */
data class IntButtonState(
    /** "A" or "B" — the currently active interrupt slot. */
    val slot: String,
    /** True if the active slot has a task assigned. */
    val hasTask: Boolean,
) {
    val label: String get() = "INT-$slot"

    /**
     * Colour for setTextColor() (not a colour resource — parsed from hex so the
     * colours match the existing hardcoded values in the old observers exactly).
     */
    val textColorHex: String get() = when {
        slot == "A" && hasTask -> "#F44336"   // red   — INT-A occupied
        slot == "B" && hasTask -> "#2196F3"   // blue  — INT-B occupied
        else                   -> ""          // empty → caller uses R.color.colorPrimary
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Single source of truth for the Next / Auto button's label.
 *
 * Kept minimal because the button has only two visual states. Exists as a named
 * type (rather than a raw Boolean) so future states (e.g. "Locked", "Cooldown")
 * can be added without touching the click handler.
 */
sealed class NextButtonState {
    abstract val label: String

    object Next : NextButtonState() { override val label = "Next" }
    object Auto : NextButtonState() { override val label = "Auto" }
}
