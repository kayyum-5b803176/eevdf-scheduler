package com.eevdf.capabilities.designsystem.output

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.eevdf.capabilities.designsystem.R
import com.eevdf.capabilities.designsystem.entities.SkeletonAction
import com.eevdf.capabilities.designsystem.entities.SkeletonButton
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout

/**
 * EXPERIMENTAL — one shared skeleton (5 fixed, ordered slots: title,
 * subtitle, action, metric, buttons) instead of a different internal
 * layout per card type. NOT one of the 9 catalog templates. Reached ONLY
 * from the Layout demo page's "template" tab right now — no real screen
 * uses this yet; see that screen for why (testing the shape in isolation
 * before any rollout decision).
 *
 * Slot 3 (action) holds AT MOST ONE widget at a time — switch, slider,
 * dropdown, chevron, or a stack of progress bars — inflated into
 * [actionContainer] on demand, previous content cleared first. This is
 * the one slot whose CONTENTS vary by card meaning; slots 1/2/4/5 are
 * always plain text/buttons, same as every other slot in every card.
 */
class SkeletonCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val cardRoot: MaterialCardView
    private val bodyView: View
    private val titleView: TextView
    private val subtitleView: TextView
    private val actionContainer: FrameLayout
    private val metricView: TextView
    private val buttonRow: LinearLayout

    var title: String = ""
        set(value) { field = value; titleView.text = value }

    var subtitle: String? = null
        set(value) {
            field = value
            subtitleView.text = value ?: ""
            subtitleView.visibility = if (value != null) View.VISIBLE else View.GONE
        }

    var action: SkeletonAction? = null
        set(value) {
            field = value
            actionContainer.removeAllViews()
            actionContainer.visibility = if (value != null) View.VISIBLE else View.GONE
            if (value != null) actionContainer.addView(buildActionView(value))
        }

    var metric: String? = null
        set(value) {
            field = value
            metricView.text = value ?: ""
            metricView.visibility = if (value != null) View.VISIBLE else View.GONE
        }

    var buttons: List<SkeletonButton> = emptyList()
        set(value) {
            field = value
            buttonRow.removeAllViews()
            buttonRow.visibility = if (value.isNotEmpty()) View.VISIBLE else View.GONE
            value.forEach { b ->
                val btn = MaterialButton(context).apply {
                    text = b.text
                    setOnClickListener { b.onClick() }
                }
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginEnd = if (buttonRow.childCount > 0) resources.getDimensionPixelSize(R.dimen.app_spacing_sm) else 0
                buttonRow.addView(btn, lp)
            }
        }

    /** Whole-card tap target — see [com.eevdf.capabilities.designsystem.entities.SkeletonCardEntity.onClick]. */
    var onCardClick: (() -> Unit)? = null
        set(value) {
            field = value
            cardRoot.isClickable = value != null
            cardRoot.isFocusable = value != null
            cardRoot.setOnClickListener(if (value != null) { _ -> value.invoke() } else null)
        }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_skeleton_card_internal, this, true)
        cardRoot        = findViewById(R.id.skeletonCardRoot)
        bodyView        = findViewById(R.id.skeletonBody)
        titleView       = findViewById(R.id.skeletonTitle)
        subtitleView    = findViewById(R.id.skeletonSubtitle)
        actionContainer = findViewById(R.id.skeletonActionContainer)
        metricView      = findViewById(R.id.skeletonMetric)
        buttonRow       = findViewById(R.id.skeletonButtonRow)
        CardDensity.applyOuterGap(cardRoot, context, isCompact = false)
        CardDensity.applyBodyPadding(bodyView, context, isCompact = false)
        CardDensity.applyCornerRadius(cardRoot, context, isCompact = false)
    }

    /**
     * Every widget here gets an explicit MATCH_PARENT-width [FrameLayout.LayoutParams]
     * — [FrameLayout.addView] with no params defaults each child to its own
     * WRAP_CONTENT size, which is what made these widgets render at their
     * raw platform-default size instead of the app's actual card width; that
     * was the real bug, not a missing style. The dropdown additionally uses
     * Material's own standard outlined-exposed-dropdown style attr
     * (`textInputOutlinedExposedDropdownMenuStyle`) to match the OUTLINED
     * box variant this design system already uses elsewhere
     * (`App.TextInput.Dropdown`'s parent style) rather than the FILLED
     * variant. The slider needs no custom style at all: Material's default
     * `Slider` already derives its thumb/track color from the activity's
     * own theme `colorPrimary` — the same color `App.Slider`'s explicit
     * `thumbColor`/`trackColorActive` overrides in the real templates
     * resolve to anyway.
     */
    private fun buildActionView(action: SkeletonAction): View {
        val fullWidth = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        return when (action) {
            is SkeletonAction.Switch -> SwitchMaterial(context).apply {
                isChecked = action.checked
                setOnCheckedChangeListener { _, checked -> action.onChange?.invoke(checked) }
                layoutParams = fullWidth
            }
            is SkeletonAction.Slider -> Slider(context).apply {
                valueFrom = action.valueFrom
                valueTo   = action.valueTo
                stepSize  = action.stepSize
                value     = action.value.coerceIn(action.valueFrom, action.valueTo)
                addOnChangeListener { _, v, _ -> action.onValueChange?.invoke(v) }
                layoutParams = fullWidth
            }
            is SkeletonAction.Dropdown -> TextInputLayout(
                context, null, com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle
            ).apply {
                val actv = AutoCompleteTextView(context).apply {
                    setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, action.options))
                    setText(action.selected ?: action.options.firstOrNull() ?: "", false)
                    inputType = android.text.InputType.TYPE_NULL
                    setOnItemClickListener { _, _, pos, _ -> action.onSelect?.invoke(action.options[pos]) }
                }
                addView(actv)
                layoutParams = fullWidth
            }
            SkeletonAction.Chevron -> TextView(context).apply {
                text = "\u203A"
                textSize = 22f
                gravity = android.view.Gravity.END
                layoutParams = fullWidth
            }
            is SkeletonAction.ProgressBars -> LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                action.fractions.forEachIndexed { i, fraction ->
                    val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 1000
                        progress = (fraction.coerceIn(0f, 1f) * 1000).toInt()
                    }
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    if (i > 0) lp.topMargin = resources.getDimensionPixelSize(R.dimen.app_spacing_sm)
                    addView(bar, lp)
                }
                layoutParams = fullWidth
            }
        }
    }

    companion object {
        fun create(
            context: Context,
            title: String,
            subtitle: String? = null,
            action: SkeletonAction? = null,
            metric: String? = null,
            buttons: List<SkeletonButton> = emptyList(),
            onClick: (() -> Unit)? = null,
        ): SkeletonCardView = SkeletonCardView(context).apply {
            this.title = title
            this.subtitle = subtitle
            this.action = action
            this.metric = metric
            this.buttons = buttons
            this.onCardClick = onClick
        }
    }
}
