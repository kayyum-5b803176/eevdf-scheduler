package com.eevdf.app.core.template

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.TextView
import com.eevdf.app.R
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
 */
class DropdownCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val titleView: TextView
    private val fieldView: AutoCompleteTextView
    private val helperButton: MaterialButton
    private val spacerView: View

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

    init {
        LayoutInflater.from(context).inflate(R.layout.view_dropdowncard_internal, this, true)
        titleView = findViewById(R.id.dropdownCardTitle)
        fieldView = findViewById(R.id.dropdownCardField)
        helperButton = findViewById(R.id.dropdownCardHelperButton)
        spacerView = findViewById(R.id.dropdownCardSpacer)

        fieldView.setOnItemClickListener { _, _, position, _ ->
            val chosen = options.getOrNull(position) ?: return@setOnItemClickListener
            selectedOption = chosen
            onOptionSelected?.invoke(chosen)
        }
        helperButton.setOnClickListener { onHelperAction?.invoke() }
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
            onHelperAction: (() -> Unit)? = null
        ): DropdownCardView = DropdownCardView(context).apply {
            this.title = title
            this.options = options
            this.selectedOption = selectedOption
            this.onOptionSelected = onOptionSelected
            this.helperActionText = helperActionText
            this.onHelperAction = onHelperAction
        }
    }
}
