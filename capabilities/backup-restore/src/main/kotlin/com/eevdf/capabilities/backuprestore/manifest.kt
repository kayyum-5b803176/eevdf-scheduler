package com.eevdf.capabilities.backuprestore

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object BackupRestoreManifest : CapabilityManifest {
    override val capabilityId = "backup-restore"
    override val publishes: Set<Topic<*>> = setOf(Topics.BACKUP_EXPORT_REQUESTED, Topics.BACKUP_RESTORE_REQUESTED)
    override val subscribes: Set<Topic<*>> = setOf(Topics.BACKUP_RESTORE_FINISHED)

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // backup.restore-finished is only how the backup screen learns the
        // outcome of a restore it asked for. If this capability is not
        // attached (the screen was closed mid-restore) the restore still
        // completes — it runs in multi-device-sync, in one transaction — and
        // only the on-screen message is missed.
    }
}

/**
 * This capability owns the backup FILE: choosing it, the .zip container, the
 * settings inside it. It holds no reference to any other capability's
 * classes for restoring — it publishes `backup.restore-requested` with the
 * path of an unpacked private database file and waits for
 * `backup.restore-finished`. It also ASKS one request topic,
 * `Topics.SAFETY_COPIES_REQUESTED`, to list automatic safety copies.
 *
 * v6.38.0 retired `backup.import-requested` (which made task-storage close
 * Room so this capability could overwrite the .db file and kill the
 * process). See Topics.BACKUP_RESTORE_REQUESTED for why.
 */
