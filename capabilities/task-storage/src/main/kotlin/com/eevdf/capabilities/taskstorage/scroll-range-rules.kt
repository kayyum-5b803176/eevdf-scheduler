package com.eevdf.capabilities.taskstorage

/**
 * Pure maths for the auto-scroll "box" — no Android, no views, no clock.
 *
 * While a task is running and Auto Scroll is on, the list is boxed in between
 * two walls:
 *   • top wall    — the ROOT ancestor's row (the root-level group the running
 *                   task lives under; the running row itself for a root task),
 *   • bottom wall — the RUNNING task's row.
 * Inside the box the person scrolls freely; a fling that reaches a wall simply
 * stops there. Nothing above the root row and nothing below the running row can
 * be scrolled into view. See TaskView `ScrollRangeDelegate` for the Android side.
 *
 * Everything here works on plain numbers (row indices and pixel edges) so the
 * rules can be tested without a device. The delegate reads the real values off
 * the RecyclerView and applies what these functions return.
 *
 * Signs follow `RecyclerView.scrollBy(0, dy)`: a POSITIVE dy moves the content
 * UP (the list scrolls down), a NEGATIVE dy moves the content DOWN.
 */
object ScrollRangeRules {

    /**
     * Row indices of the box for the row at [runningIndex] in a flat, hierarchical
     * list (a parent row precedes its children, [TaskDisplayItem.depth] grows by
     * one per level): `rootIndex..runningIndex`. Null when [runningIndex] is not a
     * row of [list].
     *
     * A row with depth 0 — a root-level task, or any list that is not nested —
     * boxes just itself.
     */
    fun rangeOf(list: List<TaskDisplayItem>, runningIndex: Int): IntRange? {
        if (runningIndex !in list.indices) return null
        var top   = runningIndex
        var depth = list[runningIndex].depth
        var i     = runningIndex - 1
        while (depth > 0 && i >= 0) {
            if (list[i].depth < depth) { top = i; depth = list[i].depth }
            i--
        }
        return top..runningIndex
    }

    /**
     * When the whole viewport (rows [first]..[last]) has left the box, the row to
     * jump to: the root row when the viewport is entirely above the box, the
     * running row when it is entirely below. Null when any row of the box is at
     * least partly on screen (the pixel [correction] deals with that case).
     */
    fun farTarget(box: IntRange, first: Int, last: Int): Int? = when {
        last  < box.first -> box.first
        first > box.last  -> box.last
        else              -> null
    }

    /**
     * The scroll (`dy` for `scrollBy`) that puts the box back inside its walls, 0
     * when it already is.
     *
     * [rootTop] is the top edge of the root row and [runBottom] the bottom edge of
     * the running row, both in RecyclerView coordinates; null when that row is not
     * laid out (it is entirely off screen on that side, which is always allowed).
     * [viewTop]/[viewBottom] are the edges of the usable area (padding excluded).
     *
     *   • Top wall: nothing above the root row may show, i.e. the root row's top
     *     edge must not be below [viewTop].
     *   • Bottom wall: nothing below the running row may show, i.e. its bottom
     *     edge must not be above [viewBottom].
     *   • When the box is shorter than the screen both cannot hold; the top wall
     *     wins — the root row stays at the top and whatever follows the running
     *     row is shown below it (hiding it would mean filtering the list).
     */
    fun correction(rootTop: Int?, runBottom: Int?, viewTop: Int, viewBottom: Int): Int {
        if (rootTop != null && rootTop > viewTop) return rootTop - viewTop          // rows above the root peek in → scroll down the list
        if (runBottom != null && runBottom < viewBottom) {                          // rows below the running row peek in → scroll up the list
            val want = viewBottom - runBottom
            val room = if (rootTop != null) viewTop - rootTop else want             // never push the root row below the top wall
            return -(minOf(want, room).coerceAtLeast(0))
        }
        return 0
    }

    /**
     * `scrollToPositionWithOffset` offset that pins a row of [rowHeight] to the
     * BOTTOM edge of a viewport of [viewHeight] — used when the chain is taller
     * than the screen and the running task has to win over the root.
     */
    fun bottomOffset(viewHeight: Int, rowHeight: Int): Int = (viewHeight - rowHeight).coerceAtLeast(0)
}
