package com.eevdf.scheduler.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.eevdf.scheduler.db.TaskDatabase
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Singleton that drives live multi-user sync by sharing a raw SQLite database
 * file in a user-chosen external folder (picked via ACTION_OPEN_DOCUMENT_TREE).
 *
 * ── Sync file layout ─────────────────────────────────────────────────────────
 *
 *  <sync_folder>/
 *    eevdf_sync.db        – the shared SQLite database (always a fully
 *                           self-contained, WAL-checkpointed snapshot)
 *    eevdf_sync_meta.json – lightweight metadata: version timestamp, exporter
 *                           deviceId, and all SharedPrefs settings so a fresh
 *                           install gets a perfect replica of the original app
 *
 * ── Access strategy (Tier 1 preferred, Tier 2 fallback) ──────────────────────
 *
 *  Tier 1 — Direct java.io.File access:
 *    The SAF tree URI's document ID (e.g. "primary:Download/Sync") is decoded
 *    to a real filesystem path via StorageManager / Environment.  Once we have
 *    a java.io.File we bypass ContentResolver entirely, enabling:
 *      • Random-access reads for page-level diffing (RandomAccessFile)
 *      • In-place partial writes (overwrite only changed 4 KB SQLite pages)
 *    Requires MANAGE_EXTERNAL_STORAGE on API 30+ or WRITE_EXTERNAL_STORAGE on
 *    API 26-29.  Both are requested in MultiUserSyncActivity before the user
 *    picks a folder.
 *
 *  Tier 2 — SAF ContentResolver streams:
 *    Used when direct path resolution fails (network drive, OTG mount, cloud
 *    provider, permission not granted).  Falls back to a full stream copy —
 *    the same behaviour as the original code.
 *
 * ── Export (local → sync folder) ─────────────────────────────────────────────
 *
 *  1. PRAGMA wal_checkpoint(TRUNCATE) — flush all WAL frames into the main .db
 *     file so the exported file is fully self-contained (no separate WAL needed
 *     by the receiver).  Room stays open; checkpoint does not close the DB.
 *
 *  2a. Direct mode — page-level diff copy:
 *      Open both the local DB and eevdf_sync.db with RandomAccessFile.  Read
 *      both files in PAGE_SIZE chunks.  For each page:
 *        • If the destination page equals the source page → skip (zero writes).
 *        • Otherwise → seek to the page offset and write the changed page.
 *      If the destination is shorter than the source, append the tail.
 *      If the destination is longer (DB shrank after a purge), truncate it.
 *      Result: only genuinely changed 4 KB blocks hit the storage medium.
 *
 *  2b. SAF mode — full stream copy (ContentResolver stream, no random access).
 *
 *  3. Write eevdf_sync_meta.json with current timestamp + this device's UUID
 *     + all SharedPrefs (minus device-specific sync keys).
 *
 *  4. Persist the meta version in local prefs so our own poll won't re-import.
 *
 * ── Import (sync folder → local) ─────────────────────────────────────────────
 *
 *  1. Read eevdf_sync_meta.json.
 *  2. If version == lastKnownVersion → nothing changed, skip.
 *  3. If deviceId == myDeviceId    → we wrote this, record version and skip.
 *  4. checkpointAndClose() — flush + close Room so no file locks remain.
 *  5. Copy eevdf_sync.db → local DB path (always a full copy; destination is
 *     private app storage so direct file access is always available here).
 *  6. Delete stale WAL / SHM files.
 *  7. Apply settings from the meta JSON.
 *  8. Persist new lastKnownVersion.
 *  9. Post importEvent → ViewModel → MainActivity restarts the process for a
 *     clean Room re-initialisation.
 *
 * ── Storage wear reduction summary ───────────────────────────────────────────
 *
 *  Old path: every export rewrites the entire DB (100 MB → 100 MB written).
 *  New path (Direct + page diff):
 *    • Typical session (few hundred rows touched) → ~200 KB written.
 *    • Heavy session  (1 000 rows touched)        → ~4 MB written.
 *    • Full purge / migration (whole DB changes)  → 100 MB written (same as old).
 *  Flash cells on the SD card / USB drive wear only for pages with real changes.
 *
 * ── Error safety ─────────────────────────────────────────────────────────────
 *
 *  Every operation is wrapped in try/catch.  On failure:
 *    • SyncState.Error is posted with a short message.
 *    • The app continues working normally.
 *    • The poll loop keeps running — errors are self-healing on next cycle.
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

    /**
     * SQLite page size used for the diff-copy loop.
     * Read at runtime from the DB file header (offset 16, big-endian uint16).
     * Room's default is 4096 bytes, so this fallback is almost always correct.
     */
    private const val SQLITE_DEFAULT_PAGE_SIZE = 4096
    private const val SQLITE_PAGE_SIZE_OFFSET  = 16

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

    // ── Write statistics ──────────────────────────────────────────────────────

    private val _writeStats = MutableLiveData<SyncWriteStats>(SyncWriteStats.EMPTY)
    /** Live per-export and cumulative write statistics for the settings UI. */
    val writeStats: LiveData<SyncWriteStats> = _writeStats

    // Session-level accumulators (reset when the process dies)
    @Volatile private var sessionExportCount:  Int  = 0
    @Volatile private var sessionBytesWritten: Long = 0L

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
        prefs().edit()
            .putString(PREFS_KEY_SYNC_URI, uri.toString())
            .remove(PREFS_KEY_LAST_VER)
            .apply()
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

    // ─────────────────────────────────────────────────────────────────────────
    // POLL
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun pollOnce() {
        val access = resolveSyncFolder() ?: return

        val metaJson = try {
            when (access) {
                is SyncAccess.Direct -> {
                    val f = File(access.dir, SYNC_META_FILE)
                    if (!f.exists()) return   // folder exists but nobody has exported yet
                    f.readText(Charsets.UTF_8)
                }
                is SyncAccess.Saf -> {
                    val metaFile = access.doc.findFile(SYNC_META_FILE) ?: return
                    readTextSaf(metaFile)
                }
            }
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

        // Nothing changed
        if (remoteVersion <= lastKnownVer) return

        // We wrote this export ourselves — stamp the version and skip
        if (remoteDeviceId == myDeviceId()) {
            prefs().edit().putLong(PREFS_KEY_LAST_VER, remoteVersion).apply()
            return
        }

        // Remote change detected — import
        try {
            _syncState.postValue(SyncState.Syncing)
            performImport(access, meta, remoteVersion)
        } catch (e: Exception) {
            _syncState.postValue(SyncState.Error("Import failed: ${short(e)}"))
            if (isEnabled()) startPolling()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IMPORT
    // ─────────────────────────────────────────────────────────────────────────

    private fun performImport(
        access: SyncAccess,
        meta: JSONObject,
        remoteVersion: Long
    ) {
        // Stop polling so we don't race during the file replace
        stopPolling()

        // Flush local WAL and close Room (releases all file locks)
        TaskDatabase.checkpointAndClose(appContext)

        val localDb  = TaskDatabase.getDatabaseFile(appContext)
        val localWal = File(localDb.path + "-wal")
        val localShm = File(localDb.path + "-shm")

        localDb.parentFile?.mkdirs()

        when (access) {
            is SyncAccess.Direct -> {
                val srcDb = File(access.dir, SYNC_DB_FILE)
                if (!srcDb.exists()) {
                    _syncState.postValue(SyncState.Error("Sync .db file missing in folder"))
                    if (isEnabled()) startPolling()
                    return
                }
                // Destination is private app storage — direct copy always works here
                srcDb.copyTo(localDb, overwrite = true)
            }
            is SyncAccess.Saf -> {
                val syncDb = access.doc.findFile(SYNC_DB_FILE) ?: run {
                    _syncState.postValue(SyncState.Error("Sync .db file missing in folder"))
                    if (isEnabled()) startPolling()
                    return
                }
                copyDocumentFileToLocal(syncDb, localDb)
            }
        }

        // Remove stale WAL / SHM so Room opens the new file cleanly
        localWal.delete()
        localShm.delete()

        applyRemoteSettings(meta)
        prefs().edit().putLong(PREFS_KEY_LAST_VER, remoteVersion).apply()

        _syncState.postValue(SyncState.OK)

        // Signal the ViewModel — app must restart for Room to open the new DB
        // with a clean singleton (same path as manual DB import)
        _importEvent.postValue(Unit)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EXPORT
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun performExport() {
        val access = resolveSyncFolder() ?: run {
            _syncState.postValue(SyncState.Error(
                "No sync folder — configure in Settings → Multiuser Sync"))
            return
        }

        val canWrite = when (access) {
            is SyncAccess.Direct -> access.dir.canWrite()
            is SyncAccess.Saf    -> access.doc.canWrite()
        }
        if (!canWrite) {
            _syncState.postValue(SyncState.Error("Sync folder is not writable"))
            return
        }

        try {
            _syncState.postValue(SyncState.Syncing)

            val localDb = TaskDatabase.getDatabaseFile(appContext)
            if (!localDb.exists()) {
                _syncState.postValue(SyncState.Error("Local database file not found"))
                return
            }

            // Checkpoint WAL into the main .db so the exported snapshot is fully
            // self-contained.  TRUNCATE: merges all WAL frames and resets the WAL
            // to zero length.  Room stays open after this — no close needed.
            TaskDatabase.checkpointWal(appContext)

            when (access) {
                is SyncAccess.Direct -> exportDirect(access.dir, localDb)
                is SyncAccess.Saf    -> exportSaf(access.doc, localDb)
            }

        } catch (e: Exception) {
            _syncState.postValue(SyncState.Error("Export failed: ${short(e)}"))
        }
    }

    /**
     * Direct-mode export: page-level diff copy to eevdf_sync.db.
     *
     * Algorithm:
     *   pageSize  = read from SQLite header (almost always 4096 bytes)
     *   srcLen    = local DB file size
     *   dstLen    = existing eevdf_sync.db size (0 if the file is new)
     *
     *   For offset = 0, pageSize, 2*pageSize, … until offset >= srcLen:
     *     chunkLen = min(pageSize, srcLen - offset)
     *     Read chunkLen bytes from src at offset.
     *     If offset + chunkLen <= dstLen:
     *       Read chunkLen bytes from dst at offset.
     *       If bytes differ → seek dst to offset, write src bytes.
     *       If bytes equal  → skip (no write issued).
     *     Else (dst is shorter than src):
     *       Seek dst to offset, write src bytes (append new tail).
     *
     *   After loop: if dstLen > srcLen → truncate dst to srcLen.
     *
     * Flash writes are proportional to changed data, not file size:
     *   100 MB DB, 100 KB changed → ~25 pages written (≈ 100 KB) instead of
     *   100 MB.
     */
    private fun exportDirect(syncDir: File, localDb: File) {
        val dstFile  = File(syncDir, SYNC_DB_FILE)
        val pageSize = readSqlitePageSize(localDb)
        val srcLen   = localDb.length()

        var pagesWritten = 0
        var pagesSkipped = 0
        var bytesWritten = 0L

        RandomAccessFile(localDb, "r").use { src ->
            if (!dstFile.exists()) dstFile.createNewFile()
            RandomAccessFile(dstFile, "rw").use { dst ->
                val dstLen  = dst.length()
                val srcPage = ByteArray(pageSize)
                val dstPage = ByteArray(pageSize)
                var offset  = 0L

                while (offset < srcLen) {
                    val chunkLen = minOf(pageSize.toLong(), srcLen - offset).toInt()

                    src.seek(offset)
                    src.readFully(srcPage, 0, chunkLen)

                    if (offset + chunkLen <= dstLen) {
                        dst.seek(offset)
                        dst.readFully(dstPage, 0, chunkLen)

                        var differs = false
                        for (i in 0 until chunkLen) {
                            if (srcPage[i] != dstPage[i]) { differs = true; break }
                        }
                        if (differs) {
                            dst.seek(offset)
                            dst.write(srcPage, 0, chunkLen)
                            pagesWritten++
                            bytesWritten += chunkLen
                        } else {
                            pagesSkipped++
                        }
                    } else {
                        dst.seek(offset)
                        dst.write(srcPage, 0, chunkLen)
                        pagesWritten++
                        bytesWritten += chunkLen
                    }

                    offset += chunkLen
                }

                if (dstLen > srcLen) {
                    dst.setLength(srcLen)
                }
            }
        }

        writeMetaDirect(syncDir, System.currentTimeMillis())

        // Update cumulative session counters and post stats
        sessionExportCount++
        sessionBytesWritten += bytesWritten
        _writeStats.postValue(
            SyncWriteStats(
                lastExportMs        = System.currentTimeMillis(),
                lastDbSizeBytes     = srcLen,
                lastBytesWritten    = bytesWritten,
                lastPagesWritten    = pagesWritten,
                lastPagesSkipped    = pagesSkipped,
                sessionExportCount  = sessionExportCount,
                sessionBytesWritten = sessionBytesWritten,
                accessMode          = "direct"
            )
        )
    }

    /**
     * SAF-mode export.  ContentResolver streams have no random-access support,
     * so we write the full file.  Used only when direct path resolution fails
     * (cloud provider, network drive, MANAGE_EXTERNAL_STORAGE not granted).
     */
    private fun exportSaf(folder: DocumentFile, localDb: File) {
        val version  = System.currentTimeMillis()
        val srcLen   = localDb.length()

        val syncDb = folder.findFile(SYNC_DB_FILE)
            ?: folder.createFile("application/octet-stream", SYNC_DB_FILE)
            ?: throw IOException("Cannot create sync .db file in folder")

        appContext.contentResolver.openOutputStream(syncDb.uri, "wt")
            ?.use { out -> localDb.inputStream().use { it.copyTo(out) } }
            ?: throw IOException("Cannot open sync DB for writing")

        val metaFile = folder.findFile(SYNC_META_FILE)
            ?: folder.createFile("application/json", SYNC_META_FILE)
            ?: throw IOException("Cannot create meta file in folder")

        writeTextSaf(metaFile, buildMetaJson(version).toString(2))
        prefs().edit().putLong(PREFS_KEY_LAST_VER, version).apply()

        // SAF always writes the full file; no page-diff stats available
        sessionExportCount++
        sessionBytesWritten += srcLen
        _writeStats.postValue(
            SyncWriteStats(
                lastExportMs        = System.currentTimeMillis(),
                lastDbSizeBytes     = srcLen,
                lastBytesWritten    = srcLen,
                lastPagesWritten    = -1,
                lastPagesSkipped    = -1,
                sessionExportCount  = sessionExportCount,
                sessionBytesWritten = sessionBytesWritten,
                accessMode          = "saf"
            )
        )
        _syncState.postValue(SyncState.OK)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FOLDER RESOLUTION — Direct vs SAF
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolves the persisted sync folder URI to:
     *  • SyncAccess.Direct — a real java.io.File usable with RandomAccessFile
     *  • SyncAccess.Saf    — a DocumentFile backed by ContentResolver streams
     *
     * The SAF document ID is formatted as "<volumeName>:<relativePath>", e.g.:
     *   "primary:Download/SyncFolder"  (internal storage)
     *   "ABCD-1234:SyncFolder"         (SD card / USB drive by UUID)
     *
     * API requirements for Direct mode:
     *   API 26-29: READ_EXTERNAL_STORAGE + WRITE_EXTERNAL_STORAGE
     *   API 30+  : MANAGE_EXTERNAL_STORAGE (requested at runtime in
     *              MultiUserSyncActivity before the folder is picked)
     */
    private fun resolveSyncFolder(): SyncAccess? {
        val uri = getSyncFolderUri() ?: run {
            _syncState.postValue(SyncState.Error(
                "No sync folder set — configure in Settings → Multiuser Sync"))
            return null
        }

        val directFile = resolveUriToFile(uri)
        if (directFile != null && directFile.exists()) {
            return SyncAccess.Direct(directFile)
        }

        val doc = DocumentFile.fromTreeUri(appContext, uri) ?: run {
            _syncState.postValue(SyncState.Error("Sync folder URI is invalid"))
            return null
        }
        if (!doc.exists()) {
            _syncState.postValue(SyncState.Error("Sync folder does not exist"))
            return null
        }
        if (!doc.canRead()) {
            _syncState.postValue(SyncState.Error("Sync folder is not accessible"))
            return null
        }
        return SyncAccess.Saf(doc)
    }

    /**
     * Decodes a SAF tree URI to a java.io.File.
     * URI path is: /tree/<encoded-docId>
     * Decoded docId looks like "primary:Download/Sync" or "ABCD-1234:path".
     * Returns null if resolution fails or the resolved path is not accessible.
     */
    private fun resolveUriToFile(treeUri: Uri): File? {
        return try {
            val rawPath = treeUri.path ?: return null
            val treePrefix = "/tree/"
            if (!rawPath.startsWith(treePrefix)) return null

            // Decode percent-encoding and strip any trailing /document/... segment
            val docId = Uri.decode(rawPath.removePrefix(treePrefix))
                .substringBefore("/document/")

            val colonIdx = docId.indexOf(':')
            if (colonIdx < 0) return null

            val volumeName   = docId.substring(0, colonIdx)
            val relativePath = docId.substring(colonIdx + 1)
                .replace('/', File.separatorChar)

            val root: File? = when {
                volumeName.equals("primary", ignoreCase = true) ->
                    Environment.getExternalStorageDirectory()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
                    val sm = appContext.getSystemService(Context.STORAGE_SERVICE)
                            as StorageManager
                    sm.storageVolumes
                        .firstOrNull { it.uuid?.equals(volumeName, ignoreCase = true) == true }
                        ?.let { vol ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                vol.directory
                            } else {
                                @Suppress("DEPRECATION")
                                vol.javaClass.getMethod("getPathFile").invoke(vol) as? File
                            }
                        }
                }
                else -> null
            }

            if (root == null) return null
            val resolved = if (relativePath.isEmpty()) root else File(root, relativePath)
            if (resolved.exists() || resolved.canRead()) resolved else null

        } catch (_: Exception) {
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SQLite page size helper
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads the page size from bytes 16-17 of the SQLite database header.
     * The value is a big-endian unsigned 16-bit integer; 1 encodes 65536.
     * Returns SQLITE_DEFAULT_PAGE_SIZE (4096) on any error.
     */
    private fun readSqlitePageSize(dbFile: File): Int {
        return try {
            RandomAccessFile(dbFile, "r").use { raf ->
                raf.seek(SQLITE_PAGE_SIZE_OFFSET.toLong())
                val hi = raf.read()
                val lo = raf.read()
                if (hi < 0 || lo < 0) return SQLITE_DEFAULT_PAGE_SIZE
                val size = (hi shl 8) or lo
                if (size == 1) 65536 else size  // SQLite spec: 1 means 65536
            }
        } catch (_: Exception) {
            SQLITE_DEFAULT_PAGE_SIZE
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Meta JSON
    // ─────────────────────────────────────────────────────────────────────────

    private fun writeMetaDirect(syncDir: File, version: Long) {
        File(syncDir, SYNC_META_FILE)
            .writeText(buildMetaJson(version).toString(2), Charsets.UTF_8)
        prefs().edit().putLong(PREFS_KEY_LAST_VER, version).apply()
        _syncState.postValue(SyncState.OK)
    }

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

    // ─────────────────────────────────────────────────────────────────────────
    // SAF I/O helpers (fallback path only)
    // ─────────────────────────────────────────────────────────────────────────

    private fun copyDocumentFileToLocal(src: DocumentFile, dst: File) {
        dst.parentFile?.mkdirs()
        appContext.contentResolver.openInputStream(src.uri)
            ?.use { input -> dst.outputStream().use { input.copyTo(it) } }
            ?: throw IOException("Cannot open remote DB for reading")
    }

    private fun readTextSaf(file: DocumentFile): String =
        appContext.contentResolver.openInputStream(file.uri)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Cannot read ${file.name}")

    private fun writeTextSaf(file: DocumentFile, text: String) {
        appContext.contentResolver.openOutputStream(file.uri, "wt")
            ?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IOException("Cannot write ${file.name}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────────────

    private fun prefs() =
        appContext.getSharedPreferences("eevdf_prefs", Context.MODE_PRIVATE)

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

    // ─────────────────────────────────────────────────────────────────────────
    // Sealed access type
    // ─────────────────────────────────────────────────────────────────────────

    private sealed class SyncAccess {
        /** Real filesystem path — RandomAccessFile available, zero ContentResolver overhead. */
        data class Direct(val dir: File) : SyncAccess()
        /** SAF DocumentFile — ContentResolver streams only, full copy on export. */
        data class Saf(val doc: DocumentFile) : SyncAccess()
    }
}
