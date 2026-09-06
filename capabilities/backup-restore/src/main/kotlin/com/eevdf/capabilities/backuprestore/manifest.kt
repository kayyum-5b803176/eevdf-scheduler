package com.eevdf.capabilities.backuprestore

import com.eevdf.kernel.eventbus.CapabilityManifest
import com.eevdf.kernel.eventbus.Topic
import com.eevdf.kernel.eventbus.Topics

object BackupRestoreManifest : CapabilityManifest {
    override val capabilityId = "backup-restore"
    override val publishes: Set<Topic<*>> = setOf(Topics.BACKUP_EXPORT_REQUESTED, Topics.BACKUP_IMPORT_REQUESTED)
    override val subscribes: Set<Topic<*>> = emptySet()

    override fun fallbackWhenUnavailable(topic: Topic<*>) {
        // No-op: backup-restore only ever publishes. It holds no reference
        // to any other capability — the direct TaskViewModel call that used
        // to live here was the violation named in the redesign spec §4, and
        // it is gone as of Phase 6.
    }
}
