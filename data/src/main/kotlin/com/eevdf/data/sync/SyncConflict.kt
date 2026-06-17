package com.eevdf.data.sync

/**
 * Describes a single field-level conflict detected during an incoming sync.
 *
 * A conflict is raised when a remote DB snapshot would overwrite a local task's
 * meaningful data with a blank / null / zero value, OR when a task that exists
 * locally has been deleted on the remote side.
 *
 * The sync engine surfaces a list of these through [SyncState.ConflictPending]
 * so the UI can show a human-readable warning before the user decides whether
 * to accept the import or skip it.
 */
data class SyncConflict(
    /** The task's database id. */
    val taskId: String,

    /** Human-readable task name (from the local copy, since remote may have it blank). */
    val taskName: String,

    /** Machine-readable conflict category — used to group items in the warning dialog. */
    val kind: Kind,

    /**
     * Optional field name that triggered the conflict (e.g. "name", "description").
     * Null when [kind] is [Kind.TASK_DELETED].
     */
    val fieldName: String? = null,

    /**
     * String representation of the LOCAL value that would be overwritten.
     * Null when [kind] is [Kind.TASK_DELETED].
     */
    val localValue: String? = null,

    /**
     * String representation of the REMOTE (incoming) value — blank / null / 0.
     * Null when [kind] is [Kind.TASK_DELETED].
     */
    val remoteValue: String? = null,
) {
    enum class Kind {
        /**
         * A field that has a real value locally is blank / null / zero in the
         * incoming snapshot (e.g. remote user cleared the task name, deleted the
         * description, zeroed out the time slice).
         */
        FIELD_BLANKED,

        /**
         * The task exists in the local DB but is absent from the remote snapshot
         * entirely — it was deleted on another device.
         */
        TASK_DELETED,
    }

    /** One-line summary suitable for a list item in the warning dialog. */
    fun summary(): String = when (kind) {
        Kind.TASK_DELETED  -> "\"$taskName\" — deleted on remote device"
        Kind.FIELD_BLANKED -> "\"$taskName\" › $fieldName: " +
            "\"$localValue\" → ${if (remoteValue.isNullOrBlank()) "(blank)" else "\"$remoteValue\""}"
    }
}
