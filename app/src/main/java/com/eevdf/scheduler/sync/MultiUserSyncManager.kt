package com.eevdf.scheduler.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.eevdf.scheduler.db.TaskDatabase
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Singleton that drives live multi-user sync by sharing a raw SQLite database
 * file in a user-chosen external folder (picked via ACTION_OPEN_DOCUMENT_TREE).
 *
 * ── Sync file layout ─────────────────────────────────────────────────────────
 *
 *  <sync_folder>/
 *    eevdf_sync.db          – binary copy of the Room database (ALL tables:
 *                             tasks, run_log, quotas, everything)
 *    eevdf_sync_meta.json   – lightweight metadata: version timestamp, exporter
 *                             deviceId, and all SharedPrefs settings so a fresh
 *                             install gets a perfect replica of the original app
 *
 * ── Export (local → folder) ──────────────────────────────────────────────────
 *
 *  1. PRAGMA wal_checkpoint(TRUNCATE) — flush WAL into main .db file (no close)
 *  2. Stream-copy local .db file → eevdf_sync.db in the shared folder
 *  3. Write eevdf_sync_meta.json with current timestamp + this device's UUID
 *     + all SharedPrefs (minus device-specific sync keys)
 *  4. Persist the meta version in local prefs so we can detect remote changes
 *
 * ── Import (folder → local) ──────────────────────────────────────────────────
 *
 *  1. Read eevdf_sync_meta.json
 *  2. If version == lastKnownVersion → nothing changed, skip
 *  3. If deviceId == myDeviceId    → we wrote this, skip
 *  4. checkpointAndClose() — flush + close Room so no file locks remain
 *  5. Copy eevdf_sync.db → local DB path (replaces existing DB)
 *  6. Delete stale WAL / SHM files
 *  7. Apply settings from the meta JSON
 *  8. Persist new lastKnownVersion
 *  9. Post [importEvent] → ViewModel → MainActivity restarts the process for a
 *     clean Room re-initialisation (same path as manual DB restore)
 *
 * ── Error safety ─────────────────────────────────────────────────────────────
 *
 *  Every operation is wrapped in try/catch.  On failure:
 *    • [SyncState.Error] is posted with a short message
 *    • The app continues working normally
 *    • The poll loop keeps running — errors are self-healing on next cycle
 */
object MultiUserSyncManager {

    // ── Prefs keys ────────────────────────────────────────────────────────────
    const val PREFS_KEY_SYNC_URI          = "multiuser_sync_uri"
    const val PREFS_KEY_SYNC_ENABLED      = "multiuser_sync_enabled"
    private const val PREFS_KEY_DEVICE_ID = "multiuser_sync_device_id"
    private const val PREFS_KEY_LAST_VER  = "multiuser_sync_last_version"

    // Settings prefs keys that are device-specific and must NOT be synced
    private val EXCLUDED_SETTING_KEYS = setOf(
        PREFS_KEY_SYNC_URI, PREFS_KEY_SYNC_ENABLED,
        PREFS_KEY_DEVICE_ID, PREFS_KEY_LAST_VER
    )

    // ── Sync file names ───────────────────────────────────────────────────────
    private const val SYNC_DB_FILE   = "eevdf_sync.db"
    private const val SYNC_META_FILE = "eevdf_sync_meta.json"

    // ── Timing ────────────────────────────────────────────────────────────────
    private const val POLL_INTERVAL_MS   = 3_000L
    private const val EXPORT_DEBOUNCE_MS = 800L

    // ── Public observable state ───────────────────────────────────────────────

    private val _syncState = MutableLiveData<SyncState>(SyncState.Disabled)
    val syncState: LiveData<SyncState> = _syncState

    /**
     * Fires once after a remote import completes and the local DB has been
     * replaced.  The ViewModel must trigger an app restart so Room re-opens
     * the new file cleanly.
     */
    private val _importEvent = MutableLiveData<Unit>()
    val importEvent: LiveData<Unit> = _importEvent

    // ── Internal state ────────────────────────────────────────────────────────
    private var scope:     CoroutineScope? = null
    private var pollJob:   Job? = null
    private var exportJob: Job? = null

    private lateinit var appContext: Context

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun init(context: Context) {
        appContext = context.applicationContext
        if (isEnabled()) {
            _syncState.postValue(SyncState.Idle)
            startPolling()
        } else {
            _syncState.postValue(SyncState.Disabled)
        }
    }

    fun onResume() {
        if (isEnabled() && pollJob?.isActive != true) startPolling()
    }

    // ── Settings accessors ────────────────────────────────────────────────────

    fun isEnabled(): Boolean =
        prefs().getBoolean(PREFS_KEY_SYNC_ENABLED, false)

    fun getSyncFolderUri(): Uri? {
        val s = prefs().getString(PREFS_KEY_SYNC_URI, null) ?: return null
        return try { Uri.parse(s) } catch (_: Exception) { null }
    }

    fun setSyncFolder(uri: Uri) {
        prefs().edit().putString(PREFS_KEY_SYNC_URI, uri.toString()).apply()
        // Reset version so we immediately check the folder on next poll
        prefs().edit().remove(PREFS_KEY_LAST_VER).apply()
    }

