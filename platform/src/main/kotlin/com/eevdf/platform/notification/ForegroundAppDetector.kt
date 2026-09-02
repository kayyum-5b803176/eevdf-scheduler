package com.eevdf.platform.notification

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Best-effort foreground-app detection, used to decide whether the banner
 * notification style should be suppressed for a configured "exclude app".
 */
object ForegroundAppDetector {

    /**
     * Current foreground package, resolved via UsageStatsManager EVENTS (not
     * aggregated stats).
     *
     * An earlier implementation used queryUsageStats(INTERVAL_DAILY) and
     * picked the entry with the largest lastTimeUsed. Those are coarse daily
     * buckets whose lastTimeUsed lags by seconds-to-minutes and frequently
     * resolves to the wrong app (or to our own app, which just ran the
     * alarm) — which is why "Exclude App" silently failed to match.
     *
     * queryEvents() returns the actual ordered stream of foreground/background
     * transitions. Scan a short recent window and take the package of the
     * most recent MOVE_TO_FOREGROUND event — the real current foreground app.
     * The window widens if nothing is found (e.g. the device was idle).
     *
     * Returns null when PACKAGE_USAGE_STATS is not granted or no event is
     * found (fail-open: the "exclude app" match is skipped, banner shows).
     */
    fun getForegroundPackage(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        for (windowMs in longArrayOf(10_000L, 60_000L, 300_000L, 3_600_000L)) {
            val pkg = lastForegroundFromEvents(usm, now - windowMs, now)
            if (pkg != null) return pkg
        }
        return null
    }

    private fun lastForegroundFromEvents(
        usm: UsageStatsManager,
        begin: Long,
        end: Long
    ): String? {
        val events = usm.queryEvents(begin, end)
        val e = UsageEvents.Event()
        var lastPkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                // Events arrive in time order, so the last match is the most recent.
                lastPkg = e.packageName
            }
        }
        return lastPkg
    }
}
