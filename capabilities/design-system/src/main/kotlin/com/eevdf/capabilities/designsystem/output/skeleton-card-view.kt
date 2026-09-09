package com.eevdf.capabilities.designsystem.output

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.eevdf.capabilities.designsystem.R
import com.eevdf.capabilities.designsystem.entities.SkeletonFullInput
import com.eevdf.capabilities.designsystem.entities.SkeletonIcon
import com.eevdf.capabilities.designsystem.entities.SkeletonSmallInput
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputLayout

/**
 * EXPERIMENTAL — one shared skeleton (4 fixed, ordered slots: title,
 * subtitle, metric, input) instead of a different internal layout per card
 * type. NOT one of the 9 catalog templates; does not affect the real task
 * card. Reached ONLY from the Layout demo page's "template" tab right now.
 *
 * Slot 4 (input) is two independent row kinds:
 *   - [fullInput] — at most one full-width control (slider or the native
 *     dropdown box), inflated into its own container.
 *   - [smallInputs] — zero or more icon-only tappable controls, laid out
 *     right-aligned in their own row, same convention the real task card's
 *     action row already uses (icons, never text-labelled buttons).
 */
class SkeletonCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val cardRoot: MaterialCardView
    private val bodyView: View
    private val titleView: TextView
    private val subtitleView: TextView
    private val metricView: TextView
    private val fullInputContainer: FrameLayout
    private val smallInputRow: LinearLayout

    var title: String = ""
        set(value) { field = value; titleView.text = value }

    var subtitle: String? = null
        set(value) {
            field = value
            subtitleView.text = value ?: ""
            subtitleView.visibility = if (value != null) View.VISIBLE else View.GONE
        }

    var metric: String? = null
        set(value) {
            field = value
            metricView.text = value ?: ""
            metricView.visibility = if (value != null) View.VISIBLE else View.GONE
        }

    var fullInput: SkeletonFullInput? = null
        set(value) {
            field = value
            fullInputContainer.removeAllViews()
            fullInputContainer.visibility = if (value != null) View.VISIBLE else View.GONE
            if (value != null) fullInputContainer.addView(buildFullInputView(value))
        }

    var smallInputs: List<SkeletonSmallInput> = emptyList()
        set(value) {
            field = value
            smallInputRow.removeAllViews()
            smallInputRow.visibility = if (value.isNotEmpty()) View.VISIBLE else View.GONE
            // Right-aligned, in sequence: smallInputRow's own gravity is
            // "end", and children are added in list order — the first
            // entry ends up leftmost WITHIN the right-packed cluster, the
            // cluster itself sits at the row's right edge.
            value.forEach { input -> smallInputRow.addView(buildSmallInputView(input)) }
        }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_skeleton_card_internal, this, true)
        cardRoot           = findViewById(R.id.skeletonCardRoot)
        bodyView           = findViewById(R.id.skeletonBody)
        titleView          = findViewById(R.id.skeletonTitle)
        subtitleView       = findViewById(R.id.skeletonSubtitle)
        metricView         = findViewById(R.id.skeletonMetric)
        fullInputContainer = findViewById(R.id.skeletonFullInputContainer)
        smallInputRow      = findViewById(R.id.skeletonSmallInputRow)
        CardDensity.applyOuterGap(cardRoot, context, isCompact = false)
        CardDensity.applyBodyPadding(bodyView, context, isCompact = false)
        CardDensity.applyCornerRadius(cardRoot, context, isCompact = false)
    }

    private fun buildFullInputView(input: SkeletonFullInput): View {
        val fullWidth = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        return when (input) {
            is SkeletonFullInput.Slider -> Slider(context).apply {
                valueFrom = input.valueFrom
                valueTo   = input.valueTo
                stepSize  = input.stepSize
                value     = input.value.coerceIn(input.valueFrom, input.valueTo)
                addOnChangeListener { _, v, _ -> input.onValueChange?.invoke(v) }
                layoutParams = fullWidth
            }
            is SkeletonFullInput.Dropdown -> TextInputLayout(
                context, null, com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle
            ).apply {
                val actv = AutoCompleteTextView(context).apply {
                    setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, input.options))
                    setText(input.selected ?: input.options.firstOrNull() ?: "", false)
                    inputType = android.text.InputType.TYPE_NULL
                    setOnItemClickListener { _, _, pos, _ -> input.onSelect?.invoke(input.options[pos]) }
                }
                addView(actv)
                layoutParams = fullWidth
            }
        }
    }

    /**
     * Icon-only, same as every interactive element in this row — no text
     * label. [SkeletonSmallInput.Toggle] swaps the SAME icon's tint between
     * "on" (colorPrimary) and "off" (the ambient control color) rather than
     * showing a track+thumb switch shape, so it stays icon-only like every
     * other small input, not a different kind of widget.
     */
    private fun buildSmallInputView(input: SkeletonSmallInput): View {
        val sizePx = resources.getDimensionPixelSize(R.dimen.app_icon)
        val lp = LinearLayout.LayoutParams(sizePx, sizePx)
        val marginPx = resources.getDimensionPixelSize(R.dimen.app_spacing_sm)
        if (smallInputRow.childCount > 0) lp.marginStart = marginPx
        return ImageButton(context).apply {
            layoutParams = lp
            background = null
            when (input) {
                is SkeletonSmallInput.Toggle -> {
                    setImageResource(R.drawable.outline_power_settings_24)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        if (input.checked) resources.getColor(R.color.colorPrimary, context.theme)
                        else resources.getColor(R.color.app_text_hint, context.theme)
                    )
                    setOnClickListener { input.onChange?.invoke(!input.checked) }
                }
                is SkeletonSmallInput.IconButton -> {
                    setImageResource(when (input.icon) {
                        SkeletonIcon.NAV_ARROW -> R.drawable.outline_arrow_forward_24
                        SkeletonIcon.PREVIEW   -> R.drawable.outline_play_arrow_24
                        SkeletonIcon.EXPORT    -> R.drawable.outline_download_24
                    })
                    setOnClickListener { input.onClick() }
                }
            }
        }
    }

    companion object {
        fun create(
            context: Context,
            title: String,
            subtitle: String? = null,
            metric: String? = null,
            fullInput: SkeletonFullInput? = null,
            smallInputs: List<SkeletonSmallInput> = emptyList(),
        ): SkeletonCardView = SkeletonCardView(context).apply {
            this.title = title
            this.subtitle = subtitle
            this.metric = metric
            this.fullInput = fullInput
            this.smallInputs = smallInputs
        }
    }
}
