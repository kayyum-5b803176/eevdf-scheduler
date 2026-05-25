package com.eevdf.scheduler.sync

/**
 * Represents the current state of the multi-user live sync feature.
 *
 * Transitions:
 *   Disabled → Idle     (when user enables sync)
 *   Idle     → Syncing  (export or import started)
 *   Syncing  → OK       (operation succeeded)
 *   Syncing  → Error    (operation failed — app keeps running, polling continues)
 *   *        → Disabled (when user disables sync)
 */
sealed class SyncState {
    /** Sync is turned off in settings. */
    object Disabled : SyncState()

    /** Sync is enabled but no operation in progress since last OK / startup. */
    object Idle : SyncState()

    /** An export or import is actively in progress. */
    object Syncing : SyncState()

    /** Last operation completed successfully. */
    object OK : SyncState()

    /**
     * Last operation failed.  [message] is a short human-readable description
     * shown in the sync icon tooltip / error snackbar.
     * Polling continues so the error will self-clear on the next successful sync.
     */
    data class Error(val message: String) : SyncState()
}
