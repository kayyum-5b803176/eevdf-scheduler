package com.eevdf.scheduler.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.eevdf.scheduler.backup.BackupManager
import com.eevdf.scheduler.db.TaskRepository
import kotlinx.coroutines.*
import java.io.IOException

/**
 * Singleton that drives live multi-user sync over an external shared folder
 * (chosen by the user via ACTION_OPEN_DOCUMENT_TREE).
 *
 * ── How it works ─────────────────────────────────────────────────────────────
 *
 *  Both users point the app at the SAME folder on shared storage
 *  (e.g. a shared NAS mount, a cloud-synced folder, or a USB drive).
 *
 *  Every [POLL_INTERVAL_MS] milliseconds this manager:
 *    1. Reads `eevdf_sync.json` from the shared folder.
 *    2. Compares a content hash against the last import hash.
 *    3. If it changed → calls [TaskRepository.restoreFromSyncBackup] which
 *       replaces the local Room DB.  Room's LiveData fires → the UI updates
 *       automatically.  [importEvent] is posted so the ViewModel can also
 *       refresh its in-memory timer state.
 *
 *  After any local DB write the ViewModel calls [scheduleExport]:
 *    • A debounced coroutine writes the current DB (as JSON) to the shared file.
 *    • The newly written hash is recorded so our own import loop won't treat
 *      it as a "remote change".
 *
 * ── Error safety ─────────────────────────────────────────────────────────────
 *
 *  Every operation is wrapped in try/catch.  Any failure:
 *    • Posts [SyncState.Error] with a short message.
 *    • Does NOT crash the app.
 *    • The poll loop keeps running — errors are self-healing on the next cycle.
 *
 * ── Thread model ─────────────────────────────────────────────────────────────
 *
 *  All file I/O runs on [Dispatchers.IO].
 *  [syncState] and [importEvent] are MutableLiveData — always posted, never set,
 *  so they are safe to call from any thread.
 */
object MultiUserSyncManager {

    // ── Prefs keys (shared with MultiUserSyncActivity) ────────────────────────
    const val PREFS_KEY_SYNC_URI     = "multiuser_sync_uri"
    const val PREFS_KEY_SYNC_ENABLED = "multiuser_sync_enabled"

    private const val SYNC_FILE_NAME     = "eevdf_sync.json"
    private const val POLL_INTERVAL_MS   = 3_000L
    private const val EXPORT_DEBOUNCE_MS = 800L

    // ── Public observable state ───────────────────────────────────────────────

    private val _syncState = MutableLiveData<SyncState>(SyncState.Disabled)
    val syncState: LiveData<SyncState> = _syncState

    /**
     * Fires with the current epoch millis every time a remote import completes
     * successfully.  The ViewModel observes this to refresh its in-memory
     * running-task state from the newly-updated Room DB.
     */
    private val _importEvent = MutableLiveData<Long>()
    val importEvent: LiveData<Long> = _importEvent

    // ── Internal state ────────────────────────────────────────────────────────

    private var scope: CoroutineScope? = null
    private var pollJob:   Job? = null
    private var exportJob: Job? = null

    /**
     * Content hash of the last JSON we *imported*.
     * Used to skip re-importing the file we ourselves just exported.
     */
    private var lastImportHash: Int = 0

    /**
     * Content hash of the last JSON we *exported*.
     * Used to avoid writing identical content on every tick.
     */
    private var lastExportHash: Int = 0

    private lateinit var appContext:  Context
    private lateinit var repository:  TaskRepository

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Must be called once from [TaskViewModel] init, before any other method.
     * Safe to call multiple times (idempotent after first call).
     */
    fun init(context: Context, repo: TaskRepository) {
        appContext = context.applicationContext
        repository = repo
        if (isEnabled()) {
            _syncState.postValue(SyncState.Idle)
            startPolling()
        } else {
            _syncState.postValue(SyncState.Disabled)
        }
    }

    /** Re-checks enabled state — call from Activity.onResume() via ViewModel. */
    fun onResume() {
        if (isEnabled() && pollJob?.isActive != true) {
            startPolling()
        }
    }

    // ── Settings accessors ────────────────────────────────────────────────────

    fun isEnabled(): Boolean = prefs()
        .getBoolean(PREFS_KEY_SYNC_ENABLED, false)

    fun getSyncFolderUri(): Uri? {
        val s = prefs().getString(PREFS_KEY_SYNC_URI, null) ?: return null
        return try { Uri.parse(s) } catch (_: Exception) { null }
    }

    fun setSyncFolder(uri: Uri) {
        prefs().edit().putString(PREFS_KEY_SYNC_URI, uri.toString()).apply()
        // Reset hashes so we'll do a fresh sync immediately
        lastImportHash = 0
        lastExportHash = 0
    }

