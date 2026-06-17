package com.eevdf.app.feature.task.adapter

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.eevdf.app.feature.task.notice.NoticePhase
import com.eevdf.data.task.Task

/**
 * Notice-segment progress bar rendering for [TaskAdapter].
 *
 * Separated so changes to segment layout (colours, gap sizes, phase logic) are
 * isolated from the main bind path and quota/badge helpers.
 *
 * Domain:
 *   • [TaskAdapter.buildNoticeSegments]       — full segment rebuild (full bind)
 *   • [TaskAdapter.updateNoticeSegmentFills]  — incremental fill update (tick payload)
 *   • [makeSegmentView]                       — factory for a single segment View
 *
 * Both public-facing functions read adapter state ([noticeTaskId],
 * [currentNoticePhase], [persistedPhaseByTask]) via the receiver.
 */

/**
 * Builds the segmented progress bar for NOTIFICATION-type tasks and fills
 * each segment with live progress from [TaskAdapter.currentNoticePhase].
 *
 * Structure:  [execute — blue] [wait — green]  ×  (repeatCount + 1)
 *
 * Heights: each segment View is explicitly 4 dp — matching the visual track
 * height of the horizontal ProgressBar style — and the container uses
 * gravity=CENTER_VERTICAL so the segments sit centred in the same 6 dp
 * space as progressTask.
 *
 * Live progress: each segment uses a LayerDrawable of
 *   [0] GradientDrawable  — lighter-tint track (always visible)
 *   [1] ClipDrawable      — solid fill clipped left→right by fillPercent
 * ClipDrawable.level ranges 0–10 000 (0 = empty, 10 000 = full).
 *
 * Segments BEFORE the active phase index are fully filled (level 10000).
 * The ACTIVE segment is partially filled from task.progressPercent or
 * elapsed-wait calculation.
 * Segments AFTER are empty (level 0, showing only the track colour).
 */
internal fun TaskAdapter.buildNoticeSegments(container: LinearLayout, task: Task) {
    container.removeAllViews()
    container.gravity = Gravity.CENTER_VERTICAL

    val execSecs = task.timeSliceSeconds
    val waitSecs = task.notificationRestSeconds
    val cycles   = task.notificationRepeatCount + 1
    val hasWait  = waitSecs > 0

    if (execSecs <= 0) return

    // ── Determine which phase to use for fill computation ────────────────
    //
    // Active task  + non-Idle phase  → live progress from currentNoticePhase
    // Active task  + Idle phase      → paused/cancelled; use persisted phase
    //                                  so segments stay frozen at last state
    // Non-active task                → use persisted phase (shows last run)
    // No persisted phase             → all segments show as empty track
    val phase = when {
        task.id == noticeTaskId && currentNoticePhase !is NoticePhase.Idle ->
            currentNoticePhase
        else ->
            persistedPhaseByTask[task.id] ?: NoticePhase.Idle
    }
    val segsPerCycle = if (hasWait) 2 else 1
    val totalSegs    = cycles * segsPerCycle
    val fills        = computeNoticeFills(phase, task, totalSegs, segsPerCycle, cycles, hasWait, waitSecs)

    // ── Colour palette ───────────────────────────────────────────────────
    val execFill  = Color.parseColor("#2196F3")   // Blue  500 — active fill
    val execTrack = Color.parseColor("#BBDEFB")   // Blue  100 — empty track
    val waitFill  = Color.parseColor("#4CAF50")   // Green 500 — active fill
    val waitTrack = Color.parseColor("#C8E6C9")   // Green 100 — empty track

    // ── Segment geometry ─────────────────────────────────────────────────
    val density = container.context.resources.displayMetrics.density
    val segHPx  = (4f * density + 0.5f).toInt()          // explicit 4 dp height
    val gapPx   = (1.5f * density + 0.5f).toInt()        // ~1.5 dp inter-segment gap

    // ── Build and attach views ───────────────────────────────────────────
    var segIdx = 0
    for (i in 0 until cycles) {
        val eIsLast = segIdx == totalSegs - 1
        container.addView(makeSegmentView(
            container.context, execSecs.toFloat(), segHPx,
            if (eIsLast) 0 else gapPx,
            execFill, execTrack, fills[segIdx]))
        segIdx++

        if (hasWait) {
            val wIsLast = segIdx == totalSegs - 1
            container.addView(makeSegmentView(
                container.context, waitSecs.toFloat(), segHPx,
                if (wIsLast) 0 else gapPx,
                waitFill, waitTrack, fills[segIdx]))
            segIdx++
        }
    }
}

/**
 * Updates segment fill levels in-place on an already-built [container].
 *
 * Called via [TaskAdapter.PAYLOAD_NOTICE_TICK] so Wait and Delay ticks repaint
 * fill without touching the view hierarchy — eliminates per-tick flicker.
 *
 * If the container has no children (first bind not yet done) this is a no-op;
 * the next full bind will build the views correctly.
 *
 * Each segment view's background is a [LayerDrawable] of:
 *   layer 0  — track GradientDrawable
 *   layer 1  — ClipDrawable whose level (0–10000) drives the fill width
 */
