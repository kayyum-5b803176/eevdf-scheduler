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
 *   2. `_currentTask.postValue(null)`  -> task-list-screen's own UI state
 *   3. `TaskDatabase.checkpointWal()` /
 *      `checkpointAndClose()`          -> task-storage  <- THIS FILE
 *
 * Now backup-restore publishes `backup.export-requested` /
 * `backup.import-requested` and each owning capability handles its own part.
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
        bus.subscribe(Topics.BACKUP_IMPORT_REQUESTED, CAPABILITY_ID) {
            // Import overwrites the .db file on disk, so Room must be CLOSED
            // and hold no file locks during the swap. Safe only because every
            // import path restarts the process immediately afterwards,
            // rebuilding the Hilt graph — the closed instance is never reused.
            TaskDatabase.checkpointAndClose(appContext)
        }
    }

    private companion object {
        const val CAPABILITY_ID = "task-storage"
    }
}
