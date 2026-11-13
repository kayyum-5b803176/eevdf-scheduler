package com.eevdf.capabilities.multidevicesync.logic

/**
 * The little bit of state the synced phones share that is not a task: which
 * alarm is ringing or was stopped, and the group-wide ring settings.
 *
 * Two ordinary tables in the app database, created and owned by this
 * capability (Room ignores tables that are not its entities, so no Room
 * migration is involved). Because they are ordinary tables, the sync engine
 * discovers and merges them like any other: a "stopped" written on one phone
 * reaches the others by exactly the same road as a task change.
 *
 *   shared_alarm(id PK, taskId, taskName, expiredAt, state, stoppedAt, stoppedBy)
 *   shared_setting(k PK, v)
 *
 * An alarm's id is the task id plus the start time of the run that expired,
 * so every phone that saw the same run expire names it the same way without
 * talking to the others first.
 */
internal class SharedState(private val db: SyncDb) {

    data class AlarmRow(
        val id: String,
        val taskId: String,
        val taskName: String,
        val expiredAt: Long,
        val stopped: Boolean,
        val stoppedBy: String?,
    )

    /** The run that is counting down right now, as the shared task data describes it. */
    data class RunningTask(
        val taskId: String,
        val name: String,
        val taskType: String,
        val startTimeEpoch: Long,
        val accumulatedMs: Long,
        val timeSliceSeconds: Long,
    ) {
        val alarmId: String get() = alarmIdOf(taskId, startTimeEpoch)

        fun remainingSeconds(nowMs: Long): Long =
            timeSliceSeconds - (accumulatedMs + (nowMs - startTimeEpoch).coerceAtLeast(0L)) / 1000L
    }

