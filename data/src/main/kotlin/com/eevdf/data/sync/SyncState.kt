package com.eevdf.data.sync

/**
 * Represents the current state of the multi-user live sync feature.
 *
 * Transitions:
 *   Disabled        → Idle            (when user enables sync)
 *   Idle            → Syncing         (export or import started)
 *   Syncing         → OK              (operation succeeded)
 *   Syncing         → Error           (operation failed — polling continues)
 *   Syncing         → ConflictPending (import blocked by blank-overwrite / deleted task)
 *   ConflictPending → Syncing         (user chose "Accept anyway" → force import)
 *   ConflictPending → Idle            (user chose "Skip" → discard remote snapshot)
 *   *               → Disabled        (when user disables sync)
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

    /**
     * An incoming sync snapshot was inspected and found to contain one or more
     * field-level conflicts (blank overwrites or remote task deletions).
     *
     * The sync engine has **blocked** the import and is waiting for the user to
     * decide:
     *   • Accept  → [MultiUserSyncManager.forceAcceptPendingImport] — apply
     *               the snapshot regardless, then resume polling.
     *   • Skip    → [MultiUserSyncManager.skipPendingImport] — discard the
     *               snapshot, advance lastKnownVersion so we don't re-flag the
     *               same snapshot, then resume polling.
     *
     * Polling is **paused** while this state is active so we don't fire repeat
     * dialogs for the same snapshot.
     */
    data class ConflictPending(
        /** Every conflict that blocked this import — used to populate the warning dialog. */
        val conflicts: List<SyncConflict>,
        /**
         * Opaque token the sync engine needs to resume work after the user
         * decides.  The Activity passes this back to [MultiUserSyncManager].
         */
        val pendingToken: PendingImportToken,
    ) : SyncState()
}

/**
 * Holds the state needed to either apply or discard a blocked import.
 * Created inside [MultiUserSyncManager] and passed to the UI via
 * [SyncState.ConflictPending].
 */
data class PendingImportToken(
    val syncDir: java.io.File?,
    val meta: org.json.JSONObject,
    val remoteVersion: Long,
)
