package com.eevdf.feature.ui

/**
 * The one spacing scale every spacing-based token in this file derives its
 * value from — a 4dp grid, matching Material Design's own spacing baseline.
 * No dimension below defines its own independent raw dp number; each is
 * expressed as a position on this one scale (see [SpacingTier]), so a new
 * spacing need later picks an existing tier instead of inventing a sixth
 * arbitrary table — the exact problem this whole file exists to fix (see
 * class doc on [DesignTokens] for the three tables this replaced).
 *
 * 7 points, not 5 — widened so the default (position 4, the exact midpoint)
 * has real room to move in both directions, not just up. The first 5
 * positions are numerically identical to the original 5-point scale (4, 8,
 * 12, 16, 20); two more were appended on the same 4dp grid, not inserted
 * or renumbered, so nothing that already read a low position changed value.
 */
public enum class SpacingStep(public val dp: Float) {
    XS(4f), SM(8f), MD(12f), LG(16f), XL(20f), XXL(24f), XXXL(28f);

    public companion object {
        /** [scale] is 1..7; index 0 = XS. */
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
 * a shared name still makes both scales read the same way.
 *
 * 7 points, 0.05 per step, centered exactly on 1.0x (position 4, unscaled) —
 * same overall 0.85x-1.15x range as the original 5-point version, just
 * divided into finer steps.
 */
public enum class TextScaleStep(public val multiplier: Float) {
    XS(0.85f), SM(0.90f), MD(0.95f), MDD(1.00f), LG(1.05f), XL(1.10f), XXL(1.15f);

    public companion object {
        public fun at(scale: Int): TextScaleStep = entries[(scale - 1).coerceIn(0, entries.lastIndex)]
    }
}

/**
 * The outer-margin scale — a card/row's spacing from its neighbors and the
 * screen edge, applied symmetrically on all four sides (not top/bottom-
 * specific values; a card's outer margin is one number, in every direction).
 *
 * Its own dedicated scale, not a tier on [SpacingStep]: outer margin has a
 * hard floor (10dp) that no other spacing role shares — below that, a card
 * starts to feel like it's touching its neighbors or the screen edge
 * regardless of how small the user wants everything else. Still a clean 4dp
 * grid, same philosophy as [SpacingStep], just starting 6dp higher.
 *
 * 7 points: the first 5 (10/14/18/22/26) are numerically identical to the
 * original 5-point version; two more appended on the same grid (30, 34).
 */
public enum class MarginStep(public val dp: Float) {
    XS(10f), SM(14f), MD(18f), LG(22f), XL(26f), XXL(30f), XXXL(34f);

    public companion object {
        public fun at(scale: Int): MarginStep = entries[(scale - 1).coerceIn(0, entries.lastIndex)]
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
    const val CORNER_RADIUS = 1
    const val BUTTON_ROW_GAP = 1
    const val ROW_GAP = 3          // the tightest — space within one card
}

/**
 * The single source of truth for "given a 1-7 scale level, what's the actual
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
 * NOT a preserve-every-pixel migration. Every value below is a position on
 * [SpacingStep]/[MarginStep]/[TextScaleStep] (see [SpacingTier]), not its own
 * independent number — a future spacing need reuses an existing tier instead
 * of a new arbitrary table.
 *
 * Widened from 5 to 7 scale points, default moved from the top of the range
 * (5) to the exact midpoint (4): with only 5 points and a max default, the
 * slider could only ever go down from what shipped, never up. 7 points with
 * a midpoint default gives real room in both directions.
 *
 * Four independently-controllable scale dimensions, each 1 (smallest) to 7
 * (largest), default 4 (the midpoint):
 *
 *   - [paddingScale]      inner content padding within a card/row
 *   - [marginScale]       outer margin around a card, applied symmetrically
 *                         on all four sides — see [MarginStep] for why this
 *                         has a 10dp floor no other dimension shares
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
        require(paddingScale in 1..7)      { "paddingScale must be 1..7, was $paddingScale" }
        require(marginScale in 1..7)       { "marginScale must be 1..7, was $marginScale" }
        require(textScale in 1..7)         { "textScale must be 1..7, was $textScale" }
        require(cornerRadiusScale in 1..7) { "cornerRadiusScale must be 1..7, was $cornerRadiusScale" }
    }

    public val contentPaddingDp: Float get() = paddingDpFor(paddingScale)
    public val outerMarginDp: Float get() = marginDpFor(marginScale)
    public val rowGapDp: Float get() = rowGapDpFor(marginScale)
    public val buttonRowGapDp: Float get() = buttonRowGapDpFor(marginScale)
    public val textSizeMultiplier: Float get() = textScaleMultiplierFor(textScale)
    public val cornerRadiusDp: Float get() = cornerRadiusDpFor(cornerRadiusScale)

    public companion object {

        /**
         * How many points every scale in this file has — 7 today. Derived
         * from [SpacingStep]'s own entry count, not a separately-maintained
         * literal: any UI control building a slider for one of these scales
         * (see `LayoutDemoActivity`) should read this instead of hardcoding
         * a number, so the slider can never silently drift out of sync with
         * the model the way it already did once — the sliders shipped
         * hardcoded to a 5-point range for a full phase after the model
         * itself moved to 7 points, caught only when explicitly checked.
         * [SpacingStep]/[MarginStep]/[TextScaleStep] are kept at the same
         * entry count deliberately so this one constant is valid for all
         * four scale dimensions, not just three of them.
         */
        public val SCALE_POINTS: Int = SpacingStep.entries.size

        init {
            check(MarginStep.entries.size == SCALE_POINTS && TextScaleStep.entries.size == SCALE_POINTS) {
                "SpacingStep/MarginStep/TextScaleStep must all have the same " +
                    "entry count — SCALE_POINTS assumes it, and so does any UI " +
                    "control that reads it for a single shared slider range."
            }
        }

        /** Position 4 of 7 — the exact midpoint — for every dimension. */
        public val DEFAULT: DesignTokens = DesignTokens(
            paddingScale = 4,
            marginScale = 4,
            textScale = 4,
            cornerRadiusScale = 4,
        )

        // Every resolver below is a lookup into SpacingStep/MarginStep/
        // TextScaleStep at a declared tier — none defines its own independent
        // number. Exposed as public functions, not just DesignTokens
        // properties, because existing call sites (DisplayScaleDelegate,
        // CardScale.kt) work with a single scale Int today, not a full
        // 4-dimension token set.

        public fun paddingDpFor(scale: Int): Float =
            SpacingStep.at(scale, SpacingTier.PADDING).dp

        public fun marginDpFor(scale: Int): Float =
            MarginStep.at(scale).dp

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
