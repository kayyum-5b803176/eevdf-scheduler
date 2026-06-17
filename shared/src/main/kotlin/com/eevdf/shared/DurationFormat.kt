package com.eevdf.shared

/**
 * Pure formatting helpers shared across features. Depends on nothing; nothing
 * about presentation logic should be duplicated across screens (the reference
 * had several near-identical time formatters in adapter and stats/.
 */
object DurationFormat {

    /** "1h 05m 09s" style, omitting leading zero units. */
    fun hms(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return buildString {
            if (h > 0) append("${h}h ")
            if (h > 0 || m > 0) append("%02dm ".format(m))
            append("%02ds".format(sec))
        }.trim()
    }

    /** Compact "MM:SS" / "HH:MM:SS" clock form for countdown displays. */
    fun clock(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }
}
