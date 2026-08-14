package com.eevdf.app.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.eevdf.shared.CrashIsolation
import com.eevdf.shared.FeatureFlag
import com.eevdf.shared.FeatureFlags

/**
 * SharedPreferences-backed [FeatureFlags].
 *
 * Local only - no server, no network, no extra dependency. Toggle a flag from a
 * hidden developer screen, or from adb while debugging:
 *
 *     adb shell am broadcast -a com.eevdf.scheduler.SET_FLAG \
 *         --es key ff_autoswitch_bubble --ez value false
 *
 * (wire that receiver yourself in a debug-only source set if you want it)
 */
class SharedPrefsFeatureFlags(context: Context) : FeatureFlags {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isEnabled(flag: FeatureFlag): Boolean =
        prefs.getBoolean(flag.key, flag.defaultEnabled)

    fun setEnabled(flag: FeatureFlag, enabled: Boolean) {
        prefs.edit().putBoolean(flag.key, enabled).apply()
    }

    fun reset(flag: FeatureFlag) {
        prefs.edit().remove(flag.key).apply()
    }

    fun snapshot(): Map<FeatureFlag, Boolean> =
        FeatureFlag.entries.associateWith { isEnabled(it) }

    companion object {
        const val PREFS_NAME = "eevdf_feature_flags"
    }
}

/**
 * Logcat reporter for contained feature failures.
 *
 * Replace the body of [onContainedFailure] with a Crashlytics
 * `recordException` or Sentry `captureException` call when you add a reporter.
 * Contained failures MUST reach you somehow - the whole point of isolation is
 * that the user does not notice, which means you will not hear about it.
 */
object LogcatCrashReporter : CrashIsolation.Reporter {
    private const val TAG = "FeatureIsolation"

    override fun onContainedFailure(feature: String, error: Throwable) {
        Log.e(TAG, "Contained failure in feature '$feature' - app kept running", error)
        // Crashlytics.getInstance().recordException(error)
    }
}
