package com.eevdf.feature.ui

/**
 * The one spacing scale every spacing-based token in this file derives its
 * value from — a 4dp grid, matching Material Design's own spacing baseline.
 * No dimension below defines its own independent raw dp number; each is
 * expressed as a position on this one scale (see [SpacingTier]), so a new
 * spacing need later picks an existing tier instead of inventing a sixth
 * arbitrary table — the exact problem this whole file exists to fix (see
 * class doc on [DesignTokens] for the three tables this replaced).
 */
public enum class SpacingStep(public val dp: Float) {
    XS(4f), SM(8f), MD(12f), LG(16f), XL(20f);

    public companion object {
        /** [scale] is 1..5 (this app's existing convention); index 0 = XS. */
        public fun at(scale: Int, tierOffset: Int = 0): SpacingStep {
            val index = (scale - 1 - tierOffset).coerceIn(0, entries.lastIndex)
            return entries[index]
        }
    }
}

/**
 * Same naming convention as [SpacingStep], for the one token dimension that
 * isn't a dp spacing value — a unitless multiplier on top of a view's own
 * base text size. Kept as its own small scale rather than folded into
 * [SpacingStep] because dp and "times the existing size" are different units;
 * a shared name (XS..XL) still makes both scales read the same way.
 */
public enum class TextScaleStep(public val multiplier: Float) {
    XS(0.85f), SM(0.925f), MD(1.0f), LG(1.075f), XL(1.15f);

    public companion object {
        public fun at(scale: Int): TextScaleStep = entries[(scale - 1).coerceIn(0, entries.lastIndex)]
    }
}

/**
 * How far below the "primary" tier (offset 0, whatever the current
 * [DesignTokens.paddingScale]/[DesignTokens.marginScale] resolves to) each
 * spacing role sits. Centralized here so the relationship between roles
 * ("padding is the most generous, row gaps are the tightest") is declared
 * once, not re-derived at each call site.
 */
private object SpacingTier {
    const val PADDING = 0          // the most generous spacing in a card
    const val MARGIN_TOP = 1
    const val CORNER_RADIUS = 1    // same visual "weight" as margin-top
    const val BUTTON_ROW_GAP = 1
    const val MARGIN_BOTTOM = 2
    const val ROW_GAP = 3          // the tightest — space within one card
}

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
 * NOT a preserve-every-pixel migration. Every value below is now a position
 * on [SpacingStep] (see [SpacingTier]), which shifts several numbers away
 * from their old hand-tuned values — accepted deliberately in exchange for a
 * system where a future spacing need reuses an existing tier instead of a
 * sixth arbitrary table. Concretely, at the default/max scale (5): content
 * padding moves from CardScale.kt's original 14dp to 20dp; margins and gaps
 * shift by roughly similar amounts. This is a real, visible change to task
 * row spacing once a screen is wired onto this model (not yet — see the
 * phase note in ARCHITECTURE.md for what's actually wired today).
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

    public val contentPaddingDp: Float get() = paddingDpFor(paddingScale)
    public val outerMarginTopDp: Float get() = marginTopDpFor(marginScale)
    public val outerMarginBottomDp: Float get() = marginBottomDpFor(marginScale)
    public val rowGapDp: Float get() = rowGapDpFor(marginScale)
    public val buttonRowGapDp: Float get() = buttonRowGapDpFor(marginScale)
    public val textSizeMultiplier: Float get() = textScaleMultiplierFor(textScale)
    public val cornerRadiusDp: Float get() = cornerRadiusDpFor(cornerRadiusScale)

    public companion object {

        /** Matches this model's own tiering, not any prior file's hardcoded
         * numbers — see the class doc's "NOT a preserve-every-pixel migration"
         * note. Adopting this model at these defaults is still a real,
         * visible spacing change once a screen is wired onto it.
         * cornerRadiusScale=4 is the one exception: checked against the
         * actual card views while wiring them (Phase 3) — their existing
         * `app_card_corner_radius` is 12dp, which is tier-1's value at
         * scale 4, not scale 3 as originally assumed in Phase 1 without
         * checking. Corrected here rather than left wrong. */
        public val DEFAULT: DesignTokens = DesignTokens(
            paddingScale = 5,
            marginScale = 5,
            textScale = 3,
            cornerRadiusScale = 4,
        )

        // Every resolver below is a lookup into SpacingStep/TextScaleStep at a
        // declared tier — none defines its own independent number. Exposed as
        // public functions, not just DesignTokens properties, because existing
        // call sites (DisplayScaleDelegate, CardScale.kt) work with a single
        // scale Int today, not a full 4-dimension token set.

        public fun paddingDpFor(scale: Int): Float =
            SpacingStep.at(scale, SpacingTier.PADDING).dp

        public fun marginTopDpFor(scale: Int): Float =
            SpacingStep.at(scale, SpacingTier.MARGIN_TOP).dp

        public fun marginBottomDpFor(scale: Int): Float =
            SpacingStep.at(scale, SpacingTier.MARGIN_BOTTOM).dp

        public fun rowGapDpFor(scale: Int): Float =
            SpacingStep.at(scale, SpacingTier.ROW_GAP).dp

        public fun buttonRowGapDpFor(scale: Int): Float =
            SpacingStep.at(scale, SpacingTier.BUTTON_ROW_GAP).dp

        public fun textScaleMultiplierFor(scale: Int): Float =
            TextScaleStep.at(scale).multiplier

        /** Scale 1 is a special case (square corners, 0dp) — every other level
         * is [SpacingTier.CORNER_RADIUS]'s tier on the same shared scale. */
        public fun cornerRadiusDpFor(scale: Int): Float =
            if (scale <= 1) 0f else SpacingStep.at(scale, SpacingTier.CORNER_RADIUS).dp
    }
}
