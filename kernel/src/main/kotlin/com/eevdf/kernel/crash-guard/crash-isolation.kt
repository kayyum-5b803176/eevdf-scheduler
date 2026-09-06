package com.eevdf.kernel.crashguard

/**
 * Reporting sink for failures contained by the crash guard.
 *
 * Migrated unchanged (behavior-wise) from `shared/SafeRun.kt`. This is the
 * second half of the "crash-recovery merges into kernel/crash-guard"
 * resolution from the Phase -1 audit: [runIsolated] contains the failure,
 * [CrashIsolation] is how the app learns a failure was contained rather than
 * having it vanish silently.
 *
 * `:app` installs a [Reporter] at startup; capabilities never call [install],
 * only the kernel and [runIsolated] call [report].
 *
 * NOTE: the old `safeFeature`/`safeFeatureOr` inline helpers that lived
 * alongside this object in `shared/SafeRun.kt` were NOT migrated — they were
 * `internal` with zero call sites outside `:shared` (documented as such in
 * their own KDoc), and [runIsolated] now covers that role as an enforced
 * mechanism on every bus dispatch rather than an opt-in helper an author has
 * to remember to wrap things in.
 */
public object CrashIsolation {

    public fun interface Reporter {
        public fun onContainedFailure(feature: String, error: Throwable)
    }

    @Volatile
    private var reporter: Reporter? = null

    public fun install(r: Reporter) { reporter = r }

    public fun report(feature: String, error: Throwable) {
        try {
            reporter?.onContainedFailure(feature, error)
        } catch (_: Throwable) {
            // A failing reporter must never escalate into the crash it was
            // reporting on.
        }
    }
}
