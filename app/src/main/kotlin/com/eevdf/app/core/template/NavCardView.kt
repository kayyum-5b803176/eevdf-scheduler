package com.eevdf.app.core.template

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import android.widget.TextView
import com.eevdf.app.R

/**
 * NavCard — one of the closed set of fundamental templates. See
 * TEMPLATE_CATALOG.md for the piece-sequence rules this class enforces
 * structurally rather than by convention.
 *
 * This is the ONLY sanctioned way a NavCard is ever built. There is no
 * XML shape a caller can copy or modify to produce one — the internal
 * layout ([R.layout.view_navcard_internal]) is inflated here and never
 * referenced anywhere else. The public API below is deliberately the
 * entire surface area: whatever cannot be set through it, cannot exist
 * in a NavCard instance, by construction, not by lint rule.
 *
 * Piece 0 [F] — title, always present, required and non-blank.
 * Piece 1 [O] — subtitle. Present iff [subtitle] is non-null; there is
 *               no way to make it a sibling of the trailing indicator,
 *               because that relationship is fixed in the internal
 *               layout and never exposed as a parameter.
 * [S] spacer  — present iff piece 1 is present. Not a property at all.
 *
 * [navigable] additionally selects between the two NavCard variants the
 * catalog already names: a normal navigating row (chevron, ripple,
 * [onNavigate] fires) or the "reserved / coming soon" placeholder row
 * (no chevron, no ripple, not clickable) — never a new template, per
 * TEMPLATE_CATALOG.md's "what is deliberately not a template" section.
 *
 * [compact] is a display-density toggle, defaulting to false. It never
 * changes the piece sequence, only the spacing scale-rung values applied
 * to margins/padding — and never [App.Row.Base]'s own minHeight, which
 * stays at the accessibility-recommended touch-target floor regardless.
 * Every existing call site is unaffected unless it explicitly opts in;
 * this is currently used only by the Render -> Layout demo screen.
 */
class NavCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val titleView: TextView
    private val subtitleView: TextView
    private val chevronView: TextView
    private val spacerView: View
    private val titleRow: View
    private val cardRoot: View

    /**
     * Display-density toggle. false (default) = every existing screen's
     * current spacing, unchanged. true = reduced margins/padding drawn
     * from the same fixed scale (app_spacing_sm/md), never a raw value —
     * currently opted into only by the Render -> Layout demo screen.
     */
    var compact: Boolean = false
        set(value) {
            field = value
            applyDensity(value)
        }

    /** Piece 0 [F]. Required, non-blank — enforced in the setter. */
    var title: String = ""
        set(value) {
            require(value.isNotBlank()) { "NavCardView.title must not be blank — it is a fundamental piece." }
            field = value
            titleView.text = value
        }

    /**
     * Piece 1 [O]. Null omits the piece and its spacer entirely — the
     * card collapses to piece 0's own height with no reserved gap.
     * Non-null shows the subtitle row AND the boundary spacer as one
     * unit; a caller cannot set one without the other.
     */
    var subtitle: String? = null
        set(value) {
            field = value
            val show = value != null
            subtitleView.text = value ?: ""
            subtitleView.visibility = if (show) View.VISIBLE else View.GONE
            spacerView.visibility = if (show) View.VISIBLE else View.GONE
        }

    /**
     * Selects between the navigating-row and reserved-placeholder
     * NavCard variants. Defaults to true (normal navigating row).
     */
    var navigable: Boolean = true
        set(value) {
            field = value
            applyClickableState(value)
        }

    /** Fires only when [navigable] is true. */
    var onNavigate: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_navcard_internal, this, true)
        titleView = findViewById(R.id.navCardTitle)
        subtitleView = findViewById(R.id.navCardSubtitle)
        chevronView = findViewById(R.id.navCardChevron)
        spacerView = findViewById(R.id.navCardSpacer)
        titleRow = findViewById(R.id.navCardTitleRow)
        cardRoot = findViewById(R.id.navCardRoot)
        applyClickableState(navigable)
    }

    /**
     * Reduces the card's outer margin and the title row's own padding to
     * the smallest values already on the spacing scale (app_spacing_sm,
     * app_spacing_md) — never a raw dp literal, never touching
     * App.Row.Base's minHeight (the accessibility touch-target floor).
     *
     * The [S] spacer's fixed size was computed to align this card's
     * NON-compact total height to a boundary rung — that alignment goes
     * stale the moment padding shrinks, since the spacer is a fixed
     * style-driven size, not a recomputed one. Rather than compute a
     * new (never-verified) rung for the compact total, the spacer is
     * suppressed entirely here: rung-alignment is a cosmetic preference
     * that yields under conflict, and "minimum space, no waste" is
     * exactly that conflict.
     */
    private fun applyDensity(isCompact: Boolean) {
        val gap = resources.getDimensionPixelSize(
            if (isCompact) R.dimen.app_spacing_sm else R.dimen.app_card_gap
        )
        (cardRoot.layoutParams as? MarginLayoutParams)?.let { lp ->
            lp.topMargin = gap
            lp.bottomMargin = gap
            lp.marginStart = gap
            lp.marginEnd = gap
            cardRoot.layoutParams = lp
        }

        val hPad = resources.getDimensionPixelSize(
            if (isCompact) R.dimen.app_spacing_md else R.dimen.app_row_padding_horizontal
        )
        val vPad = resources.getDimensionPixelSize(
            if (isCompact) R.dimen.app_spacing_sm else R.dimen.app_row_padding_vertical
        )
        titleRow.setPadding(hPad, vPad, hPad, vPad)

        if (isCompact) spacerView.visibility = View.GONE
        else spacerView.visibility = if (subtitle != null) View.VISIBLE else View.GONE
    }

    private fun applyClickableState(enabled: Boolean) {
        titleRow.isClickable = enabled
        titleRow.isFocusable = enabled
        // App.Row.Nav (the title row's style) already carries the ripple
        // background. The reserved-placeholder variant strips it rather
        // than reconstructing it — there is nothing to build back for
        // the enabled case, since inflation already set it correctly.
        if (!enabled) {
            titleRow.background = null
        }
        chevronView.visibility = if (enabled) View.VISIBLE else View.GONE
        titleRow.setOnClickListener(if (enabled) { _ -> onNavigate?.invoke() } else null)
    }

    companion object {
        /**
         * The recommended construction path. [title] is a true
         * compile-enforced required parameter here — unlike the bare
         * property setters above (kept only for XML-attribute inflation
         * compatibility), a call to [create] missing [title] does not
         * compile.
         */
        fun create(
            context: Context,
            title: String,
            subtitle: String? = null,
            navigable: Boolean = true,
            compact: Boolean = false,
            onNavigate: (() -> Unit)? = null
        ): NavCardView = NavCardView(context).apply {
            this.title = title
            this.subtitle = subtitle
            this.navigable = navigable
            this.compact = compact
            this.onNavigate = onNavigate
        }
    }
}
