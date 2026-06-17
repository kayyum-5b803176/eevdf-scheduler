package com.eevdf.data.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.eevdf.data.task.TaskDatabase
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
 *    eevdf_sync.db        – shared SQLite snapshot (WAL-checkpointed, self-contained)
 *    eevdf_sync_meta.json – version timestamp, exporter deviceId, SharedPrefs snapshot
 *
 * ── File access model ────────────────────────────────────────────────────────
 *
 *  Export (local → external):
 *    The SAF tree URI is decoded to a real java.io.File path so we open
 *    eevdf_sync.db directly with RandomAccessFile — same as any text file
 *    access in the app.  No ContentResolver, no SAF stream involved.
 *    If the path cannot be resolved (permission not granted, cloud provider)
 *    the export fails with an actionable error; there is no silent full-copy
 *    fallback because a full-copy fallback would defeat the entire purpose.
 *
 *  Import (external → local):
 *    The external file is read via SAF ContentResolver into the app's private
 *    DB directory, which is always writable by the app without any permission.
 *    A full copy is fine here — destination is internal flash, not the SD card.
 *
 * ── Export detail ─────────────────────────────────────────────────────────────
 *
 *  1. PRAGMA wal_checkpoint(TRUNCATE) — merge WAL into main .db, Room stays open.
 *  2. Open local DB with RandomAccessFile("r").
 *  3. Open eevdf_sync.db on external storage with RandomAccessFile("rw").
 *     Create file if it does not exist yet.
 *  4. Page-diff loop (page size read from SQLite header at offset 16):
 *       For each 4 KB page:
 *         • Read page from local DB.
 *         • If destination already has this page: read it back and compare.
 *           Equal   → skip entirely (zero writes to external flash).
 *           Differs → seek dst to offset, write only that page.
 *         • If destination is shorter: append the page.
 *       After loop: if destination is longer (DB shrank) → truncate.
 *  5. Write eevdf_sync_meta.json (plain File.writeText — direct, no SAF).
 *  6. Post SyncWriteStats LiveData with bytes/pages written vs skipped.
 *
 * ── Import detail ─────────────────────────────────────────────────────────────
 *
 *  1. Read eevdf_sync_meta.json (direct File.readText if path resolved,
 *     SAF InputStream otherwise — meta is tiny so the difference is negligible).
 *  2. Skip if version ≤ lastKnownVersion or deviceId == myDeviceId.
 *  3. checkpointAndClose() — flush WAL and close Room.
 *  4. Stream-copy eevdf_sync.db → local DB path via SAF or direct File.copyTo.
 *  5. Delete stale local WAL / SHM.
 *  6. Apply remote SharedPrefs.  Post importEvent → app restart.
 *
 * ── Overwrite-conflict guard ─────────────────────────────────────────────────
 *
 *  Before any import is applied, [SyncFieldGuard.detectConflicts] compares the
 *  incoming snapshot against the live local task table:
 *
 *    AUTO-SYNC  — remote adds or updates a field with real content → apply.
 *    WARN+BLOCK — remote would blank/null a locally-populated field, or a task
 *                 that exists locally is absent from the remote snapshot (deleted
 *                 on the other device).
 *
 *  On conflict the import is blocked and [SyncState.ConflictPending] is posted.
 *  The poll loop pauses so the same snapshot is not re-flagged repeatedly.
 *  The UI calls [forceAcceptPendingImport] or [skipPendingImport] to resume.
 *
 * ── Error safety ─────────────────────────────────────────────────────────────
 *
 *  All operations are try/catch wrapped.  On failure SyncState.Error is posted
 *  and the poll loop keeps running (self-healing on next cycle).
 */
object MultiUserSyncManager {

    // ── Prefs keys ────────────────────────────────────────────────────────────
    const val PREFS_KEY_SYNC_URI          = "multiuser_sync_uri"
    const val PREFS_KEY_SYNC_ENABLED      = "multiuser_sync_enabled"
    private const val PREFS_KEY_DEVICE_ID = "multiuser_sync_device_id"
    private const val PREFS_KEY_LAST_VER  = "multiuser_sync_last_version"

    private val EXCLUDED_SETTING_KEYS = setOf(
        PREFS_KEY_SYNC_URI, PREFS_KEY_SYNC_ENABLED,
        PREFS_KEY_DEVICE_ID, PREFS_KEY_LAST_VER
    )