internal fun TaskAdapter.updateNoticeSegmentFills(container: LinearLayout, task: Task) {
    if (container.childCount == 0) return   // views not yet built; skip

    val execSecs = task.timeSliceSeconds
    val waitSecs = task.notificationRestSeconds
    val cycles   = task.notificationRepeatCount + 1
    val hasWait  = waitSecs > 0
    if (execSecs <= 0) return

    val phase = when {
        task.id == noticeTaskId && currentNoticePhase !is NoticePhase.Idle ->
            currentNoticePhase
        else ->
            persistedPhaseByTask[task.id] ?: NoticePhase.Idle
    }
    val segsPerCycle = if (hasWait) 2 else 1
    val totalSegs    = cycles * segsPerCycle
    if (container.childCount != totalSegs) return  // segment count changed; let full bind fix it

    val fills = computeNoticeFills(phase, task, totalSegs, segsPerCycle, cycles, hasWait, waitSecs)

    // Apply fill levels to existing ClipDrawable layers — no view creation
    for (i in 0 until totalSegs) {
        val v  = container.getChildAt(i) ?: continue
        val ld = v.background as? LayerDrawable ?: continue
        val cd = ld.getDrawable(1) as? ClipDrawable ?: continue
        cd.level = (fills[i] * 100).coerceIn(0, 10_000)
    }
}

// ── Internal fill-computation helper ─────────────────────────────────────────

/**
 * Computes the fill percentage (0–100) for each segment given the current or
 * persisted [phase]. Extracted as a pure function so both [buildNoticeSegments]
 * and [updateNoticeSegmentFills] share identical logic without duplication.
 */
private fun TaskAdapter.computeNoticeFills(
    phase: NoticePhase,
    task: Task,
    totalSegs: Int,
    segsPerCycle: Int,
    cycles: Int,
    hasWait: Boolean,
    waitSecs: Long
): IntArray {
    val fills = IntArray(totalSegs) { 0 }
    when (phase) {
        is NoticePhase.Execute -> {
            val iter    = phase.iteration.coerceIn(0, cycles - 1)
            val execIdx = iter * segsPerCycle
            for (s in 0 until execIdx) fills[s] = 100
            if (execIdx < totalSegs) fills[execIdx] = task.progressPercent
        }
        is NoticePhase.Wait -> {
            val iter    = phase.iteration.coerceIn(0, cycles - 1)
            val execIdx = iter * segsPerCycle
            for (s in 0..execIdx) if (s < totalSegs) fills[s] = 100
            if (hasWait) {
                val waitIdx = execIdx + 1
                if (waitIdx < totalSegs && waitSecs > 0) {
                    val elapsed = (waitSecs - phase.remainingSecs).coerceAtLeast(0L)
                    fills[waitIdx] = (elapsed * 100L / waitSecs).toInt().coerceIn(0, 100)
                }
            }
        }
        is NoticePhase.Expired -> {
            // Full cycle complete — show all segments at 100 %
            for (s in fills.indices) fills[s] = 100
        }
        is NoticePhase.Delay -> {
            // Bug fix #1: During the delay phase the execute slice has not
            // started yet, so there is no live progress to show. Fall back to
            // the persisted phase so the segment bar stays at its prior fill
            // instead of going blank. If there is no persisted state every
            // segment shows the empty track — correct for a first-ever run.
            val fallback = persistedPhaseByTask[task.id]
            if (fallback != null && fallback !is NoticePhase.Idle && fallback !is NoticePhase.Delay) {
                applyFallbackFills(fills, fallback, task, totalSegs, segsPerCycle, cycles, hasWait, waitSecs)
            }
            // else: first-ever run — all fills stay 0, segments show empty track
        }
        else -> { /* Idle: no fill */ }
    }
    return fills
}

/** Applies [fallback] phase fills into [fills] — used by the Delay branch. */
private fun applyFallbackFills(
    fills: IntArray,
    fallback: NoticePhase,
    task: Task,
    totalSegs: Int,
    segsPerCycle: Int,
    cycles: Int,
    hasWait: Boolean,
    waitSecs: Long
) {
    when (fallback) {
        is NoticePhase.Execute -> {
            val iter    = fallback.iteration.coerceIn(0, cycles - 1)
            val execIdx = iter * segsPerCycle
            for (s in 0 until execIdx) fills[s] = 100
            if (execIdx < totalSegs) fills[execIdx] = task.progressPercent
        }
        is NoticePhase.Wait -> {
            val iter    = fallback.iteration.coerceIn(0, cycles - 1)
            val execIdx = iter * segsPerCycle
            for (s in 0..execIdx) if (s < totalSegs) fills[s] = 100
            if (hasWait) {
                val waitIdx = execIdx + 1
                if (waitIdx < totalSegs && waitSecs > 0) {
                    val elapsed = (waitSecs - fallback.remainingSecs).coerceAtLeast(0L)
                    fills[waitIdx] = (elapsed * 100L / waitSecs).toInt().coerceIn(0, 100)
                }
            }
        }
        else -> { /* Expired already handled in the caller */ }
    }
}

// ── View factory ─────────────────────────────────────────────────────────────

/**
 * Creates a single segment View sized at [heightPx] tall with [weight] for
 * proportional width, a [endGapPx] right margin, and a two-layer background:
 * [trackColor] as the always-visible track, [fillColor] clipped to [fillPercent]%
 * from the left as the progress fill.
 */
internal fun makeSegmentView(
    ctx: Context,
    weight: Float,
    heightPx: Int,
    endGapPx: Int,
    fillColor: Int,
    trackColor: Int,
    fillPercent: Int
): View {
    val v  = View(ctx)
    val lp = LinearLayout.LayoutParams(0, heightPx, weight)
    lp.marginEnd = endGapPx
    v.layoutParams = lp

    val radius = 0f  // square edges — rounded caps were visually wrong for split segments

    val track = GradientDrawable().apply {
        setColor(trackColor)
        cornerRadius = radius
    }
    val fill = GradientDrawable().apply {
        setColor(fillColor)
        cornerRadius = radius
    }
    val clip = ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL).also {
        it.level = (fillPercent * 100).coerceIn(0, 10_000)
    }
    v.background = LayerDrawable(arrayOf(track, clip))
    return v
}
