package com.eevdf.shared

/**
 * Crash isolation at feature boundaries.
 *
 * This app runs a foreground alarm service, an overlay window and a call-state
 * receiver in one process. Without isolation, an unhandled exception in the
 * stats screen or the bubble overlay takes down the alarm service with it, and
 * the user misses the thing the app exists to do.
 *
 * Use [safeFeature] at the OUTER edge of a feature only - a service entry
 * point, a receiver's onReceive, a fragment's render pass. Do NOT sprinkle it
 * through business logic; swallowing exceptions in the middle of a transaction
 * hides bugs instead of containing them.
 *
 *     override fun onReceive(context: Context, intent: Intent) = safeFeature("call-state") {
 *         handleCallState(context, intent)
 *     }
 */
inline fun safeFeature(
    feature: String,
    onError: (Throwable) -> Unit = {},
    block: () -> Unit,
) {
    try {
        block()
    } catch (e: Throwable) {
        // Never swallow the JVM's own fatal signals - those are not ours to contain.
        if (e is VirtualMachineError || e is LinkageError || e is ThreadDeath) throw e
        CrashIsolation.report(feature, e)
        onError(e)
    }
}

/** As [safeFeature], but for code that produces a value; returns [fallback] on failure. */
inline fun <T> safeFeatureOr(feature: String, fallback: T, block: () -> T): T =
    try {
        block()
    } catch (e: Throwable) {
        if (e is VirtualMachineError || e is LinkageError || e is ThreadDeath) throw e
        CrashIsolation.report(feature, e)
        fallback
    }

/**
 * Sink for contained failures. :app installs a reporter that forwards to
 * Logcat and, once you add one, to Crashlytics/Sentry as a NON-fatal - a
 * contained crash you never see is a bug you never fix.
 */
object CrashIsolation {

    fun interface Reporter {
        fun onContainedFailure(feature: String, error: Throwable)
    }

    @Volatile
    private var reporter: Reporter? = null

    fun install(r: Reporter) { reporter = r }

    fun report(feature: String, error: Throwable) {
        try {
            reporter?.onContainedFailure(feature, error)
        } catch (_: Throwable) {
            // A failing reporter must never escalate into the crash it was
            // reporting on.
        }
    }
}