    // ── Sync file names ───────────────────────────────────────────────────────
    private const val SYNC_DB_FILE   = "eevdf_sync.db"
    private const val SYNC_META_FILE = "eevdf_sync_meta.json"

    // SQLite header stores page size at offset 16 as big-endian uint16
    private const val SQLITE_DEFAULT_PAGE_SIZE = 4096
    private const val SQLITE_PAGE_SIZE_OFFSET  = 16

    // ── Timing ────────────────────────────────────────────────────────────────
    private const val POLL_INTERVAL_MS   = 3_000L
    private const val EXPORT_DEBOUNCE_MS = 800L

    // ── Public observable state ───────────────────────────────────────────────

    private val _syncState = MutableLiveData<SyncState>(SyncState.Disabled)
    val syncState: LiveData<SyncState> = _syncState

    private val _importEvent = MutableLiveData<Unit>()
    val importEvent: LiveData<Unit> = _importEvent

    private val _writeStats = MutableLiveData<SyncWriteStats>(SyncWriteStats.EMPTY)
    val writeStats: LiveData<SyncWriteStats> = _writeStats

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

    // ── Settings ──────────────────────────────────────────────────────────────

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

    // ── Export schedule ───────────────────────────────────────────────────────

    fun scheduleExport() {
        if (!isEnabled()) return
        val s = scope ?: return
        exportJob?.cancel()
        exportJob = s.launch {
            delay(EXPORT_DEBOUNCE_MS)
            performExport()
        }
    }

    // ── Coroutine control ─────────────────────────────────────────────────────

