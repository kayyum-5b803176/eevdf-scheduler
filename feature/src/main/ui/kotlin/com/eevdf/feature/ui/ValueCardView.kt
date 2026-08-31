package com.eevdf.feature.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.eevdf.feature.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider

/**
 * ValueCard — one of the closed set of fundamental templates. See
 * TEMPLATE_CATALOG.md.
 *
 * Piece 0 [F] — label (reserved slot) + value, always present.
 * Piece 1 [O] — description. Present iff [description] is non-null.
 * Piece 2 [O] + Piece 3 [O] — slider and its caption row. These are
 *   bundled into a single [slider] property of type [SliderConfig]?.
 *   This is the structural enforcement point: the catalog's legal
 *   subsets are (0), (0,1), (0,2,3), (0,1,2,3) — piece 3 (a caption) is
 *   NEVER legal without piece 2 (a slider) above it. There is no
 *   separate captionStart/captionEnd property a caller could set
 *   without also providing a slider — those fields live inside
 *   [SliderConfig] itself, so the illegal (0,3) shape cannot be
 *   expressed in this API at all, not merely discouraged by it.
 * [S] spacer — present whenever pieces 1..3 push the card's total
 *   height past its [F] piece's own boundary rung. Not a property.
 */
class ValueCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /**
     * Bundles a slider's range/value together with its caption text.
     * There is deliberately no way to have one without the other.
     */
    data class SliderConfig(
        val valueFrom: Float,
        val valueTo: Float,
        val stepSize: Float,
        val value: Float,
        val captionStart: String,
        val captionEnd: String,
        val onValueChange: ((Float) -> Unit)? = null
    )

    private val labelView: TextView
    private val valueView: TextView
    private val descriptionView: TextView
    private val sliderView: Slider
    private val captionRow: View
    private val captionStartView: TextView
    private val captionEndView: TextView
    private val spacerView: View
    private val cardRoot: MaterialCardView
    private val bodyView: View

    /** Piece 0 [F]. Required, non-blank. */
    var label: String = ""
        set(v) {
            require(v.isNotBlank()) { "ValueCardView.label must not be blank — it is a fundamental piece." }
            field = v
            labelView.text = v
        }

    /** Piece 0 [F]. Required, non-blank — the value half of the same fundamental row. */
    var value: String = ""
        set(v) {
            require(v.isNotBlank()) { "ValueCardView.value must not be blank — it is part of the fundamental row." }
            field = v
            valueView.text = v
        }

    /** Piece 1 [O]. */
    var description: String? = null
        set(v) {
            field = v
            val show = v != null
            descriptionView.text = v ?: ""
            descriptionView.visibility = if (show) View.VISIBLE else View.GONE
            recomputeSpacer()
        }

    /** Pieces 2+3 [O], bundled — see [SliderConfig]. */
    var slider: SliderConfig? = null
        set(v) {
            field = v
            val show = v != null
            sliderView.visibility = if (show) View.VISIBLE else View.GONE
            captionRow.visibility = if (show) View.VISIBLE else View.GONE
            if (v != null) {
                sliderView.valueFrom = v.valueFrom
                sliderView.valueTo = v.valueTo
                sliderView.stepSize = v.stepSize
                sliderView.value = v.value.coerceIn(v.valueFrom, v.valueTo)
                captionStartView.text = v.captionStart
                captionEndView.text = v.captionEnd
            }
            recomputeSpacer()
        }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_valuecard_internal, this, true)
        labelView = findViewById(R.id.valueCardLabel)
        valueView = findViewById(R.id.valueCardValue)
        descriptionView = findViewById(R.id.valueCardDescription)
        sliderView = findViewById(R.id.valueCardSlider)
        captionRow = findViewById(R.id.valueCardCaptionRow)
        captionStartView = findViewById(R.id.valueCardCaptionStart)
        captionEndView = findViewById(R.id.valueCardCaptionEnd)
        spacerView = findViewById(R.id.valueCardSpacer)
        cardRoot = findViewById(R.id.valueCardRoot)
        bodyView = findViewById(R.id.valueCardBody)

        sliderView.addOnChangeListener { _, newValue, fromUser ->
            if (fromUser) slider?.onValueChange?.invoke(newValue)
        }
        // Must run unconditionally — see NavCardView.kt's init{} for why.
        // Uses the literal `false` rather than the `compact` property: in
        // this file (unlike the other three views), `compact` is declared
        // AFTER init{}, so reading it here would fail to compile ("Variable
        // 'compact' must be initialized") — Kotlin initializes properties
        // top-to-bottom, and init{} runs at its own position in that order.
        // `false` is compact's actual default value, so this is identical
        // in behavior to what was intended.
        applyDensity(false)
    }

    /** Display-density toggle. See other template classes' doc for the
     *  same property; default false, currently used only by the
     *  Render -> Layout demo screen. */
    var compact: Boolean = false
        set(value) {
            field = value
            applyDensity(value)
        }

    /**
     * Re-applies the current live token values without recreating the view —
     * needed for any card built once and kept alive rather than rebuilt on
     * every token change (the Layout demo page's own scale sliders are
     * exactly this case: they're `ValueCardView` instances themselves, and
     * were found to keep their construction-time padding/margin/corner-radius
     * forever, unlike the demo template cards below them which get fully
     * rebuilt — and therefore naturally pick up fresh tokens — on every
     * slider change). Safe to call mid-drag: this only touches the card's
     * own padding/margin/corner radius, never the `Slider` widget inside it
     * or its active touch state.
     */
    public fun refreshDensity() { applyDensity(compact) }

    private fun applyDensity(isCompact: Boolean) {
        CardDensity.applyOuterGap(cardRoot, context, isCompact)
        CardDensity.applyBodyPadding(bodyView, context, isCompact)
        CardDensity.applyCornerRadius(cardRoot, context, isCompact)
        recomputeSpacer()
    }

    /**
     * The spacer's presence is a function of which pieces beyond the
     * fundamental piece 0 are populated — never a caller-set value.
     * Any pieces beyond 0 being present is the only condition checked;
     * exact sizing comes from the style resource itself
     * (app_row_spacer_valuecard_0123), not from anything computed here.
     *
     * Under [compact] mode the spacer is dropped unconditionally: its
     * fixed size was computed to align this card's non-compact total to
     * a boundary rung, which goes stale the moment padding shrinks.
     * Rung-alignment is a cosmetic preference that yields under
     * conflict; "minimum space, no waste" is exactly that conflict.
     */
    private fun recomputeSpacer() {
        if (compact) {
            spacerView.visibility = View.GONE
            return
        }
        val anyOptionalPiecePresent = description != null || slider != null
        spacerView.visibility = if (anyOptionalPiecePresent) View.VISIBLE else View.GONE
    }

    companion object {
        /**
         * The recommended construction path. [label] and [value] are
         * true compile-enforced required parameters — a call to
         * [create] missing either does not compile.
         */
        fun create(
            context: Context,
            label: String,
            value: String,
            description: String? = null,
            slider: SliderConfig? = null,
            compact: Boolean = false
        ): ValueCardView = ValueCardView(context).apply {
            this.label = label
            this.value = value
            this.description = description
            this.compact = compact
            this.slider = slider
        }
    }
}
