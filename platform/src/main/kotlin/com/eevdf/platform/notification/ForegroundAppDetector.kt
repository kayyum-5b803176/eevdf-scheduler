package com.eevdf.platform.notification

import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Best-effort foreground-app detection, used to decide whether the banner
 * notification style should be suppressed for a configured "exclude app".
 */
object ForegroundAppDetector {

    /**
     * Queries [UsageStatsManager] for the most-recently-used app in the last
     * 5 seconds — a reliable proxy for the current foreground package.
     * Returns null if the PACKAGE_USAGE_STATS permission is not granted or the
     * usage-stats list is empty. Same approach as BubbleOverlayService's own
     * foreground-app check.
     */
    fun getForegroundPackage(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 5_000L, now)
        return stats?.maxByOrNull { it.lastTimeUsed }?.packageName
    }
}
