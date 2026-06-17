package com.eevdf.platform.scheduler

import android.content.SharedPreferences
import com.eevdf.core.scheduler.ports.RrStatePort
import com.eevdf.core.time.Clock

/** Real system clock. The single allowed `System.currentTimeMillis()` in the app. */
class SystemClock : Clock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

/**
 * SCHED_RR cursor persistence. In the reference this `SharedPreferences` access
 * lived INSIDE the core scheduler; here it sits behind the [RrStatePort] so the
 * core stays pure. Resets the index when the cohort membership changes (same
 * behaviour as the reference, expressed through the port contract).
 */
class SharedPrefsRrStateStore(private val prefs: SharedPreferences) : RrStatePort {

    override fun currentIndex(cohortKey: String): Int {
        val savedCohort = prefs.getString(KEY_COHORT, "") ?: ""
        return if (savedCohort == cohortKey) prefs.getInt(KEY_INDEX, 0) else 0
    }

    override fun setIndex(cohortKey: String, index: Int) {
        prefs.edit().putString(KEY_COHORT, cohortKey).putInt(KEY_INDEX, index).apply()
    }

    override fun clear(cohortKey: String) {
        prefs.edit().remove(KEY_COHORT).remove(KEY_INDEX).apply()
    }

    private companion object {
        const val KEY_INDEX = "rt_rr_index"
        const val KEY_COHORT = "rt_rr_cohort"
    }
}
