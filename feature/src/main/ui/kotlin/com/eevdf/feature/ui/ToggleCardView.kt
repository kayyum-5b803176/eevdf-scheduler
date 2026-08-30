package com.eevdf.feature.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import android.widget.TextView
import com.eevdf.app.R
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * ToggleCard — one of the closed set of fundamental templates. See
 * TEMPLATE_CATALOG.md.
 *
 * Piece 0 [F] — title + switch, always present, required and non-blank.
 * Piece 1 [O] — description. Present iff [description] is non-null.
 * [S] spacer  — present iff piece 1 is present. Not a settable property.
 *
 * [compact] is a display-density toggle, defaulting to false — every
 * existing screen keeps its current spacing unless it opts in. Currently
 * used only by the Render -> Layout demo screen. Never touches this
 * card's minHeight.
 */
class ToggleCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val switchView: SwitchMaterial
    private val descriptionView: TextView
    private val spacerView: View
    private val cardRoot: View
    private val bodyView: View

    /** Piece 0 [F]. Required, non-blank. */
    var title: String = ""
        set(value) {
            require(value.isNotBlank()) { "ToggleCardView.title must not be blank — it is a fundamental piece." }
            field = value
            switchView.text = value
        }

    /** Piece 0 [F]'s state. Always present, never nullable — a toggle
     *  with no defined state is not a legal ToggleCard instance. */
    var checked: Boolean
        get() = checkedBacking
        set(value) {
            checkedBacking = value
            if (switchView.isChecked != value) switchView.isChecked = value
        }
    private var checkedBacking: Boolean = false

    /** Piece 1 [O]. Null omits the piece and its spacer entirely. */
    var description: String? = null
        set(value) {
            field = value
            val show = value != null
            descriptionView.text = value ?: ""
            descriptionView.visibility = if (show) View.VISIBLE else View.GONE
            spacerView.visibility = if (show) View.VISIBLE else View.GONE
        }

    var onCheckedChange: ((Boolean) -> Unit)? = null

    /** Display-density toggle. See class doc. */
    var compact: Boolean = false
        set(value) {
            field = value
            applyDensity(value)
        }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_togglecard_internal, this, true)
        switchView = findViewById(R.id.toggleCardSwitch)
        descriptionView = findViewById(R.id.toggleCardDescription)
        spacerView = findViewById(R.id.toggleCardSpacer)
        cardRoot = findViewById(R.id.toggleCardRoot)
        bodyView = findViewById(R.id.toggleCardBody)
        switchView.setOnCheckedChangeListener { _, isChecked ->
            checkedBacking = isChecked
            onCheckedChange?.invoke(isChecked)
        }
    }

    /**
     * The [S] spacer exists only to align this card's total height to a
     * boundary rung — a cosmetic preference dropped entirely in compact
     * mode, where the card renders at its native content-driven height
     * instead. No scale-rung recomputation is attempted here; the
     * spacer is fully suppressed, not resized.
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
        val bodyPad = resources.getDimensionPixelSize(
            if (isCompact) R.dimen.app_spacing_md else R.dimen.app_card_padding_lg
        )
        bodyView.setPadding(bodyPad, bodyPad, bodyPad, bodyPad)

        if (isCompact) spacerView.visibility = View.GONE
        else spacerView.visibility = if (description != null) View.VISIBLE else View.GONE
    }

    companion object {
        /**
         * The recommended construction path. [title] is a true
         * compile-enforced required parameter here — unlike the bare
         * property setters above (kept only for XML-attribute inflation
         * compatibility), a call to [create] missing [title] does not
         * compile, so a caller cannot end up with a blank-titled
         * instance by simply forgetting to set it.
         */
        fun create(
            context: Context,
            title: String,
            description: String? = null,
            checked: Boolean = false,
            compact: Boolean = false,
            onCheckedChange: ((Boolean) -> Unit)? = null
        ): ToggleCardView = ToggleCardView(context).apply {
            this.title = title
            this.description = description
            this.checked = checked
            this.compact = compact
            this.onCheckedChange = onCheckedChange
        }
    }
}
