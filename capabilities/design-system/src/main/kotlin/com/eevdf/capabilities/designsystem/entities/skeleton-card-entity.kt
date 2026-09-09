package com.eevdf.capabilities.designsystem.entities

/**
 * EXPERIMENTAL — describes one instance of the proposed unified skeleton
 * card: 5 fixed, ordered slots (title, subtitle, action, metric, buttons),
 * where a card only shows the slots it needs but never reorders them.
 *
 * Demo-only right now — wired up on the Layout demo page's existing
 * "template" tab, alongside the 4 real templates, at the same live scale.
 */
data class SkeletonCardEntity(
    /** Slot 1 [F]. */
    val title: String,
    /** Slot 2 [O] — subtitle / category / group. */
    val subtitle: String? = null,
    /** Slot 3 [O] — at most one action widget. */
    val action: SkeletonAction? = null,
    /** Slot 4 [O] — a changeable/dynamic readout, separate from the action itself. */
    val metric: String? = null,
    /** Slot 5 [O] — zero or more buttons, laid out in one row. */
    val buttons: List<SkeletonButton> = emptyList(),
    /**
     * Whole-card tap target — separate from any Slot 3 action, since a
     * card can be BOTH navigable (tap anywhere) and hold a control that
     * needs its own touch handling (a switch, a slider). Null means the
     * card itself isn't clickable; child widgets (switch, slider, buttons)
     * still handle their own touches regardless.
     */
    val onClick: (() -> Unit)? = null,
)

data class SkeletonButton(val text: String, val onClick: () -> Unit)

/** Slot 3's contents — exactly one of these, or none. */
sealed class SkeletonAction {
    data class Switch(
        val checked: Boolean,
        val onChange: ((Boolean) -> Unit)? = null,
    ) : SkeletonAction()

    data class Slider(
        val valueFrom: Float,
        val valueTo: Float,
        val stepSize: Float,
        val value: Float,
        val onValueChange: ((Float) -> Unit)? = null,
    ) : SkeletonAction()

    data class Dropdown(
        val options: List<String>,
        val selected: String? = null,
        val onSelect: ((String) -> Unit)? = null,
    ) : SkeletonAction()

    object Chevron : SkeletonAction()

    /** One bar per entry, each 0f..1f. Task-card-style stacked progress. */
    data class ProgressBars(val fractions: List<Float>) : SkeletonAction()
}
