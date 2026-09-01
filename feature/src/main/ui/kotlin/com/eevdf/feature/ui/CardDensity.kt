package com.eevdf.feature.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import com.eevdf.feature.R
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt

/**
 * Shared density-application logic for every card view in this design
 * system (NavCardView, DropdownCardView, ToggleCardView, ValueCardView).
 *
 * Before this file, all four views carried an IDENTICAL copy of the outer-
 * margin ("gap") logic below, and three of the four (all but NavCardView,
 * which used its own asymmetric horizontal/vertical padding split — folded
 * into one symmetric value here, matching the other three, since a design
 * system unifying the app's look is the whole point of this work) carried
 * an identical copy of the content-padding logic too. This is the "systemic
 * mechanism, not per-view calls" piece of the UI-unification work — one
 * canonical implementation, reading the live, user-adjustable [DesignTokens]
 * instead of each view's own static, non-adjustable XML dimens
 * (`app_spacing_sm`, `app_card_gap`, `app_card_padding_lg`).
 *
 * Corner radius is a genuinely NEW capability added here — none of the four
 * views varied it before (always the static `app_card_corner_radius`,
 * 12dp, from the `App.Card` style). There is nothing to preserve for it, no
 * compatibility constraint the way there is for gap/padding below.
 *
 * [isCompact] is each view's own existing manual override (used only by the
 * Render -> Layout demo screen today, never by any real production screen).
 * [COMPACT_OVERRIDE]'s margin no longer matches the old static
 * `app_spacing_sm` (4dp) — margin gained its own dedicated [MarginStep]
 * scale with a 10dp floor no other dimension shares, so even the smallest
 * margin scale now resolves to 10dp, never 4dp. Its padding value is also
 * stale relative to the numbers this comment originally described, now
 * that padding has its own dedicated [PaddingStep] scale too. Neither is
 * corrected to match any specific old value: nothing in production ever
 * exercises [COMPACT_OVERRIDE] (confirmed — zero live callers), so precise
 * backward-compatible numeric matching isn't meaningful here, only that the
 * values stay valid.
 */
public object CardDensity {

    /**
     * Half the target gap, not the full value. Android does not collapse
     * adjacent margins — two cards each carrying the FULL [DesignTokens.outerMarginDp]
     * on their touching sides would produce a real, rendered gap of DOUBLE
     * the target (confirmed and documented as a real, un-fixed bug in
     * ModelDiagramView's own debug label before this change: "10dp + 10dp =
     * 20dp real"). Applying exactly half here — paired with the hosting
     * screen applying the same half as its own edge padding — makes every
     * touching pair of halves sum to exactly the target, uniformly: screen-
     * edge-to-first-card, card-to-card, and last-card-to-screen-edge all
     * become the identical, correct distance, in either orientation. See
     * ARCHITECTURE.md for the full reasoning — this was found and fixed as
     * a specific requirement, not a style preference.
     */
    public fun applyOuterGap(cardRoot: View, context: Context, isCompact: Boolean) {
        val tokens = tokensFor(context, isCompact)
        val gapPx = dp(context, tokens.outerMarginDp / 2f)
        (cardRoot.layoutParams as? MarginLayoutParams)?.let { lp ->
            lp.topMargin = gapPx
            lp.bottomMargin = gapPx
            lp.marginStart = gapPx
            lp.marginEnd = gapPx
            cardRoot.layoutParams = lp
        }
    }

    public fun applyBodyPadding(bodyView: View, context: Context, isCompact: Boolean) {
        val tokens = tokensFor(context, isCompact)
        val p = dp(context, tokens.contentPaddingDp)
        bodyView.setPadding(p, p, p, p)
    }

    /** [cardRoot] must be the actual MaterialCardView, not a plain View —
     * corner radius is a MaterialCardView-specific property, unlike margin/
     * padding which any View supports. */
    public fun applyCornerRadius(cardRoot: MaterialCardView, context: Context, isCompact: Boolean) {
        val tokens = tokensFor(context, isCompact)
        cardRoot.radius = dp(context, tokens.cornerRadiusDp).toFloat()
    }

    /**
     * Scales a row's minimum height (the accessibility touch-target floor,
     * `app_row_min_height` = 48dp) linearly with the live padding scale —
     * 0dp at the smallest padding setting, up to the full 48dp at the
     * largest. Previously a static, always-on `android:minHeight` in
     * `App.Row.Base` (used by `NavCardView`'s title row): padding=0 alone
     * couldn't achieve "text touches the card border" there, since the row
     * was still forced to be at least 48dp tall regardless of padding, with
     * its content centered inside that space. Made scale-driven instead, on
     * explicit instruction that the padding slider should be allowed to
     * shrink the touch target below the accessibility floor at its lowest
     * setting — a real, deliberate trade-off, not an oversight.
     *
     * The 48dp ceiling is read from the named dimen resource, not
     * hardcoded a second time here, so the two stay in sync by construction.
     */
    public fun applyMinHeight(view: View, context: Context, isCompact: Boolean) {
        val tokens = tokensFor(context, isCompact)
        val maxPx = context.resources.getDimensionPixelSize(R.dimen.app_row_min_height)
        val steps = (DesignTokens.SCALE_POINTS - 1).coerceAtLeast(1)
        view.minimumHeight = (maxPx.toFloat() * (tokens.paddingScale - 1) / steps).roundToInt()
    }

    private fun tokensFor(context: Context, isCompact: Boolean): DesignTokens =
        if (isCompact) COMPACT_OVERRIDE else LayoutTokenPrefs.current(context)

    private fun dp(context: Context, value: Float): Int =
        // roundToInt(), not (value * density + 0.5f).toInt() — that older
        // pattern only rounds positive values correctly; for negatives it
        // truncates toward zero instead of rounding, off by up to 1px. Now
        // that padding can go negative (see PaddingStep), this needed fixing
        // here since this function is the one every card's density value
        // actually passes through.
        (value * context.resources.displayMetrics.density).roundToInt()

    private val COMPACT_OVERRIDE = DesignTokens(
        paddingScale = 2, marginScale = 2, textScale = 1, cornerRadiusScale = 1,
        rowGapScale = 1, columnGapScale = 1,
    )
}
