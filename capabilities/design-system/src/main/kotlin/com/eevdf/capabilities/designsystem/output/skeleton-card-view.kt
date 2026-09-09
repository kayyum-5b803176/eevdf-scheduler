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
import com.google.android.material.switchmaterial.SwitchMaterial
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
 *   - [smallInputs] — compact tappable controls (a real switch, or an
 *     icon-only button), right-aligned in their own row. Icon buttons get
 *     an opaque square background (see [buildSmallInputView]) so they
 *     read as tappable at a glance; the switch keeps its own native look.
 *
 * Icon-button size and the dropdown box's height are deliberately NOT
 * separate hardcoded dimens — they're read from a throwaway [SwitchMaterial]/
 * [Slider] instance's own MEASURED size (see [referenceSwitchSize]/
 * [referenceSliderHeight]), so "the icon button is exactly as big as the
 * real switch" and "the dropdown box is exactly as tall as the real
 * slider" stay true automatically at whatever live token scale is
 * currently active, rather than two dimens that could silently drift out
 * of sync with the switch/slider's own actual rendered size over time.
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

    /**
     * A real [SwitchMaterial], never attached to any layout, measured with
     * both dimensions UNSPECIFIED — this is what its natural width/height
     * actually resolve to at the current theme/token scale, the same size
     * it would render at if it WERE the visible toggle on this card.
     * `by lazy`: computed once per card instance, on first use, not once
     * per icon button.
     */
    private val referenceSwitchSize: Pair<Int, Int> by lazy {
        val switch = SwitchMaterial(context)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        switch.measure(unspecified, unspecified)
        switch.measuredWidth to switch.measuredHeight
    }

    /** Same technique as [referenceSwitchSize], for a real [Slider]'s natural height. */
    private val referenceSliderHeight: Int by lazy {
        val slider = Slider(context)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        slider.measure(unspecified, unspecified)
        slider.measuredHeight
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
            is SkeletonFullInput.Dropdown -> {
                // RESOLVED: building this purely in Kotlin (constructor
                // defStyleAttr) did not actually apply the outlined-box
                // style — rendered as a plain underlined field. Inflating
                // the same XML shape App.TextInput.Dropdown already works
                // in (see profile-settings-activity.kt's own real
                // Vibration Pattern row) is the proven-working path.
                val fragment = LayoutInflater.from(context)
                    .inflate(R.layout.view_skeleton_dropdown_internal, fullInputContainer, false)
                val actv = fragment.findViewById<AutoCompleteTextView>(R.id.skeletonDropdownField)
                actv.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, input.options))
                actv.setText(input.selected ?: input.options.firstOrNull() ?: "", false)
                actv.setOnItemClickListener { _, _, pos, _ -> input.onSelect?.invoke(input.options[pos]) }
                // The outer box's height forced to equal a real Slider's own
                // measured height — an experiment, not a proven-safe fit:
                // TextInputLayout's natural content (label + box padding)
                // may need more vertical space than a Slider's track+thumb
                // does, so this can clip/crowd the field's own text at some
                // token scales. Width still MATCH_PARENT, unaffected.
                fragment.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, referenceSliderHeight)
                fragment
            }
        }
    }

    /**
     * [SkeletonSmallInput.Toggle] is the real native [SwitchMaterial] —
     * NOT an icon. [SkeletonSmallInput.IconButton] is icon-only, with an
     * opaque square background (see [R.drawable.bg_skeleton_icon_button])
     * so a bare floating glyph doesn't have to be guessed as tappable —
     * sized to exactly match [referenceSwitchSize], so an icon button and
     * the real switch occupy the same footprint if they ever sit in the
     * same row.
     */
    private fun buildSmallInputView(input: SkeletonSmallInput): View = when (input) {
        is SkeletonSmallInput.Toggle -> SwitchMaterial(context).apply {
            isChecked = input.checked
            setOnCheckedChangeListener { _, checked -> input.onChange?.invoke(checked) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        is SkeletonSmallInput.IconButton -> {
            val (refWidth, refHeight) = referenceSwitchSize
            val lp = LinearLayout.LayoutParams(refWidth, refHeight)
            if (smallInputRow.childCount > 0) lp.marginStart = resources.getDimensionPixelSize(R.dimen.app_spacing_sm)
            ImageButton(context).apply {
                layoutParams = lp
                background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_skeleton_icon_button)
                setPadding(
                    resources.getDimensionPixelSize(R.dimen.app_spacing_sm),
                    resources.getDimensionPixelSize(R.dimen.app_spacing_sm),
                    resources.getDimensionPixelSize(R.dimen.app_spacing_sm),
                    resources.getDimensionPixelSize(R.dimen.app_spacing_sm),
                )
                setImageResource(when (input.icon) {
                    SkeletonIcon.NAV_ARROW -> R.drawable.outline_arrow_forward_24
                    SkeletonIcon.HAMBURGER -> R.drawable.outline_menu_24
                    SkeletonIcon.PREVIEW   -> R.drawable.outline_play_arrow_24
                    SkeletonIcon.EXPORT    -> R.drawable.outline_download_24
                })
                setOnClickListener { input.onClick() }
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
