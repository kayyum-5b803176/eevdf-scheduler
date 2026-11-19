package com.eevdf.capabilities.designsystem.output

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.ContextCompat
import com.eevdf.capabilities.designsystem.R

/**
 * The app's universal info icon: a small circled "i" that explains something when tapped.
 *
 * Put it anywhere a line of explanatory text would otherwise take up room, and give it the text:
 *
 *     infoIcon.infoText = "Fires on this date and time (24 h)"
 *
 * Tapping shows the text in an [InfoBubble] anchored under the icon; a tap anywhere closes it. The
 * text can be changed at any time (a screen whose explanation depends on a choice just reassigns it);
 * an empty text makes the icon do nothing. Size it at the use site like any icon button
 * (`@dimen/app_btn_icon` is the usual touch target) — the drawing is `@dimen/app_icon`.
 *
 * Colours come from the design tokens, so it follows light / dark mode like everything else.
 */
class InfoIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.imageButtonStyle,
) : AppCompatImageButton(context, attrs, defStyleAttr) {

    /** What the bubble says. Empty = tapping does nothing. */
    var infoText: CharSequence = ""

    init {
        setImageResource(R.drawable.ic_info_24)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.app_text_label))
        contentDescription = "Info"
        val ripple = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
            setBackgroundResource(ripple.resourceId)
        } else {
            background = null
        }
        setOnClickListener { if (infoText.isNotEmpty()) InfoBubble.show(this, infoText) }
    }
}

/**
 * A small explanatory bubble anchored under a view, in the card look (card background, rounded
 * corners, hairline border). Closes on a tap anywhere — inside it or outside. It is its own window,
 * so it works over a dialog as well as over a screen.
 */
object InfoBubble {

    /** Wide enough to read, narrow enough to stay a bubble. */
    private const val MAX_WIDTH_DP = 280f

    fun show(anchor: View, text: CharSequence) {
        val context = anchor.context
        val density = context.resources.displayMetrics.density
        val padding = context.resources.getDimensionPixelSize(R.dimen.app_card_padding_md)

        val label = TextView(context).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, R.color.app_text_body))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.app_text_size_md))
            maxWidth = (MAX_WIDTH_DP * density).toInt()
            setPadding(padding, padding, padding, padding)
        }

        val popup = PopupWindow(label, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(
            GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.cardBackground))
                cornerRadius = context.resources.getDimension(R.dimen.app_card_corner_radius)
                setStroke(density.toInt().coerceAtLeast(1), ContextCompat.getColor(context, R.color.divider))
            }
        )
        popup.isOutsideTouchable = true
        popup.elevation = 8f * density
        label.setOnClickListener { popup.dismiss() }

        // Right edges aligned, a hair below the icon.
        popup.showAsDropDown(anchor, 0, (4f * density).toInt(), Gravity.END)
    }
}
