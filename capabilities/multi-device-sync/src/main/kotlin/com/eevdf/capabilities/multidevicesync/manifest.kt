package com.eevdf.capabilities.multidevicesync

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object MultiDeviceSyncManifest : CapabilityManifest {
    override val capabilityId = "multi-device-sync"

    override val publishes: Set<Topic<*>> = setOf(
        Topics.SYNC_STATUS_CHANGED,
        Topics.SYNC_LOCAL_PUBLISHED,
        Topics.SYNC_REMOTE_APPLIED,
        Topics.SYNC_FAILED,
        Topics.SYNC_MAINTENANCE,
        Topics.BACKUP_RESTORE_FINISHED,
    )

    override val subscribes: Set<Topic<*>> = setOf(
        Topics.TASK_SAVED,
        Topics.APP_FOREGROUND_CHANGED,
        Topics.SYNC_NOW_REQUESTED,
        Topics.BACKUP_RESTORE_REQUESTED,
    )

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // Nothing this capability misses can lose data, because none of these
        // topics is how a change is *recorded* — that is done by triggers
        // inside the database, in the same transaction as the change itself.
        // The topics only decide WHEN the recorded changes are written out:
        //
        //  task.saved / app.foreground-changed / sync.now-requested missed
        //      → the changes stay recorded and leave on the next sync
        //        (next app open at the latest).
        //  backup.restore-requested missed
        //      → the restore does not happen and the backup screen reports
        //        that nothing was changed; the user's data is untouched.
    }
}

/**
 * ALSO ANSWERS one request topic, `Topics.SAFETY_COPIES_REQUESTED`
 * (`bus.respondTo`, not `subscribe`, so it is not in the sets above):
 * backup-restore asks which automatic safety copies exist.
 *
 * WHY THIS CAPABILITY CARRIES OUT RESTORES (v6.38.0)
 * --------------------------------------------------
 * A restore used to be "overwrite the .db file and kill the process", owned
 * by backup-restore with task-storage closing Room for it. It is now "merge
 * the backup's rows into the live database", which is exactly what this
 * capability's engine already does for sync. One engine, one lock: a restore
 * and a sync cannot overlap, and a restore made in one user reaches the
 * others the same way any edit does. backup-restore still owns everything
 * about the backup *file* (choosing it, unpacking it, the settings inside
 * it) and never imports this capability — it publishes a request.
 *
 * RETIRED (were this capability's, are gone)
 * ------------------------------------------
 *  - `Topics.TASK_CONFLICT_DETECTED` and the "sync blocked — N conflicts"
 *    dialog. Conflicts are settled by rule (newest change wins, per column
 *    for tasks; a later edit beats a delete), identically on every member,
 *    so there is nothing to ask the user.
 *  - `MultiUserSyncManager` (a global `object`), `SyncFieldGuard`,
 *    `TaskFieldClassification` and its test. The engine works from the
 *    database's own schema, so a new Task field needs no registration here.
 *  - The direct `task-list-screen → multi-device-sync` import (flagged debt
 *    since v6.10.8). The toolbar dot now reads `SYNC_STATUS_CHANGED`, the
 *    icon tap publishes `SYNC_NOW_REQUESTED`.
 */
