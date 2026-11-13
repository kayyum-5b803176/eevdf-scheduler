package com.eevdf.capabilities.taskstorage.logic

import android.content.Context
import com.eevdf.capabilities.taskstorage.TaskDatabase
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics

/**
 * Handles the database half of a backup export/import on task-storage's behalf.
 *
 * WHAT THIS REPLACES
 * ------------------
 * Previously `DataBackupActivity` (backup-restore) called
 * `TaskViewModel.prepareForDbExport()` / `prepareForDbImport()` directly —
 * the single cross-capability violation called out in the redesign spec §4.
 * Those two methods did three unrelated things belonging to three different
 * capabilities:
 *
 *   1. `pauseTimer()`                  -> countdown-timer / task-list-screen
 *   2. CurrentTaskOwner.setAsync(null)  -> task-list-screen's own UI state
 *   3. `TaskDatabase.checkpointWal()` /
 *      `checkpointAndClose()`          -> task-storage  <- THIS FILE
 *
 * Now backup-restore publishes `backup.export-requested` and each owning
 * capability handles its own part. (`backup.import-requested`, which made
 * this handler CLOSE the database, was retired in v6.38.0 together with the
 * replace-the-file restore it existed for.)
 *
 * ORDERING GUARANTEE (important — do not "optimise" this away)
 * -----------------------------------------------------------
 * The export flow reads the raw `.db` file off disk immediately after
 * requesting the checkpoint, so the checkpoint MUST have completed first or
 * the backup captures a torn database.
 *
 * That guarantee is preserved because [EventBus.publish] is a `suspend`
 * function built on `coroutineScope { ... launch { ... } }`, and
 * `coroutineScope` does not return until every child coroutine it started
 * has finished. So `bus.publish(BACKUP_EXPORT_REQUESTED, Unit)` suspends
 * until this handler (and every other subscriber) has run to completion —
 * exactly the same ordering the old direct call had.
 */
class BackupCheckpointHandler(
    private val appContext: Context,
    bus: EventBus,
) {
    init {
        bus.subscribe(Topics.BACKUP_EXPORT_REQUESTED, CAPABILITY_ID) {
            // Export never replaces the file, so a WAL checkpoint is enough —
            // Room stays open and the cached Hilt handle remains valid.
            TaskDatabase.checkpointWal(appContext)
        }
        // v6.38.0: there is no import-side subscription any more. A restore
        // no longer overwrites the .db file — it writes rows into the live
        // database (see Topics.BACKUP_RESTORE_REQUESTED) — so Room is never
        // closed and no capability has to restart the process afterwards.
    }

    private companion object {
        const val CAPABILITY_ID = "task-storage"
    }
}
