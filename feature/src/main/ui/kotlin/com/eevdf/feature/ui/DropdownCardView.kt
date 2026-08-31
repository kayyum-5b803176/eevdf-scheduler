package com.eevdf.feature.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.TextView
import com.eevdf.feature.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton

/**
 * DropdownCard — one of the closed set of fundamental templates. See
 * TEMPLATE_CATALOG.md.
 *
 * Piece 0 [F] — title, always present, required and non-blank.
 * Piece 1 [F] — the dropdown control itself, always present. [options]
 *   is required and must be non-empty — this template has no legal
 *   subset that omits its own dropdown, so the API does not accept an
 *   empty list rather than silently rendering an unusable control.
 * Piece 2 [O] — helper action button. Present iff [helperActionText]
 *   is non-null.
 *
 * [compact] is a display-density toggle, defaulting to false — see the
 * other template classes' doc for the same property. Currently used
 * only by the Render -> Layout demo screen.
 */
class DropdownCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val titleView: TextView
    private val fieldView: AutoCompleteTextView
    private val helperButton: MaterialButton
    private val spacerView: View
    private val cardRoot: MaterialCardView
    private val bodyView: View

    /** Piece 0 [F]. Required, non-blank. */
    var title: String = ""
        set(v) {
            require(v.isNotBlank()) { "DropdownCardView.title must not be blank — it is a fundamental piece." }
            field = v
            titleView.text = v
        }

    /** Piece 1 [F]. Required, non-empty — this template has no legal
     *  subset that omits its own dropdown control. */
    var options: List<String> = emptyList()
        set(v) {
            require(v.isNotEmpty()) { "DropdownCardView.options must not be empty — the dropdown is a fundamental piece with no legal omission." }
            field = v
            val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, v)
            fieldView.setAdapter(adapter)
            if (fieldView.text.toString() !in v) {
                fieldView.setText(v.first(), false)
            }
        }

    var selectedOption: String? = null
        set(v) {
            field = v
            if (v != null) fieldView.setText(v, false)
        }

    var onOptionSelected: ((String) -> Unit)? = null

    /** Piece 2 [O]. Null omits the piece and its spacer entirely. */
    var helperActionText: String? = null
        set(v) {
            field = v
            val show = v != null
            helperButton.text = v ?: ""
            helperButton.visibility = if (show) View.VISIBLE else View.GONE
            spacerView.visibility = if (show) View.VISIBLE else View.GONE
        }

    var onHelperAction: (() -> Unit)? = null

    /** Display-density toggle. See class doc. */
    var compact: Boolean = false
        set(value) {
            field = value
            applyDensity(value)
        }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_dropdowncard_internal, this, true)
        titleView = findViewById(R.id.dropdownCardTitle)
        fieldView = findViewById(R.id.dropdownCardField)
        helperButton = findViewById(R.id.dropdownCardHelperButton)
        spacerView = findViewById(R.id.dropdownCardSpacer)
        cardRoot = findViewById(R.id.dropdownCardRoot)
        bodyView = findViewById(R.id.dropdownCardBody)

        fieldView.setOnItemClickListener { _, _, position, _ ->
            val chosen = options.getOrNull(position) ?: return@setOnItemClickListener
            selectedOption = chosen
            onOptionSelected?.invoke(chosen)
        }
        helperButton.setOnClickListener { onHelperAction?.invoke() }
        // Must run unconditionally — see NavCardView.kt's init{} for why.
        applyDensity(compact)
    }

    /**
     * The [S] spacer exists only to align this card's total height to a
     * boundary rung — a cosmetic preference dropped entirely in compact
     * mode, where the card renders at its native content-driven height
     * instead. No scale-rung recomputation is attempted here; the
     * spacer is fully suppressed, not resized.
     */
    /**
     * Re-applies the current live token values without recreating the view.
     * See `ValueCardView.refreshDensity`'s doc comment for the full
     * reasoning — this is the same capability, added consistently across
     * all four card views since they all share `CardDensity`.
     */
    public fun refreshDensity() { applyDensity(compact) }

    private fun applyDensity(isCompact: Boolean) {
        CardDensity.applyOuterGap(cardRoot, context, isCompact)
        CardDensity.applyBodyPadding(bodyView, context, isCompact)
        CardDensity.applyCornerRadius(cardRoot, context, isCompact)

        if (isCompact) spacerView.visibility = View.GONE
        else spacerView.visibility = if (helperActionText != null) View.VISIBLE else View.GONE
    }

    companion object {
        /**
         * The recommended construction path. [title] and [options] are
         * true compile-enforced required parameters.
         */
        fun create(
            context: Context,
            title: String,
            options: List<String>,
            selectedOption: String? = null,
            onOptionSelected: ((String) -> Unit)? = null,
            helperActionText: String? = null,
            onHelperAction: (() -> Unit)? = null,
            compact: Boolean = false
        ): DropdownCardView = DropdownCardView(context).apply {
            this.title = title
            this.options = options
            this.selectedOption = selectedOption
            this.onOptionSelected = onOptionSelected
            this.helperActionText = helperActionText
            this.onHelperAction = onHelperAction
            this.compact = compact
        }
    }
}
