package com.eevdf.data.backup

import com.eevdf.data.task.Task
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON (de)serialization of application data for the zip-based backup.
 *
 * A backup is a .zip containing:
 *   - database.db   : the raw SQLite file (authoritative, exact restore)
 *   - tasks.json     : a COMPLETE JSON export of every task (portable / inspectable)
 *   - settings.json  : all SharedPreferences settings (see SettingsBackup)
 *   - manifest.json  : self-describing metadata
 *
 * This object owns tasks.json + manifest.json. Unlike the previous version,
 * taskToJson/taskFromJson now serialize EVERY task field, so the JSON is a
 * faithful full round-trip (the old code dropped pinned share, quota, RT,
 * notification, and interrupt config).
 *
 * Live timer state (isRunning / accumulatedMs / startTimeEpoch) is intentionally
 * reset in a regular backup so a restore never resurrects a running timer; the
 * sync path (toSyncJson/fromSyncJson) preserves it.
 */
object BackupManager {

    const val BACKUP_VERSION = 2

    // ── tasks.json (complete export) ───────────────────────────────────────────

    fun exportTasksJson(tasks: List<Task>): String {
        val root = JSONObject()
        root.put("backupVersion", BACKUP_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        val arr = JSONArray()
        tasks.forEach { arr.put(taskToJson(it)) }
        root.put("tasks", arr)
        return root.toString(2)
    }

    fun importTasksJson(json: String): List<Task> {
        val arr = JSONObject(json).getJSONArray("tasks")
        return (0 until arr.length()).map { taskFromJson(arr.getJSONObject(it)) }
    }

    fun manifestJson(taskCount: Int): String = JSONObject().apply {
        put("format", "eevdf-backup")
        put("backupVersion", BACKUP_VERSION)
        put("exportedAt", System.currentTimeMillis())
        put("appVersion", "1.0")
        put("taskCount", taskCount)
        put("contents", JSONArray().apply {
            put("database.db"); put("tasks.json"); put("settings.json")
        })
    }.toString(2)

    // ── Complete task <-> JSON (used by tasks.json AND sync) ─────────────────────

    private fun taskToJson(t: Task): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("name", t.name)
        put("description", t.description)
        put("priority", t.priority)
        put("timeSliceSeconds", t.timeSliceSeconds)
        put("category", t.category)
        put("color", t.color)
        put("parentId", t.parentId ?: JSONObject.NULL)
        put("isGroup", t.isGroup)
        put("isGroupExpanded", t.isGroupExpanded)
        // EEVDF scheduler state
        put("vruntime", t.vruntime)
        put("eligibleTime", t.eligibleTime)
        put("virtualDeadline", t.virtualDeadline)
        put("lag", t.lag)
        // Timer / completion (live state reset for regular backup)
        put("remainingSeconds", t.remainingSeconds)
        put("isRunning", false)
        put("isCompleted", t.isCompleted)
        put("totalRunTime", t.totalRunTime)
        put("runCount", t.runCount)
        put("accumulatedMs", 0L)
        put("startTimeEpoch", 0L)
        // Interrupt slot config
        put("isInterrupt", t.isInterrupt)
        put("interruptSlot", t.interruptSlot)
        // Task type + notice/notification config
        put("taskType", t.taskType)
        put("notificationDelaySeconds", t.notificationDelaySeconds)
        put("notificationRestSeconds", t.notificationRestSeconds)
        put("notificationRepeatCount", t.notificationRepeatCount)
        put("notificationResumeType", t.notificationResumeType)
        // CPU-share pinning
        put("pinnedShare", t.pinnedShare ?: JSONObject.NULL)
        put("internalWeight", t.internalWeight ?: JSONObject.NULL)
        put("createdAt", t.createdAt)
        // Quota
        put("quotaSeconds", t.quotaSeconds)
        put("quotaPeriodSeconds", t.quotaPeriodSeconds)
        put("quotaPeriodStartEpoch", t.quotaPeriodStartEpoch)
        put("quotaUsedSeconds", t.quotaUsedSeconds)
        // Scheduler class + SCHED_DEADLINE
        put("schedulerClass", t.schedulerClass)
        put("dlRuntimeSeconds", t.dlRuntimeSeconds)
        put("dlDeadlineSeconds", t.dlDeadlineSeconds)
        put("dlPeriodSeconds", t.dlPeriodSeconds)
        put("dlPeriodStartEpoch", t.dlPeriodStartEpoch)
        put("dlRuntimeUsedSeconds", t.dlRuntimeUsedSeconds)
        // SCHED_FIFO / SCHED_RR
        put("rtPriority", t.rtPriority)
        put("rtPolicy", t.rtPolicy)
        put("rtActiveDays", t.rtActiveDays)
        put("rtActivationHour", t.rtActivationHour)
        put("rtActivationMinute", t.rtActivationMinute)
        put("rtActivationSecond", t.rtActivationSecond)
        put("rtSliceTimeoutSeconds", t.rtSliceTimeoutSeconds)
    }

