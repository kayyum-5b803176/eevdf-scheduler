package com.eevdf.app.core.template

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
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
 */
class ToggleCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val switchView: SwitchMaterial
    private val descriptionView: TextView
    private val spacerView: View

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

    init {
        LayoutInflater.from(context).inflate(R.layout.view_togglecard_internal, this, true)
        switchView = findViewById(R.id.toggleCardSwitch)
        descriptionView = findViewById(R.id.toggleCardDescription)
        spacerView = findViewById(R.id.toggleCardSpacer)
        switchView.setOnCheckedChangeListener { _, isChecked ->
            checkedBacking = isChecked
            onCheckedChange?.invoke(isChecked)
        }
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
            onCheckedChange: ((Boolean) -> Unit)? = null
        ): ToggleCardView = ToggleCardView(context).apply {
            this.title = title
            this.description = description
            this.checked = checked
            this.onCheckedChange = onCheckedChange
        }
    }
}
