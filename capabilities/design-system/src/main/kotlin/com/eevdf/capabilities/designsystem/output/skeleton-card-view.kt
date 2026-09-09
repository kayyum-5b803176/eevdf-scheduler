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
 * Icon-button size is deliberately NOT a hardcoded dimen — it's read from
 * a throwaway [SwitchMaterial] instance's own MEASURED size (see
 * [referenceSwitchSize]), so "the icon button is exactly as big as the
 * real switch" stays true automatically at whatever live token scale is
 * currently active, rather than a dimen that could silently drift out of
 * sync with the switch's own actual rendered size over time.
 *
 * The slider row and the dropdown box are a different case: previously
 * both were forced to a fixed guessed dp value (48dp), which fought each
 * widget's own internal geometry — clipped dropdown text, a stray
 * label-cutout line, and a displaced popup anchor, since padding a field
 * to hit an external number pushes its content into space the widget
 * itself reserves for other things. Fixed by flipping which side is the
 * source of truth: the dropdown's outlined box (`App.TextInput.Dropdown.Dense`
 * in themes.xml) is left completely untouched — no padding hacks, no
 * forced height — and its own real, natural, artifact-free measured
 * height (see [referenceDropdownBoxHeight]) becomes the one reference
 * every OTHER full-width control's outer slot is built to match. The
 * slider's own track+thumb stays exactly as thin as it naturally is; it
 * just sits centered inside an invisible frame sized to that same
 * reference, the same "real widget's own measured size, never a
 * dp guess" technique [referenceSwitchSize] already uses for icon
 * buttons. Only the CONTAINER/slot is matched across tools — the tools
 * themselves (slider track vs. text field) are never resized to look
 * alike.
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

    /** The one real reference every full-width input's outer slot is built
     *  to match — the dropdown box's own natural, untouched, artifact-free
     *  measured height (Dense outlined style, no padding hacks). Not a
     *  guessed dp value: inflates the exact same fragment the real
     *  dropdown control uses (see [buildFullInputView]'s Dropdown branch),
     *  with a placeholder option so its measured height reflects real
     *  text content, and reads back its natural size. `by lazy`: computed
     *  once per card instance. */
    private val referenceDropdownBoxHeight: Int by lazy {
        val probe = LayoutInflater.from(context)
            .inflate(R.layout.view_skeleton_dropdown_internal, fullInputContainer, false)
        val actv = probe.findViewById<AutoCompleteTextView>(R.id.skeletonDropdownField)
        actv.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, listOf("Sample")))
        actv.setText("Sample", false)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            (fullInputContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels),
            View.MeasureSpec.AT_MOST
        )
        val unspecifiedHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        probe.measure(widthSpec, unspecifiedHeight)
        probe.measuredHeight
    }

    private fun buildFullInputView(input: SkeletonFullInput): View {
        return when (input) {
            is SkeletonFullInput.Slider -> {
                // The Slider widget itself is completely untouched — its
                // own track/thumb/touch-target geometry stays exactly as
                // thin as it naturally is. It's centered inside an
                // invisible frame sized to [referenceDropdownBoxHeight] —
                // matching the dropdown's real SLOT, not its own tool
                // size, to the dropdown's real box.
                val band = FrameLayout(context).apply {
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, referenceDropdownBoxHeight)
                }
                val slider = Slider(context).apply {
                    valueFrom = input.valueFrom
                    valueTo   = input.valueTo
                    stepSize  = input.stepSize
                    value     = input.value.coerceIn(input.valueFrom, input.valueTo)
                    addOnChangeListener { _, v, _ -> input.onValueChange?.invoke(v) }
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER_VERTICAL
                    )
                }
                band.addView(slider)
                band
            }
            is SkeletonFullInput.Dropdown -> {
                // RESOLVED: building this purely in Kotlin (constructor
                // defStyleAttr) did not actually apply the outlined-box
                // style — rendered as a plain underlined field. Inflating
                // the same XML shape works (see profile-settings-activity.kt's
                // own real Vibration Pattern row) is the proven-working path.
                //
                // RESOLVED: this used to be force-padded to hit an external
                // 48dp target — that pushed the field's content into the
                // label-cutout region the outlined border reserves (the
                // stray line above the box) and threw off the dropdown
                // popup's own anchor-offset math (the ~48dp-displaced
                // popup). This box is now left completely untouched: no
                // padding, no forced height. Its own natural size IS the
                // reference every other control's slot matches (see
                // [referenceDropdownBoxHeight]) — nothing here needs to
                // calibrate to anything else.
                val fragment = LayoutInflater.from(context)
                    .inflate(R.layout.view_skeleton_dropdown_internal, fullInputContainer, false)
                val actv = fragment.findViewById<AutoCompleteTextView>(R.id.skeletonDropdownField)
                actv.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, input.options))
                actv.setText(input.selected ?: input.options.firstOrNull() ?: "", false)
                actv.setOnItemClickListener { _, _, pos, _ -> input.onSelect?.invoke(input.options[pos]) }
                fragment.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
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