    /**
     * Called by the UI when the user chooses to **accept** a blocked import
     * (i.e. they acknowledge the conflicts and want to apply the remote snapshot
     * anyway).
     *
     * [token] comes from [SyncState.ConflictPending.pendingToken].
     */
    fun forceAcceptPendingImport(token: PendingImportToken) {
        val s = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scope = it }
        s.launch {
            try {
                _syncState.postValue(SyncState.Syncing)
                performImport(token.syncDir, token.meta, token.remoteVersion)
            } catch (e: Exception) {
                _syncState.postValue(SyncState.Error("Import failed: ${short(e)}"))
            } finally {
                conflictPollPaused = false
                if (isEnabled()) startPolling()
            }
        }
    }

    /**
     * Called by the UI when the user chooses to **skip** a blocked import
     * (i.e. they want to keep their local data and discard the remote snapshot).
     *
     * We advance [PREFS_KEY_LAST_VER] to the remote version so we don't
     * re-flag the same snapshot on the next poll cycle.
     *
     * [token] comes from [SyncState.ConflictPending.pendingToken].
     */
    fun skipPendingImport(token: PendingImportToken) {
        prefs().edit().putLong(PREFS_KEY_LAST_VER, token.remoteVersion).apply()
        conflictPollPaused = false
        _syncState.postValue(SyncState.Idle)
        if (isEnabled()) startPolling()
    }

    /**
     * True while the poll loop is intentionally paused waiting for the user
     * to resolve a [SyncState.ConflictPending] dialog.  Prevents the poller
     * from re-flagging the same snapshot repeatedly.
     */
    @Volatile private var conflictPollPaused = false

    private fun startPolling() {
        if (scope?.isActive != true) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        pollJob?.cancel()
        pollJob = scope!!.launch {
            while (isActive) {
                if (!conflictPollPaused) {
                    try { pollOnce() } catch (e: Exception) {
                        _syncState.postValue(SyncState.Error(short(e)))
                    }
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
        // Resolve to a real File — needed for both meta read and import
        val syncDir = resolveSyncDir()  // null = error already posted, or no folder set

        // Read meta — direct if we have a path, SAF otherwise
        val metaJson: String = try {
            if (syncDir != null) {
                val f = File(syncDir, SYNC_META_FILE)
                if (!f.exists()) return
                f.readText(Charsets.UTF_8)
            } else {
                // Direct resolution failed — try SAF read for meta (tiny file, acceptable)
                val doc = safDocumentOrNull() ?: return
                val metaDoc = doc.findFile(SYNC_META_FILE) ?: return
                appContext.contentResolver.openInputStream(metaDoc.uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: return
            }
        } catch (e: Exception) {
            _syncState.postValue(SyncState.Error("Meta read: ${short(e)}"))
            return
        }

        val meta = try { JSONObject(metaJson) } catch (_: Exception) {
            _syncState.postValue(SyncState.Error("Meta corrupt"))
            return
        }

        val remoteVersion  = meta.optLong("version", 0L)
        val remoteDeviceId = meta.optString("deviceId", "")
        val lastKnownVer   = prefs().getLong(PREFS_KEY_LAST_VER, 0L)

        if (remoteVersion <= lastKnownVer) return
        if (remoteDeviceId == myDeviceId()) {
            prefs().edit().putLong(PREFS_KEY_LAST_VER, remoteVersion).apply()
            return
        }

        // ── Overwrite-conflict guard ──────────────────────────────────────────
        // Before applying the remote snapshot, diff it against local tasks.
        // If any guarded field would be blanked or a local task is missing on
        // the remote side, block the import and surface a warning to the user.
        val remoteDbFile: java.io.File? = if (syncDir != null) {
            java.io.File(syncDir, SYNC_DB_FILE).takeIf { it.exists() }
        } else null

        if (remoteDbFile != null) {
            val conflicts = try {
                SyncFieldGuard.detectConflicts(appContext, remoteDbFile)
            } catch (e: Exception) {
                _syncState.postValue(SyncState.Error("Conflict check failed: ${short(e)}"))
                return
            }

            if (conflicts.isNotEmpty()) {
                // Pause polling so we don't spam the same dialog on every cycle.
                conflictPollPaused = true
                _syncState.postValue(
                    SyncState.ConflictPending(
                        conflicts    = conflicts,
                        pendingToken = PendingImportToken(syncDir, meta, remoteVersion),
                    )
                )
                // Return here — the UI will call forceAcceptPendingImport or
                // skipPendingImport to resume.
                return
            }
        }
        // ── No conflicts — proceed with automatic import ───────────────────

        try {
            _syncState.postValue(SyncState.Syncing)
            performImport(syncDir, meta, remoteVersion)
        } catch (e: Exception) {
            _syncState.postValue(SyncState.Error("Import failed: ${short(e)}"))
            if (isEnabled()) startPolling()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IMPORT
    // Import reads from the external file into private app storage.
    // Full copy is fine — destination is internal flash, not the SD card.
    // ─────────────────────────────────────────────────────────────────────────

    private fun performImport(syncDir: File?, meta: JSONObject, remoteVersion: Long) {
        stopPolling()
        TaskDatabase.checkpointAndClose(appContext)

        val localDb  = TaskDatabase.getDatabaseFile(appContext)
        val localWal = File(localDb.path + "-wal")
        val localShm = File(localDb.path + "-shm")
        localDb.parentFile?.mkdirs()

        if (syncDir != null) {
            // Direct: simple File.copyTo into private app storage
            val src = File(syncDir, SYNC_DB_FILE)
            if (!src.exists()) {
                _syncState.postValue(SyncState.Error("Sync .db missing in folder"))
                if (isEnabled()) startPolling()
                return
            }
            src.copyTo(localDb, overwrite = true)
        } else {
            // SAF stream read — only path when direct resolution failed
            val doc = safDocumentOrNull() ?: run {
                _syncState.postValue(SyncState.Error("Cannot access sync folder"))
                if (isEnabled()) startPolling()
                return
            }
            val srcDoc = doc.findFile(SYNC_DB_FILE) ?: run {
                _syncState.postValue(SyncState.Error("Sync .db missing in folder"))
                if (isEnabled()) startPolling()
                return
            }
            appContext.contentResolver.openInputStream(srcDoc.uri)
                ?.use { input -> localDb.outputStream().use { input.copyTo(it) } }
                ?: throw IOException("Cannot read sync DB via SAF")
        }

        localWal.delete()
        localShm.delete()

        applyRemoteSettings(meta)
        prefs().edit().putLong(PREFS_KEY_LAST_VER, remoteVersion).apply()
        _syncState.postValue(SyncState.OK)
        _importEvent.postValue(Unit)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EXPORT
    // Opens eevdf_sync.db directly with RandomAccessFile — the exact same way
    // a text file would be opened on external storage.  No SAF, no streams.
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun performExport() {
        val syncDir = resolveSyncDir()
        if (syncDir == null) {
            // resolveSyncDir() already posted a specific error message
            return
        }
        if (!syncDir.canWrite()) {
            _syncState.postValue(SyncState.Error("Sync folder is not writable — check storage permission"))
            return
        }

        try {
            _syncState.postValue(SyncState.Syncing)

            val localDb = TaskDatabase.getDatabaseFile(appContext)
            if (!localDb.exists()) {
                _syncState.postValue(SyncState.Error("Local database not found"))
                return
            }

            // Merge WAL into main file so the snapshot is self-contained.
            // Room stays open — TRUNCATE checkpoint does not require closing.
            TaskDatabase.checkpointWal(appContext)

            exportPageDiff(localDb, File(syncDir, SYNC_DB_FILE))

            // Write meta as a plain text file — direct, no SAF
            val version = System.currentTimeMillis()
            File(syncDir, SYNC_META_FILE)
                .writeText(buildMetaJson(version).toString(2), Charsets.UTF_8)
            prefs().edit().putLong(PREFS_KEY_LAST_VER, version).apply()

        } catch (e: Exception) {
            _syncState.postValue(SyncState.Error("Export failed: ${short(e)}"))
        }
    }

    /**
     * Opens [src] (local DB) and [dst] (eevdf_sync.db on external storage)
     * directly as RandomAccessFile — identical to how any file is opened on
     * the filesystem.  Reads both files one SQLite page at a time and writes
     * only pages that differ.
     *
     *   src = local app DB  (read-only)
     *   dst = external sync file  (read-write, created if absent)
     *
     * For each page at [offset]:
     *   • dst has the page already → read it back, compare byte-by-byte.
     *       Equal   → no write at all.
     *       Differs → seek to offset in dst, write only those bytes.
     *   • dst is shorter → seek past end, write (append).
     * After all src pages processed:
     *   • dst longer than src → setLength(srcLen) truncates without rewriting.
     */
    private fun exportPageDiff(src: File, dst: File) {
        val pageSize = readSqlitePageSize(src)
        val srcLen   = src.length()

        var pagesWritten = 0
        var pagesSkipped = 0
        var bytesWritten = 0L

        RandomAccessFile(src, "r").use { srcRaf ->
            if (!dst.exists()) dst.createNewFile()
            RandomAccessFile(dst, "rw").use { dstRaf ->
                val dstLen  = dstRaf.length()
                val srcPage = ByteArray(pageSize)
                val dstPage = ByteArray(pageSize)
                var offset  = 0L

                while (offset < srcLen) {
                    val chunk = minOf(pageSize.toLong(), srcLen - offset).toInt()

                    srcRaf.seek(offset)
                    srcRaf.readFully(srcPage, 0, chunk)

                    if (offset + chunk <= dstLen) {
                        // Page already exists in dst — read and compare
                        dstRaf.seek(offset)
                        dstRaf.readFully(dstPage, 0, chunk)

                        var differs = false
                        for (i in 0 until chunk) {
                            if (srcPage[i] != dstPage[i]) { differs = true; break }
                        }

                        if (differs) {
                            dstRaf.seek(offset)
                            dstRaf.write(srcPage, 0, chunk)
                            pagesWritten++
                            bytesWritten += chunk
                        } else {
                            pagesSkipped++
                        }
                    } else {
                        // dst shorter — append
                        dstRaf.seek(offset)
                        dstRaf.write(srcPage, 0, chunk)
                        pagesWritten++
                        bytesWritten += chunk
                    }

                    offset += chunk
                }

                // DB shrank (bulk delete / VACUUM) — trim to match
                if (dstLen > srcLen) dstRaf.setLength(srcLen)
            }
        }

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
        _syncState.postValue(SyncState.OK)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PATH RESOLUTION
    // Decode the SAF tree URI → real filesystem File so we can use it directly.
    // Returns null and posts an error if resolution fails.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the sync folder as a [File], or null if the URI cannot be decoded
     * to a real path (permission missing, cloud/network provider, etc.).
     *
     * The SAF tree URI path looks like:
     *   /tree/primary%3ADownload%2FSyncFolder
     * Decoded document ID: "primary:Download/SyncFolder"
     *   → /storage/emulated/0/Download/SyncFolder
     *
     * For SD cards / USB drives the volume name is the FAT UUID:
     *   "ABCD-1234:SyncFolder"
     *   → /storage/ABCD-1234/SyncFolder
     */
    private fun resolveSyncDir(): File? {
        val uri = getSyncFolderUri() ?: run {
            _syncState.postValue(SyncState.Error(
                "No sync folder set — configure in Settings → Multiuser Sync"))
            return null
        }

        val file = uriToFile(uri)
        if (file == null) {
            _syncState.postValue(SyncState.Error(
                "Cannot access sync folder directly — grant 'All files access' in Settings"))
            return null
        }
        if (!file.exists()) {
            _syncState.postValue(SyncState.Error("Sync folder does not exist"))
            return null
        }
        return file
    }

    /** Decodes a SAF tree URI to a real [File]. Returns null on any failure. */
    private fun uriToFile(treeUri: Uri): File? {
        return try {
            val rawPath = treeUri.path ?: return null
            if (!rawPath.startsWith("/tree/")) return null

            val docId = Uri.decode(rawPath.removePrefix("/tree/"))
                .substringBefore("/document/")

            val colon = docId.indexOf(':')
            if (colon < 0) return null

            val volume   = docId.substring(0, colon)
            val relative = docId.substring(colon + 1).replace('/', File.separatorChar)

            val root: File = (when {
                volume.equals("primary", ignoreCase = true) ->
                    Environment.getExternalStorageDirectory()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
                    val sm = appContext.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                    sm.storageVolumes
                        .firstOrNull { it.uuid?.equals(volume, ignoreCase = true) == true }
                        ?.let { vol ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) vol.directory
                            else {
                                @Suppress("DEPRECATION")
                                vol.javaClass.getMethod("getPathFile").invoke(vol) as? File
                            }
                        }
                }
                else -> null
            }) ?: return null

            val resolved = if (relative.isEmpty()) root else File(root, relative)
            if (resolved.exists() || resolved.canRead()) resolved else null

        } catch (_: Exception) { null }
    }

    /**
     * Returns a SAF [DocumentFile] for the sync folder.
     * Only used as a last resort for import meta/db reads when [uriToFile] fails.
     */
    private fun safDocumentOrNull(): DocumentFile? {
        val uri = getSyncFolderUri() ?: return null
        val doc = DocumentFile.fromTreeUri(appContext, uri) ?: return null
        return if (doc.exists() && doc.canRead()) doc else null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SQLite page size
    // ─────────────────────────────────────────────────────────────────────────

    private fun readSqlitePageSize(dbFile: File): Int {
        return try {
            RandomAccessFile(dbFile, "r").use { raf ->
                raf.seek(SQLITE_PAGE_SIZE_OFFSET.toLong())
                val hi = raf.read(); val lo = raf.read()
                if (hi < 0 || lo < 0) return SQLITE_DEFAULT_PAGE_SIZE
                val size = (hi shl 8) or lo
                if (size == 1) 65536 else size
            }
        } catch (_: Exception) { SQLITE_DEFAULT_PAGE_SIZE }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Meta JSON
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildMetaJson(version: Long): JSONObject {
        val root = JSONObject()
        root.put("version",  version)
        root.put("deviceId", myDeviceId())

        val settings = JSONObject()
        @Suppress("UNCHECKED_CAST")
        (prefs().all as Map<String, Any?>).forEach { (key, value) ->
            if (key in EXCLUDED_SETTING_KEYS || value == null) return@forEach
            val entry = JSONObject()
            when (value) {
                is Boolean -> { entry.put("t", "b"); entry.put("v", value) }
                is String  -> { entry.put("t", "s"); entry.put("v", value) }
                is Int     -> { entry.put("t", "i"); entry.put("v", value) }
                is Long    -> { entry.put("t", "l"); entry.put("v", value) }
                is Float   -> { entry.put("t", "f"); entry.put("v", value.toDouble()) }
                else       -> return@forEach
            }
            settings.put(key, entry)
        }
        root.put("settings", settings)
        return root
    }

    private fun applyRemoteSettings(meta: JSONObject) {
        val settings = meta.optJSONObject("settings") ?: return
        val edit = prefs().edit()
        settings.keys().forEach { key ->
            if (key in EXCLUDED_SETTING_KEYS) return@forEach
            val entry = settings.optJSONObject(key) ?: return@forEach
            try {
                when (entry.getString("t")) {
                    "b" -> edit.putBoolean(key, entry.getBoolean("v"))
                    "s" -> edit.putString(key,  entry.getString("v"))
                    "i" -> edit.putInt(key,     entry.getInt("v"))
                    "l" -> edit.putLong(key,    entry.getLong("v"))
                    "f" -> edit.putFloat(key,   entry.getDouble("v").toFloat())
                }
            } catch (_: Exception) {}
        }
        edit.apply()
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
}
