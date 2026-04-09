package com.eevdf.scheduler.backup

import com.eevdf.scheduler.model.Task
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts the full application state (tasks + settings) to/from a JSON string.
 *
 * Schema:
 * {
 *   "backupVersion": 1,
 *   "exportedAt": <epoch ms>,
 *   "appVersion": "1.0.0",
 *   "settings": {
 *     "groupsEnabled": <bool>,
 *     "globalRotateEnabled": <bool>,
 *     "allowEditEnabled": <bool>
 *   },
 *   "tasks": [ { ...all Task fields... }, ... ]
 * }
 */
object BackupManager {

    private const val BACKUP_VERSION = 1

    // ── Export ────────────────────────────────────────────────────────────────

    fun toJson(
        tasks: List<Task>,
        groupsEnabled: Boolean,
        globalRotateEnabled: Boolean,
        allowEditEnabled: Boolean
    ): String {
        val root = JSONObject()
        root.put("backupVersion", BACKUP_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("appVersion", "1.0.0")

        val settings = JSONObject()
        settings.put("groupsEnabled", groupsEnabled)
        settings.put("globalRotateEnabled", globalRotateEnabled)
        settings.put("allowEditEnabled", allowEditEnabled)
        root.put("settings", settings)

        val tasksArray = JSONArray()
        for (task in tasks) {
            tasksArray.put(taskToJson(task))
        }
        root.put("tasks", tasksArray)

        return root.toString(2) // pretty-print with 2-space indent
    }

    private fun taskToJson(t: Task): JSONObject = JSONObject().apply {
        put("id",               t.id)
        put("name",             t.name)
        put("description",      t.description)
        put("priority",         t.priority)
        put("timeSliceSeconds", t.timeSliceSeconds)
        put("category",         t.category)
        put("color",            t.color)
        put("parentId",         if (t.parentId != null) t.parentId else JSONObject.NULL)
        put("isGroup",          t.isGroup)
        put("isGroupExpanded",  t.isGroupExpanded)
        // EEVDF scheduler state
        put("vruntime",         t.vruntime)
        put("eligibleTime",     t.eligibleTime)
        put("virtualDeadline",  t.virtualDeadline)
        put("lag",              t.lag)
        // Timer state
        put("remainingSeconds", t.remainingSeconds)
        put("isRunning",        false) // always reset running state in backup
        put("isCompleted",      t.isCompleted)
        put("totalRunTime",     t.totalRunTime)
        put("runCount",         t.runCount)
        put("createdAt",        t.createdAt)
    }

    // ── Import ────────────────────────────────────────────────────────────────

    data class BackupData(
        val tasks: List<Task>,
        val groupsEnabled: Boolean,
        val globalRotateEnabled: Boolean,
        val allowEditEnabled: Boolean,
        val exportedAt: Long,
        val backupVersion: Int
    )

    /**
     * Parses a JSON string produced by [toJson].
     * @throws Exception if the JSON is malformed or missing required fields.
     */
    fun fromJson(json: String): BackupData {
        val root = JSONObject(json)

        val version = root.optInt("backupVersion", 1)

        val settings = root.optJSONObject("settings") ?: JSONObject()
        val groupsEnabled       = settings.optBoolean("groupsEnabled", false)
        val globalRotateEnabled = settings.optBoolean("globalRotateEnabled", false)
        val allowEditEnabled    = settings.optBoolean("allowEditEnabled", false)

        val tasksArray = root.getJSONArray("tasks")
        val tasks = mutableListOf<Task>()
        for (i in 0 until tasksArray.length()) {
            tasks.add(taskFromJson(tasksArray.getJSONObject(i)))
        }

        return BackupData(
            tasks               = tasks,
            groupsEnabled       = groupsEnabled,
            globalRotateEnabled = globalRotateEnabled,
            allowEditEnabled    = allowEditEnabled,
            exportedAt          = root.optLong("exportedAt", 0L),
            backupVersion       = version
        )
    }

    private fun taskFromJson(j: JSONObject): Task = Task(
        id               = j.getString("id"),
        name             = j.getString("name"),
        description      = j.optString("description", ""),
        priority         = j.getInt("priority"),
        timeSliceSeconds = j.getLong("timeSliceSeconds"),
        category         = j.optString("category", "General"),
        color            = j.optInt("color", 0),
        parentId         = if (j.isNull("parentId")) null else j.optString("parentId"),
        isGroup          = j.optBoolean("isGroup", false),
        isGroupExpanded  = j.optBoolean("isGroupExpanded", true),
        vruntime         = j.optDouble("vruntime", 0.0),
        eligibleTime     = j.optDouble("eligibleTime", 0.0),
        virtualDeadline  = j.optDouble("virtualDeadline", 0.0),
        lag              = j.optDouble("lag", 0.0),
        remainingSeconds = j.optLong("remainingSeconds", j.optLong("timeSliceSeconds", 0L)),
        isRunning        = false, // never restore a running state
        isCompleted      = j.optBoolean("isCompleted", false),
        totalRunTime     = j.optLong("totalRunTime", 0L),
        runCount         = j.optInt("runCount", 0),
        createdAt        = j.optLong("createdAt", System.currentTimeMillis())
    )
}