    fun setEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(PREFS_KEY_SYNC_ENABLED, enabled).apply()
        if (enabled) {
            _syncState.postValue(SyncState.Idle)
            lastImportHash = 0
            lastExportHash = 0
            startPolling()
        } else {
            stopPolling()
            _syncState.postValue(SyncState.Disabled)
        }
    }

    // ── Export (triggered by local DB writes) ─────────────────────────────────

    /**
     * Schedules a debounced export.  Call this after any local action that
     * writes to the DB (startTimer, pauseTimer, markCompleted, etc.).
     * Does nothing if sync is disabled or not yet initialised.
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

    // ── Private polling & IO ──────────────────────────────────────────────────

    private fun startPolling() {
        if (scope == null || scope?.isActive != true) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        pollJob?.cancel()
        pollJob = scope!!.launch {
            while (isActive) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    _syncState.postValue(SyncState.Error(shortMsg(e)))
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

    /**
     * One poll cycle:
     *  • Validate the folder is accessible.
     *  • Read the sync file (if it exists).
     *  • Import if the content hash is different from the last import.
     */
    private suspend fun pollOnce() {
        val uri    = getSyncFolderUri() ?: run {
            _syncState.postValue(SyncState.Error("No sync folder set — configure in Settings"))
            return
        }
        val folder = DocumentFile.fromTreeUri(appContext, uri) ?: run {
            _syncState.postValue(SyncState.Error("Sync folder URI invalid"))
            return
        }
        if (!folder.exists()) {
            _syncState.postValue(SyncState.Error("Sync folder does not exist"))
            return
        }
        if (!folder.canRead()) {
            _syncState.postValue(SyncState.Error("Sync folder not readable"))
            return
        }

        val syncFile = folder.findFile(SYNC_FILE_NAME) ?: return // file not yet created by any user

        if (!syncFile.exists() || !syncFile.canRead()) return

        val content = try {
            readDocumentFile(syncFile)
        } catch (e: Exception) {
            _syncState.postValue(SyncState.Error("Read error: ${shortMsg(e)}"))
            return
        }

        val hash = content.hashCode()
        if (hash == lastImportHash) return // nothing changed

        // Remote content changed — import it
        try {
            _syncState.postValue(SyncState.Syncing)
            val backup = BackupManager.fromSyncJson(content)
            repository.restoreFromSyncBackup(backup.tasks)
            lastImportHash = hash
            lastExportHash = hash  // we wrote this (or it is now our DB) — skip re-export
            _syncState.postValue(SyncState.OK)
            _importEvent.postValue(System.currentTimeMillis())
        } catch (e: Exception) {
            _syncState.postValue(SyncState.Error("Import error: ${shortMsg(e)}"))
        }
    }

    private suspend fun performExport() {
        val uri = getSyncFolderUri() ?: run {
            _syncState.postValue(SyncState.Error("No sync folder — configure in Settings"))
            return
        }
        val folder = DocumentFile.fromTreeUri(appContext, uri) ?: run {
            _syncState.postValue(SyncState.Error("Sync folder URI invalid"))
            return
        }
        if (!folder.exists() || !folder.canWrite()) {
            _syncState.postValue(SyncState.Error("Sync folder not writable"))
            return
        }

        try {
            _syncState.postValue(SyncState.Syncing)

            val p = prefs()
            val tasks = repository.getAllTasksForBackup()
            val json  = BackupManager.toSyncJson(
                tasks               = tasks,
                groupsEnabled       = p.getBoolean("groups_enabled",       false),
                globalRotateEnabled = p.getBoolean("global_rotate_enabled", false),
                allowEditEnabled    = p.getBoolean("allow_edit_enabled",    false)
            )

            val hash = json.hashCode()
            if (hash == lastExportHash) {
                _syncState.postValue(SyncState.OK)
                return  // content unchanged — skip write
            }

            // Find or create the sync file
            val syncFile: DocumentFile = folder.findFile(SYNC_FILE_NAME)
                ?: folder.createFile("application/json", SYNC_FILE_NAME)
                ?: run {
                    _syncState.postValue(SyncState.Error("Cannot create sync file in folder"))
                    return
                }

            appContext.contentResolver
                .openOutputStream(syncFile.uri, "wt")
                ?.use { out -> out.write(json.toByteArray(Charsets.UTF_8)) }
                ?: run {
                    _syncState.postValue(SyncState.Error("Cannot open sync file for writing"))
                    return
                }

            lastExportHash = hash
            lastImportHash = hash  // this is now "what we know" — don't re-import it
            _syncState.postValue(SyncState.OK)

        } catch (e: Exception) {
            _syncState.postValue(SyncState.Error("Export error: ${shortMsg(e)}"))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun readDocumentFile(file: DocumentFile): String {
        return appContext.contentResolver
            .openInputStream(file.uri)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Cannot open sync file for reading")
    }

    private fun prefs() =
        appContext.getSharedPreferences("eevdf_prefs", Context.MODE_PRIVATE)

    private fun shortMsg(e: Exception): String =
        e.message?.take(80) ?: e.javaClass.simpleName
}
