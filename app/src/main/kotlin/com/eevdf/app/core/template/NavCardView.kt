package com.eevdf.app.core.template

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
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
        applyClickableState(navigable)
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
            onNavigate: (() -> Unit)? = null
        ): NavCardView = NavCardView(context).apply {
            this.title = title
            this.subtitle = subtitle
            this.navigable = navigable
            this.onNavigate = onNavigate
        }
    }
}
