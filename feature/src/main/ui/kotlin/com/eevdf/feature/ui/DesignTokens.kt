package com.eevdf.feature.ui

/**
 * The single source of truth for "given a 1-5 scale level, what's the actual
 * dp/sp value." Before this file, the same kind of scale-to-dimension mapping
 * existed independently in three places, already diverged from each other:
 *
 *   - `DisplayScaleDelegate.applyCardScaleToView` — one padding table, applied
 *     to the fixed timer/alarm cards only.
 *   - `feature/task/adapter/CardScale.kt`'s `applyCardScale` — a DIFFERENT,
 *     6-dimension table (content padding, card top/bottom margin, row gap,
 *     button-row gap, progress-bar top margin), applied to task list rows only.
 *   - `feature/ui/res/values/dimens.xml` — static XML dimens consumed by the
 *     `NavCardView`/`DropdownCardView`/etc. card views, with no runtime
 *     scale-awareness at all.
 *
 * None of the three agreed with each other, and none was reusable by a screen
 * that didn't already have its own hand-written version. This file is that
 * reusable version. It does not yet replace any of the three above — wiring
 * existing call sites onto this is later phases of the layout-unification
 * work, done incrementally so each wiring change gets its own visual
 * check-in, not bundled into introducing the model itself.
 *
 * Four independently-controllable scale dimensions, each 1 (smallest) to 5
 * (largest/default), matching the existing `DisplayPrefs` card-height-scale
 * convention users are already familiar with from Display settings:
 *
 *   - [paddingScale]      inner content padding within a card/row
 *   - [marginScale]       outer spacing between cards/rows and their neighbors
 *   - [textScale]         a multiplier applied on top of each view's base
 *                         text size (never replaces it — a heading and a
 *                         caption stay visually distinct at every scale)
 *   - [cornerRadiusScale] card/button corner rounding
 */
public data class DesignTokens(
    public val paddingScale: Int,
    public val marginScale: Int,
    public val textScale: Int,
    public val cornerRadiusScale: Int,
) {
    init {
        require(paddingScale in 1..5)      { "paddingScale must be 1..5, was $paddingScale" }
        require(marginScale in 1..5)       { "marginScale must be 1..5, was $marginScale" }
        require(textScale in 1..5)         { "textScale must be 1..5, was $textScale" }
        require(cornerRadiusScale in 1..5) { "cornerRadiusScale must be 1..5, was $cornerRadiusScale" }
    }

    /** Inner content padding, in dp, for [paddingScale]. */
    public val contentPaddingDp: Float get() = paddingFor(paddingScale)

    /** Outer top margin between stacked elements, in dp, for [marginScale]. */
    public val outerMarginTopDp: Float get() = marginTopFor(marginScale)

    /** Outer bottom margin between stacked elements, in dp, for [marginScale]. */
    public val outerMarginBottomDp: Float get() = marginBottomFor(marginScale)

    /** Gap between rows within one card/section, in dp, for [marginScale]. */
    public val rowGapDp: Float get() = rowGapFor(marginScale)

    /**
     * Multiplier applied on top of a view's own base `textSize` — e.g.
     * `view.textSize = view.textSize * tokens.textSizeMultiplier`. Never an
     * absolute sp value: this preserves the relative size difference between
     * (say) a card title and a caption at every scale level.
     */
    public val textSizeMultiplier: Float get() = textScaleMultiplierFor(textScale)

    /** Corner radius, in dp, for [cornerRadiusScale]. 0 = square corners. */
    public val cornerRadiusDp: Float get() = cornerRadiusFor(cornerRadiusScale)

    public companion object {

        /** [DEFAULT] matches every screen's current hardcoded appearance —
         * adopting this model changes nothing visually until a preference is
         * changed away from these defaults. */
        public val DEFAULT: DesignTokens = DesignTokens(
            paddingScale = 5,
            marginScale = 5,
            textScale = 3,       // 3 is the neutral/unscaled midpoint — see textScaleMultiplierFor
            cornerRadiusScale = 3,
        )

        // ── Padding (dp) — carries forward DisplayScaleDelegate's existing table,
        // the one already live in production for the timer/alarm cards. ────────
        private fun paddingFor(scale: Int): Float = when (scale) {
            5 -> 16f; 4 -> 13f; 3 -> 10f; 2 -> 7f; else -> 5f
        }

        // ── Margins (dp) — carries forward CardScale.kt's existing card
        // top/bottom margin table, the one already live for task rows. ─────────
        private fun marginTopFor(scale: Int): Float = when (scale) {
            5 -> 8f; 4 -> 6f; 3 -> 5f; 2 -> 3f; else -> 2f
        }
        private fun marginBottomFor(scale: Int): Float = when (scale) {
            5 -> 4f; 4 -> 3f; 3 -> 2f; 2 -> 1f; else -> 1f
        }
        private fun rowGapFor(scale: Int): Float = when (scale) {
            5 -> 4f; 4 -> 3f; 3 -> 2f; 2 -> 1f; else -> 0f
        }

        // ── Text scale multiplier — NEW dimension, nothing to carry forward.
        // 3 (midpoint) = 1.0x = unscaled, matching every screen's current text
        // size exactly. 1 = 0.85x, 5 = 1.15x — deliberately narrow range: this
        // controls the WHOLE app's text at once, so a wide range risks making
        // some screen's longest label overflow its container. ──────────────────
        private fun textScaleMultiplierFor(scale: Int): Float = when (scale) {
            5 -> 1.15f; 4 -> 1.075f; 3 -> 1.0f; 2 -> 0.925f; else -> 0.85f
        }

        // ── Corner radius (dp) — NEW dimension. 3 (midpoint) matches Material's
        // default card corner radius already baked into feature/ui's static
        // theme resources, so adopting this at the default level changes
        // nothing. ───────────────────────────────────────────────────────────
        private fun cornerRadiusFor(scale: Int): Float = when (scale) {
            5 -> 16f; 4 -> 12f; 3 -> 8f; 2 -> 4f; else -> 0f
        }
    }
}
