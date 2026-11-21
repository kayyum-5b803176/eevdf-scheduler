package com.eevdf.capabilities.grouppicker.output

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import androidx.recyclerview.widget.RecyclerView

/**
 * A RecyclerView that wraps its content but never grows past [maxHeightPx].
 *
 * Inside a wrap_content dialog a plain RecyclerView takes all the room its parent offers, and a
 * LinearLayout measures children in order — so anything BELOW a long list (the path mode footer with
 * Done) was pushed off screen. Capping the list keeps the footer visible however many entries there are.
 * Unlimited by default, so a dialog that does not set it behaves exactly as before.
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    var maxHeightPx: Int = Int.MAX_VALUE

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val capped = if (maxHeightPx == Int.MAX_VALUE) heightSpec else {
            val offered = MeasureSpec.getSize(heightSpec)
            val limit = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) maxHeightPx else minOf(maxHeightPx, offered)
            MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthSpec, capped)
    }
}
