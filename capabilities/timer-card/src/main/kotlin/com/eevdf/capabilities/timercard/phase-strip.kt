package com.eevdf.capabilities.timercard

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.eevdf.capabilities.designsystem.R as DesignSystemR
import com.eevdf.kernel.eventbus.CardStrip
import com.eevdf.kernel.eventbus.StripState

/**
 * Draws the timer card's 7-segment status strip from a [CardStrip] — the ONE shared strip that
 * quota exhaustion and a notification's delay / wait all report through (moved here unchanged from
 * task-list-screen's phase-status-bar.kt; WHAT to show — the quota chain, the blink severity — is
 * still decided by the task list and arrives in the card spec).
 *
 * The 7 segment views are created ONCE, on the first call for a given [container], then reused and
 * updated in place on every later call — never torn down and rebuilt. Two concrete reasons: a
 * blinking segment's animation runs on one persistent View (recreating it on every refresh destroyed
 * the animation before it could visibly move), and tearing down and re-adding all 7 views on a fast
 * timer risks a layout pass catching a half-rebuilt container with a stale mix of colours.
 *
 * Always exactly [StripMath.SEGMENT_COUNT] same-size segments — a permanent part of the card, not
 * something that appears or disappears. No active state → every segment uses the neutral `divider`
 * track colour. One active state colours every segment the same; two or more bounce across them
 * ([StripMath.pingPongIndex]).
 *
 * [CardStrip.blinkSegment], when set, slowly toggles that one segment between the quota red and the
 * neutral track (faster at [CardStrip.blinkFast]); every other segment is untouched. The animation is
 * only (re)started when the blink TARGET or SPEED changes — not on every call.
 */
internal fun renderStrip(container: LinearLayout, strip: CardStrip) {
    container.visibility = View.VISIBLE
    val context = container.context

    if (container.childCount != StripMath.SEGMENT_COUNT) {
        container.removeAllViews()
        val density = context.resources.displayMetrics.density
        val gapPx = (2 * density).toInt()
        for (i in 0 until StripMath.SEGMENT_COUNT) {
            val segment = View(context)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            if (i > 0) params.marginStart = gapPx
            container.addView(segment, params)
        }
    }

    val neutralColor = ContextCompat.getColor(context, DesignSystemR.color.divider)
    val quotaColor   = ContextCompat.getColor(context, colorOf(StripState.QUOTA))
    val stateColors  = strip.states.map { ContextCompat.getColor(context, colorOf(it)) }
    val blinkIndex   = strip.blinkSegment.takeIf { it in 0 until StripMath.SEGMENT_COUNT }
    val intervalMs   = if (strip.blinkFast) FAST_BLINK_INTERVAL_MS else NORMAL_BLINK_INTERVAL_MS

    for (i in 0 until StripMath.SEGMENT_COUNT) {
        val segment = container.getChildAt(i)
        val color = if (stateColors.isEmpty()) neutralColor
                    else stateColors[StripMath.pingPongIndex(i, stateColors.size)]

        val shouldBlink   = i == blinkIndex
        val currentHandle = segment.getTag(R.id.phase_status_blink_tag) as? BlinkHandle
        when {
            shouldBlink && currentHandle == null -> segment.startBlinkToggle(quotaColor, neutralColor, intervalMs)
            // Same segment, but severity crossed the fast-blink threshold (or dropped back) since the
            // last call — restart at the new speed rather than leaving the old one running.
            shouldBlink && currentHandle?.intervalMs != intervalMs -> segment.startBlinkToggle(quotaColor, neutralColor, intervalMs)
            !shouldBlink && currentHandle != null -> segment.stopBlinkToggle(color)
            !shouldBlink -> segment.setBackgroundColor(color)
            // shouldBlink && same interval → already toggling correctly; leave it running.
        }
    }
}

/** Stops every running blink in [container] — used when the card goes away, so no handler outlives it. */
internal fun stopStripBlinks(container: LinearLayout) {
    for (i in 0 until container.childCount) {
        val segment = container.getChildAt(i)
        (segment.getTag(R.id.phase_status_blink_tag) as? BlinkHandle)?.let {
            it.handler.removeCallbacks(it.runnable)
            segment.setTag(R.id.phase_status_blink_tag, null)
        }
    }
}

private fun colorOf(state: StripState): Int = when (state) {
    StripState.QUOTA -> DesignSystemR.color.quotaBarExceeded
    StripState.DELAY -> DesignSystemR.color.timerYellow
    StripState.WAIT  -> DesignSystemR.color.timerGreen
}

/**
 * Hard on/off colour toggle — NOT a smooth fade. "On" is the quota red; "off" is the neutral track
 * colour, so the blinking segment reads as "red / same as an idle segment". A self-rescheduling
 * [Runnable] rather than Android's Animation system, which only interpolates continuously.
 */
private class BlinkHandle(val handler: Handler, val runnable: Runnable, val intervalMs: Long)

private fun View.startBlinkToggle(onColor: Int, offColor: Int, intervalMs: Long) {
    (getTag(R.id.phase_status_blink_tag) as? BlinkHandle)?.let { it.handler.removeCallbacks(it.runnable) }
    val handler = Handler(Looper.getMainLooper())
    var isOn = true
    lateinit var toggle: Runnable
    toggle = Runnable {
        setBackgroundColor(if (isOn) onColor else offColor)
        isOn = !isOn
        handler.postDelayed(toggle, intervalMs)
    }
    setTag(R.id.phase_status_blink_tag, BlinkHandle(handler, toggle, intervalMs))
    handler.post(toggle)
}

/** Cancels a running [startBlinkToggle] and leaves the segment at [staticColor]. */
private fun View.stopBlinkToggle(staticColor: Int) {
    (getTag(R.id.phase_status_blink_tag) as? BlinkHandle)?.let { it.handler.removeCallbacks(it.runnable) }
    setTag(R.id.phase_status_blink_tag, null)
    setBackgroundColor(staticColor)
}

/** Each on/off half-cycle at ordinary severity — unhurried, not a flicker. */
private const val NORMAL_BLINK_INTERVAL_MS = 700L

/** Each on/off half-cycle once overage reaches 128x — only ever the last segment (see the task list's quotaBlinkSegment). */
private const val FAST_BLINK_INTERVAL_MS = 250L