    fun setEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(PREFS_KEY_SYNC_ENABLED, enabled).apply()
        if (enabled) {
            _syncState.postValue(SyncState.Idle)
            prefs().edit().remove(PREFS_KEY_LAST_VER).apply()
            startPolling()
        } else {
            stopPolling()
            _syncState.postValue(SyncState.Disabled)
        }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Schedules a debounced export to the sync folder.
     * Call after any local DB write (timer start/stop, task add/edit/delete).
     * Also callable directly for an immediate "sync now" via toolbar tap.
     */
    fun scheduleExport() {
        if (!isEnabled()) return
        val s = scope ?: return
        exportJob?.cancel()
        exportJob = s.launch {
            delay(EXPORT_DEBOUNCE_MS)
            performExport()
        }
    }

    // ── Private: polling & IO ─────────────────────────────────────────────────

    private fun startPolling() {
        if (scope?.isActive != true) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        pollJob?.cancel()
        pollJob = scope!!.launch {
            while (isActive) {
                try { pollOnce() } catch (e: Exception) {
                    _syncState.postValue(SyncState.Error(short(e)))
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        exportJob?.cancel()
        pollJob   = null
        exportJob = null
        scope?.cancel()
        scope = null
    }

    /** Single poll cycle: check meta file, import if remote version is newer. */
    private suspend fun pollOnce() {
        val folder = resolveFolder() ?: return

        val metaFile = folder.findFile(SYNC_META_FILE) ?: return  // not yet created by anyone

        val metaJson = try {
            readText(metaFile)
        } catch (e: Exception) {
            _syncState.postValue(SyncState.Error("Meta read error: ${short(e)}"))
            return
        }

        val meta = try { JSONObject(metaJson) } catch (_: Exception) {
            _syncState.postValue(SyncState.Error("Meta parse error — file may be corrupt"))
            return
        }

        val remoteVersion  = meta.optLong("version",  0L)
        val remoteDeviceId = meta.optString("deviceId", "")
        val lastKnownVer   = prefs().getLong(PREFS_KEY_LAST_VER, 0L)

        // Nothing changed or we wrote this ourselves — nothing to do
        if (remoteVersion <= lastKnownVer) return
        if (remoteDeviceId == myDeviceId()) {
            // Our own export landed; just update the local version stamp and move on
            prefs().edit().putLong(PREFS_KEY_LAST_VER, remoteVersion).apply()
            return
        }

        // ── Remote change detected — import ───────────────────────────────────
        val syncDb = folder.findFile(SYNC_DB_FILE) ?: run {
            _syncState.postValue(SyncState.Error("Sync .db file missing in folder"))
            return
        }

        try {
            _syncState.postValue(SyncState.Syncing)

            // Stop polling so we don't race during the file replace
            stopPolling()

            // 1. Flush local WAL and close Room (releases all file locks)
            TaskDatabase.checkpointAndClose(appContext)

            // 2. Copy remote DB file → local app DB path
            val localDb = TaskDatabase.getDatabaseFile(appContext)
            copyDocumentFileToLocal(syncDb, localDb)

            // 3. Remove stale WAL / SHM so Room opens the new file cleanly
            File(localDb.path + "-wal").delete()
            File(localDb.path + "-shm").delete()

            // 4. Apply settings from the meta JSON (groups, profile, quota, etc.)
            applyRemoteSettings(meta)

            // 5. Persist the new version — we won't re-import this on restart
            prefs().edit().putLong(PREFS_KEY_LAST_VER, remoteVersion).apply()

            _syncState.postValue(SyncState.OK)

            // 6. Signal the ViewModel — the app must restart for Room to open the
            //    new DB file with a clean singleton (same path as manual DB import)
            _importEvent.postValue(Unit)

        } catch (e: Exception) {
            _syncState.postValue(SyncState.Error("Import failed: ${short(e)}"))
            // Restart polling so the error can self-heal
            if (isEnabled()) startPolling()
        }
    }

    private suspend fun performExport() {
        val folder = resolveFolder() ?: run {
            _syncState.postValue(SyncState.Error("No sync folder — configure in Settings → Multiuser Sync"))
            return
        }
        if (!folder.canWrite()) {
            _syncState.postValue(SyncState.Error("Sync folder is not writable"))
            return
        }

        try {
            _syncState.postValue(SyncState.Syncing)

            // 1. Checkpoint WAL into main .db file (no close — Room stays open)
            TaskDatabase.checkpointWal(appContext)

            val localDb = TaskDatabase.getDatabaseFile(appContext)
            if (!localDb.exists()) {
                _syncState.postValue(SyncState.Error("Local database file not found"))
                return
            }

            // 2. Copy local DB → eevdf_sync.db in shared folder
            val syncDb = folder.findFile(SYNC_DB_FILE)
                ?: folder.createFile("application/octet-stream", SYNC_DB_FILE)
                ?: run {
                    _syncState.postValue(SyncState.Error("Cannot create sync .db file in folder"))
                    return
                }

            copyLocalToDocumentFile(localDb, syncDb)

            // 3. Write meta file with version + deviceId + all settings
            val version = System.currentTimeMillis()
            val meta    = buildMetaJson(version)

            val metaFile = folder.findFile(SYNC_META_FILE)
                ?: folder.createFile("application/json", SYNC_META_FILE)
                ?: run {
                    _syncState.postValue(SyncState.Error("Cannot create meta file in folder"))
                    return
                }

            writeText(metaFile, meta.toString(2))

            // 4. Record this version so our own poll won't trigger an import
            prefs().edit().putLong(PREFS_KEY_LAST_VER, version).apply()

            _syncState.postValue(SyncState.OK)

        } catch (e: Exception) {
            _syncState.postValue(SyncState.Error("Export failed: ${short(e)}"))
        }
    }

    // ── Settings sync ─────────────────────────────────────────────────────────

    /**
     * Builds the meta JSON:
     * {
     *   "version":  <epoch ms>,
     *   "deviceId": "<uuid>",
     *   "settings": {
     *     "<key>": { "t": "b|s|i|l|f", "v": <value> },
     *     ...
     *   }
     * }
     * All SharedPrefs keys except device-specific sync keys are included.
     */
    private fun buildMetaJson(version: Long): JSONObject {
        val root = JSONObject()
        root.put("version",  version)
        root.put("deviceId", myDeviceId())

        val settingsObj = JSONObject()
        @Suppress("UNCHECKED_CAST")
        val all = prefs().all as Map<String, Any?>
        for ((key, value) in all) {
            if (key in EXCLUDED_SETTING_KEYS || value == null) continue
            val entry = JSONObject()
            when (value) {
                is Boolean -> { entry.put("t", "b"); entry.put("v", value) }
                is String  -> { entry.put("t", "s"); entry.put("v", value) }
                is Int     -> { entry.put("t", "i"); entry.put("v", value) }
                is Long    -> { entry.put("t", "l"); entry.put("v", value) }
                is Float   -> { entry.put("t", "f"); entry.put("v", value.toDouble()) }
                else       -> continue
            }
            settingsObj.put(key, entry)
        }
        root.put("settings", settingsObj)
        return root
    }

    /** Writes all settings from a remote meta JSON to local SharedPrefs. */
    private fun applyRemoteSettings(meta: JSONObject) {
        val settings = meta.optJSONObject("settings") ?: return
        val edit = prefs().edit()
        val keys = settings.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in EXCLUDED_SETTING_KEYS) continue
            val entry = settings.optJSONObject(key) ?: continue
            try {
                when (entry.getString("t")) {
                    "b" -> edit.putBoolean(key, entry.getBoolean("v"))
                    "s" -> edit.putString(key,  entry.getString("v"))
                    "i" -> edit.putInt(key,     entry.getInt("v"))
                    "l" -> edit.putLong(key,    entry.getLong("v"))
                    "f" -> edit.putFloat(key,   entry.getDouble("v").toFloat())
                }
            } catch (_: Exception) { /* skip malformed entry */ }
        }
        edit.apply()
    }

    // ── IO helpers ────────────────────────────────────────────────────────────

    private fun resolveFolder(): DocumentFile? {
        val uri = getSyncFolderUri() ?: run {
            _syncState.postValue(SyncState.Error("No sync folder set — configure in Settings → Multiuser Sync"))
            return null
        }
        val folder = DocumentFile.fromTreeUri(appContext, uri) ?: run {
            _syncState.postValue(SyncState.Error("Sync folder URI is invalid"))
            return null
        }
        if (!folder.exists()) {
            _syncState.postValue(SyncState.Error("Sync folder does not exist"))
            return null
        }
        if (!folder.canRead()) {
            _syncState.postValue(SyncState.Error("Sync folder is not accessible"))
            return null
        }
        return folder
    }

    private fun copyDocumentFileToLocal(src: DocumentFile, dst: File) {
        dst.parentFile?.mkdirs()
        appContext.contentResolver.openInputStream(src.uri)
            ?.use { input -> dst.outputStream().use { input.copyTo(it) } }
            ?: throw IOException("Cannot open remote DB for reading")
    }

    private fun copyLocalToDocumentFile(src: File, dst: DocumentFile) {
        appContext.contentResolver.openOutputStream(dst.uri, "wt")
            ?.use { output -> src.inputStream().use { it.copyTo(output) } }
            ?: throw IOException("Cannot open sync DB for writing")
    }

    private fun readText(file: DocumentFile): String =
        appContext.contentResolver.openInputStream(file.uri)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Cannot read ${file.name}")

    private fun writeText(file: DocumentFile, text: String) {
        appContext.contentResolver.openOutputStream(file.uri, "wt")
            ?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IOException("Cannot write ${file.name}")
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun prefs() =
        appContext.getSharedPreferences("eevdf_prefs", Context.MODE_PRIVATE)

    /** Returns (or generates) a stable UUID for this installation. */
    private fun myDeviceId(): String {
        var id = prefs().getString(PREFS_KEY_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs().edit().putString(PREFS_KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    private fun short(e: Exception): String =
        e.message?.take(80) ?: e.javaClass.simpleName
}
