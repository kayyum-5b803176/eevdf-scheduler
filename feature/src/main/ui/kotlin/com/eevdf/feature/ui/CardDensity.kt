package com.eevdf.feature.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import com.google.android.material.card.MaterialCardView

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
 * [COMPACT_OVERRIDE]'s padding (8dp, scale 2) still matches the old static
 * `app_spacing_md` exactly. Its margin no longer matches the old
 * `app_spacing_sm` (4dp) — margin gained its own dedicated [MarginStep]
 * scale with a 10dp floor no other dimension shares, so even the smallest
 * margin scale now resolves to 10dp, never 4dp. Accepted deliberately: the
 * floor is a hard rule, not one this override should bypass, and nothing in
 * production ever exercised the old 4dp value anyway.
 */
public object CardDensity {

    public fun applyOuterGap(cardRoot: View, context: Context, isCompact: Boolean) {
        val tokens = tokensFor(context, isCompact)
        val gapPx = dp(context, tokens.outerMarginDp)
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

    private fun tokensFor(context: Context, isCompact: Boolean): DesignTokens =
        if (isCompact) COMPACT_OVERRIDE else LayoutTokenPrefs.current(context)

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private val COMPACT_OVERRIDE = DesignTokens(
        paddingScale = 2, marginScale = 2, textScale = 1, cornerRadiusScale = 1,
    )
}
