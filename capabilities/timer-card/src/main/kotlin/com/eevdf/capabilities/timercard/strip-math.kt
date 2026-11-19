package com.eevdf.capabilities.timercard

/** Arithmetic of the 7-segment status strip. Pure. */
internal object StripMath {

    /** Fixed segment count — never grows, even if a fourth state is added one day. */
    const val SEGMENT_COUNT = 7

    /**
     * Index into the active states (0 until [n]) for bar position [i] under a ping-pong (triangle
     * wave) walk: forward through all n states, then back, repeating.
     * n=1 → always 0.  n=2 → 0,1,0,1,…  n=3 → 0,1,2,1,0,1,2,…
     */
    fun pingPongIndex(i: Int, n: Int): Int {
        if (n <= 1) return 0
        val period = 2 * (n - 1)
        val m = i % period
        return if (m < n) m else period - m
    }
}