    private fun taskFromJson(j: JSONObject): Task = Task(
        id = j.getString("id"),
        name = j.getString("name"),
        description = j.optString("description", ""),
        priority = j.getInt("priority"),
        timeSliceSeconds = j.getLong("timeSliceSeconds"),
        category = j.optString("category", "General"),
        color = j.optInt("color", 0),
        parentId = if (j.isNull("parentId")) null else j.optString("parentId"),
        isGroup = j.optBoolean("isGroup", false),
        isGroupExpanded = j.optBoolean("isGroupExpanded", true),
        vruntime = j.optDouble("vruntime", 0.0),
        eligibleTime = j.optDouble("eligibleTime", 0.0),
        virtualDeadline = j.optDouble("virtualDeadline", 0.0),
        lag = j.optDouble("lag", 0.0),
        remainingSeconds = j.optLong("remainingSeconds", j.optLong("timeSliceSeconds", 0L)),
        isRunning = false, // never restore a running timer
        isCompleted = j.optBoolean("isCompleted", false),
        totalRunTime = j.optLong("totalRunTime", 0L),
        runCount = j.optInt("runCount", 0),
        isInterrupt = j.optBoolean("isInterrupt", false),
        interruptSlot = j.optString("interruptSlot", "A"),
        taskType = j.optString("taskType", "DEFAULT"),
        notificationDelaySeconds = j.optLong("notificationDelaySeconds", 0L),
        notificationRestSeconds = j.optLong("notificationRestSeconds", 0L),
        notificationRepeatCount = j.optInt("notificationRepeatCount", 0),
        notificationResumeType = j.optString("notificationResumeType", "MIDDLE"),
        accumulatedMs = 0L,
        startTimeEpoch = 0L,
        pinnedShare = if (j.isNull("pinnedShare")) null else j.optDouble("pinnedShare"),
        internalWeight = if (j.isNull("internalWeight")) null else j.optDouble("internalWeight"),
        createdAt = j.optLong("createdAt", System.currentTimeMillis()),
        quotaSeconds = j.optLong("quotaSeconds", 0L),
        quotaPeriodSeconds = j.optLong("quotaPeriodSeconds", 86_400L),
        quotaPeriodStartEpoch = j.optLong("quotaPeriodStartEpoch", 0L),
        quotaUsedSeconds = j.optLong("quotaUsedSeconds", 0L),
        schedulerClass = j.optString("schedulerClass", "fair_sched_class"),
        dlRuntimeSeconds = j.optLong("dlRuntimeSeconds", 0L),
        dlDeadlineSeconds = j.optLong("dlDeadlineSeconds", 0L),
        dlPeriodSeconds = j.optLong("dlPeriodSeconds", 0L),
        dlPeriodStartEpoch = j.optLong("dlPeriodStartEpoch", 0L),
        dlRuntimeUsedSeconds = j.optLong("dlRuntimeUsedSeconds", 0L),
        rtPriority = j.optInt("rtPriority", 50),
        rtPolicy = j.optString("rtPolicy", "RR"),
        rtActiveDays = j.optInt("rtActiveDays", 0),
        rtActivationHour = j.optInt("rtActivationHour", 0),
        rtActivationMinute = j.optInt("rtActivationMinute", 0),
        rtActivationSecond = j.optInt("rtActivationSecond", 0),
        rtSliceTimeoutSeconds = j.optLong("rtSliceTimeoutSeconds", 0L),
    )

    // ── Multi-user sync (preserves live timer state) ─────────────────────────────

    data class BackupData(
        val tasks: List<Task>,
        val exportedAt: Long,
        val backupVersion: Int,
    )

    fun toSyncJson(tasks: List<Task>): String {
        val root = JSONObject()
        root.put("backupVersion", BACKUP_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("syncFormat", true)
        val arr = JSONArray()
        tasks.forEach { arr.put(taskToSyncJson(it)) }
        root.put("tasks", arr)
        return root.toString(2)
    }

    private fun taskToSyncJson(t: Task): JSONObject = taskToJson(t).apply {
        put("isRunning", t.isRunning)
        put("accumulatedMs", t.accumulatedMs)
        put("startTimeEpoch", t.startTimeEpoch)
    }

    fun fromSyncJson(json: String): BackupData {
        val root = JSONObject(json)
        val arr = root.getJSONArray("tasks")
        val tasks = (0 until arr.length()).map { taskFromSyncJson(arr.getJSONObject(it)) }
        return BackupData(tasks, root.optLong("exportedAt", 0L), root.optInt("backupVersion", BACKUP_VERSION))
    }

    private fun taskFromSyncJson(j: JSONObject): Task = taskFromJson(j).copy(
        isRunning = j.optBoolean("isRunning", false),
        accumulatedMs = j.optLong("accumulatedMs", 0L),
        startTimeEpoch = j.optLong("startTimeEpoch", 0L),
    )
}
