package com.eevdf.capabilities.designsystem.entities

/**
 * EXPERIMENTAL — describes one instance of the proposed unified skeleton
 * card: 4 fixed, ordered slots (title, subtitle, metric, input), where a
 * card only shows the slots it needs but never reorders them.
 *
 * Slot 4 (input) splits into two row KINDS, not just widget types:
 *   - [fullInput] — a slider or a native dropdown box. Owns the whole row;
 *     at most one, since only one thing can own a full-width row.
 *   - [smallInputs] — compact tappable controls: a real native Material
 *     switch, or an icon-only button. Several can sit side by side in
 *     their own, separate row. [SkeletonSmallInput.IconButton] is
 *     icon-only, no text label, same convention the real task card's
 *     action row already uses — but [SkeletonSmallInput.Toggle] renders
 *     as the actual native switch widget, not an icon standing in for one.
 * A card can have one, the other, both (stacked, full row first), or
 * neither.
 *
 * Demo-only right now — wired up on the Layout demo page's existing
 * "template" tab, alongside the 4 real templates, at the same live scale.
 */
data class SkeletonCardEntity(
    /** Slot 1 [F]. */
    val title: String,
    /** Slot 2 [O] — subtitle / category / group. */
    val subtitle: String? = null,
    /** Slot 3 [O] — a changeable/dynamic readout. */
    val metric: String? = null,
    /** Slot 4a [O] — the one full-width control, if this card has one. */
    val fullInput: SkeletonFullInput? = null,
    /** Slot 4b [O] — compact tappable controls, right-aligned, in sequence. */
    val smallInputs: List<SkeletonSmallInput> = emptyList(),
)

/** A full-width input — owns its entire row. At most one per card. */
sealed class SkeletonFullInput {
    data class Slider(
        val valueFrom: Float,
        val valueTo: Float,
        val stepSize: Float,
        val value: Float,
        val onValueChange: ((Float) -> Unit)? = null,
    ) : SkeletonFullInput()

    /** Renders as the complete native dropdown box, not a compact form. */
    data class Dropdown(
        val options: List<String>,
        val selected: String? = null,
        val onSelect: ((String) -> Unit)? = null,
    ) : SkeletonFullInput()
}

/** One compact tappable control. Several may appear in the same row. */
sealed class SkeletonSmallInput {
    /** Renders as the real native Material switch — not an icon. */
    data class Toggle(
        val checked: Boolean,
        val onChange: ((Boolean) -> Unit)? = null,
    ) : SkeletonSmallInput()

    /** A single icon-only button — hamburger, nav-arrow, preview, export, or any future icon. */
    data class IconButton(
        val icon: SkeletonIcon,
        val onClick: () -> Unit,
    ) : SkeletonSmallInput()
}

/**
 * The fixed, closed set of icons this experimental card can show — never a
 * raw drawable resource id crossing the entity boundary, same discipline
 * as every other entity in this module carrying no Android types.
 *
 * NAV_ARROW is for a card that navigates to a different page/Activity —
 * unused by any of the current demo cards (none of them change screens),
 * kept in reserve for the day a demo card mirrors a real page-jumping row.
 * HAMBURGER is for a card whose icon opens something IN PLACE (a dialog,
 * an expansion) without changing pages — see Exclude App.
 */
enum class SkeletonIcon { NAV_ARROW, HAMBURGER, PREVIEW, EXPORT }