    fun ensure() {
        db.exec(
            "CREATE TABLE IF NOT EXISTS shared_alarm(id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, " +
                "taskName TEXT NOT NULL, expiredAt INTEGER NOT NULL, state TEXT NOT NULL, " +
                "stoppedAt INTEGER NOT NULL DEFAULT 0, stoppedBy TEXT)"
        )
        db.exec("CREATE TABLE IF NOT EXISTS shared_setting(k TEXT NOT NULL PRIMARY KEY, v TEXT)")
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun get(key: String): String? = db.queryString("SELECT v FROM shared_setting WHERE k = ?", listOf(key))

    /** Writes only if the value changes, so re-saving the same choice costs nothing anywhere. */
    fun set(key: String, value: String?) {
        if (get(key) == value) return
        db.exec("INSERT OR REPLACE INTO shared_setting(k, v) VALUES(?, ?)", listOf(key, value))
    }

    fun ringMode(): RingMode = RingMode.parse(get(KEY_RING_MODE))
    fun setRingMode(mode: RingMode) = set(KEY_RING_MODE, mode.key)

    /** The phone that last started, paused or changed a timer: (phone id, name). */
    fun ringOwner(): Pair<String?, String?> = get(KEY_RING_OWNER) to get(KEY_RING_OWNER_NAME)

    fun setRingOwner(deviceId: String, name: String) {
        db.transaction {
            set(KEY_RING_OWNER, deviceId)
            set(KEY_RING_OWNER_NAME, name)
        }
    }

    // ── Alarms ────────────────────────────────────────────────────────────────

    fun alarm(id: String): AlarmRow? = db.queryRows(
        "SELECT id, taskId, taskName, expiredAt, state, stoppedBy FROM shared_alarm WHERE id = ?", listOf(id)
    ).firstOrNull()?.let { r ->
        AlarmRow(
            id = r["id"].toString(),
            taskId = r["taskId"].toString(),
            taskName = r["taskName"].toString(),
            expiredAt = (r["expiredAt"] as Number).toLong(),
            stopped = r["state"] == STATE_STOPPED,
            stoppedBy = r["stoppedBy"]?.toString(),
        )
    }

    /**
     * Records that this alarm is ringing — unless any phone already recorded
     * it (then nothing is written, so two phones expiring together do not
     * overwrite each other, and a "stopped" can never be turned back into
     * "ringing").
     */
    fun raise(id: String, taskId: String, taskName: String, expiredAt: Long) {
        db.exec(
            "INSERT OR IGNORE INTO shared_alarm(id, taskId, taskName, expiredAt, state) VALUES(?, ?, ?, ?, ?)",
            listOf(id, taskId, taskName, expiredAt, STATE_RINGING)
        )
    }

    /** Marks the alarm stopped. Returns false if it already was (nothing written). */
    fun stop(id: String, taskId: String, taskName: String, at: Long, by: String): Boolean {
        val existing = alarm(id)
        if (existing?.stopped == true) return false
        db.transaction {
            raise(id, taskId, taskName, at)
            db.exec(
                "UPDATE shared_alarm SET state = ?, stoppedAt = ?, stoppedBy = ? WHERE id = ? AND state != ?",
                listOf(STATE_STOPPED, at, by, id, STATE_STOPPED)
            )
        }
        return true
    }

    /** Forgets alarms older than [olderThanMs]; the deletions travel like any change. */
    fun prune(olderThanMs: Long): Int {
        val n = db.queryLong("SELECT COUNT(*) FROM shared_alarm WHERE expiredAt < ?", listOf(olderThanMs)) ?: 0L
        if (n > 0) db.exec("DELETE FROM shared_alarm WHERE expiredAt < ?", listOf(olderThanMs))
        return n.toInt()
    }

    // ── The running task, read by column name (absent columns → null) ────────

    fun runningTask(): RunningTask? {
        val t = SyncSchema.describe(db, "tasks") ?: return null
        val needed = listOf("id", "name", "isRunning", "startTimeEpoch", "accumulatedMs", "timeSliceSeconds")
        if (!needed.all { t.column(it) != null }) return null
        val type = if (t.column("taskType") != null) "taskType" else "'DEFAULT'"
        val r = db.queryRows(
            "SELECT id, name, $type AS taskType, startTimeEpoch, accumulatedMs, timeSliceSeconds FROM tasks " +
                "WHERE isRunning = 1 AND startTimeEpoch > 0 ORDER BY startTimeEpoch DESC LIMIT 1"
        ).firstOrNull() ?: return null
        return RunningTask(
            taskId = r["id"].toString(),
            name = r["name"].toString(),
            taskType = r["taskType"]?.toString() ?: "DEFAULT",
            startTimeEpoch = (r["startTimeEpoch"] as Number).toLong(),
            accumulatedMs = (r["accumulatedMs"] as Number).toLong(),
            timeSliceSeconds = (r["timeSliceSeconds"] as Number).toLong(),
        )
    }

    companion object {
        const val KEY_RING_MODE = "ring_mode"
        const val KEY_RING_OWNER = "ring_owner"
        const val KEY_RING_OWNER_NAME = "ring_owner_name"
        private const val STATE_RINGING = "ringing"
        private const val STATE_STOPPED = "stopped"

        fun alarmIdOf(taskId: String, runStartEpoch: Long): String = "$taskId@$runStartEpoch"
    }
}

/** How the synced phones ring when a timer ends. One setting for the whole group. */
internal enum class RingMode(val key: String) {
    /** Every phone rings; stopping one stops them all. */
    SWARM("swarm"),
    /** Only the phone that last started, paused or changed a timer rings. */
    HANDOVER("handover");

    companion object {
        fun parse(value: String?): RingMode = entries.firstOrNull { it.key == value } ?: SWARM
    }
}

/**
 * The ring decision, kept free of everything else so every case is tested.
 * Errs on the side of ringing: a phone stays silent only when it is sure
 * another phone is ringing instead.
 */
internal object RingPolicy {
    /**
     * @param alarmStoppedElsewhere the shared record says this very alarm was already stopped
     * @param ownerReachable there is a live link to the owner phone right now
     */
    fun shouldRing(
        mode: RingMode,
        myDevice: String?,
        ownerDevice: String?,
        ownerReachable: Boolean,
        alarmStoppedElsewhere: Boolean,
    ): Boolean {
        if (alarmStoppedElsewhere) return false
        if (mode == RingMode.SWARM) return true
        if (ownerDevice == null || myDevice == null || ownerDevice == myDevice) return true
        // Handover, and the other phone was used last: stay silent only if it
        // can actually be reached to ring (and to tell us when it is stopped).
        return !ownerReachable
    }
}
