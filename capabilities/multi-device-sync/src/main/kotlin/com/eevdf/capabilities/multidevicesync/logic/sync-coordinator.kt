package com.eevdf.capabilities.multidevicesync.logic

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import com.eevdf.capabilities.taskstorage.TaskDatabase
import com.eevdf.kernel.clock.Clock
import com.eevdf.kernel.eventbus.BackupRestoreRequest
import com.eevdf.kernel.eventbus.BackupRestoreResult
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.RestoreMode
import com.eevdf.kernel.eventbus.SyncLocalPublished
import com.eevdf.kernel.eventbus.SyncPhase
import com.eevdf.kernel.eventbus.SyncRemoteApplied
import com.eevdf.kernel.eventbus.SyncStatus
import com.eevdf.kernel.eventbus.Topics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Decides WHEN to sync and reports what happened. The merging itself is
 * [SyncEngine]; the card is [SyncFolder]; one full pass is [SyncSession].
 *
 * ── Everything arrives and leaves on the bus (rule 3) ────────────────────────
 *
 *   IN   task.saved               → write my changes out (debounced)
 *        app.foreground-changed   → true: full sync now ("app open — data synced")
 *                                   false: write my changes out NOW, before the
 *                                   user switches Android user and this process
 *                                   may be frozen or killed
 *        sync.now-requested       → full sync now (toolbar icon, settings button)
 *        backup.restore-requested → restore through the merge engine
 *        backup.safety-copies-requested (request) → list of safety copies
 *
 *   OUT  sync.status-changed (retained)   sync.local-published
 *        sync.remote-applied              sync.failed
 *        sync.maintenance                 backup.restore-finished
 *
 * No other capability imports this class. task-list-screen used to call
 * `MultiUserSyncManager` directly (flagged debt since v6.10.8); that
 * dependency is gone.
 *
 * ── One operation at a time ──────────────────────────────────────────────────
 *
 * A single [Mutex] serialises every sync, restore and safety copy. This is
 * requirement 5 ("no conflict between sync and backup") in one line: they
 * cannot overlap, and both are the same kind of thing anyway — rows merged
 * into the live database.
 *
 * ── Bus handlers return immediately ──────────────────────────────────────────
 *
 * The bus gives a handler 5 seconds before it treats the capability as hung.
 * A first sync of a large history takes longer, so every handler below only
 * *starts* work on this object's own scope and returns.
 *
 * Constructed once by composition (an injected @Singleton, not a global
 * `object` — see ADDING_A_FEATURE.md) and created eagerly at app start.
 */
class SyncCoordinator(
    context: Context,
    private val bus: EventBus,
    private val clock: Clock,
) {
    /** What a user wants done, at join time, with rows only this user has. */
    enum class JoinMode { KEEP_MINE, GROUP_ONLY }

    data class Member(val name: String, val isMe: Boolean, val lastWriteMs: Long, val bytes: Long)

    /** Everything the settings screen shows. Collected as a flow; never polled. */
    data class UiState(
        val enabled: Boolean = false,
        val joined: Boolean = false,
        val folderLabel: String? = null,
        val phase: SyncPhase = SyncPhase.DISABLED,
        val message: String? = null,
        val memberName: String = "",
        val lastSyncMs: Long = 0L,
        val lastWrittenBytes: Long = 0L,
        val lastAppliedChanges: Int = 0,
        val sessionWrittenBytes: Long = 0L,
        val sessionSyncs: Int = 0,
        val pendingChanges: Long = 0L,
        val folderBytes: Long = 0L,
        val members: List<Member> = emptyList(),
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    @Volatile private var engine: SyncEngine? = null
    private var session: SyncSession? = null
    private var sessionDir: File? = null
    private var pushJob: Job? = null
    private var lastFingerprint: String = ""

    /** When the oldest save that has not been written to the card yet happened; 0 = none waiting. */
    @Volatile private var pendingSince: Long = 0L

    init {
        bus.subscribe(Topics.TASK_SAVED, CAPABILITY_ID) { schedulePush() }
        bus.subscribe(Topics.APP_FOREGROUND_CHANGED, CAPABILITY_ID) { foreground ->
            if (foreground) requestSync(quiet = false) else scope.launch { pushNow() }
        }
        bus.subscribe(Topics.SYNC_NOW_REQUESTED, CAPABILITY_ID) { requestSync(quiet = false) }
        bus.subscribe(Topics.BACKUP_RESTORE_REQUESTED, CAPABILITY_ID) { request ->
            scope.launch { restore(request) }
        }
        bus.respondTo(Topics.SAFETY_COPIES_REQUESTED, CAPABILITY_ID) { safetyCopies().map { it.absolutePath } }
        scope.launch { startUp() }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Settings (called by this capability's own settings screen)
    // ═════════════════════════════════════════════════════════════════════════

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun folderUri(): Uri? = prefs.getString(KEY_FOLDER, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun setFolder(uri: Uri) {
        prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
        session = null
        lastFingerprint = ""
        refreshUi()
        if (isEnabled()) requestSync(quiet = false)
    }

    /**
     * Switches sync on or off. [joinMode] only matters the first time this
     * user joins a folder that already has shared data.
     *
     * Switching off stops reading and writing the folder; it does not forget
     * anything. Changes made while off are still recorded locally and are
     * sent, correctly dated, when sync is switched on again.
     */
    fun setEnabled(enabled: Boolean, joinMode: JoinMode = JoinMode.KEEP_MINE) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).putString(KEY_JOIN_MODE, joinMode.name).apply()
        if (enabled) {
            requestSync(quiet = false)
        } else {
            scope.launch { publishStatus(SyncPhase.DISABLED, null) }
        }
        refreshUi()
    }

    /** Asks for a full sync now. Safe to call from any thread, any number of times. */
    fun syncNow() = requestSync(quiet = false)

    /**
     * True when switching sync on would be a first-time join into a folder
     * that already holds other members' data *and* this user has data of its
     * own — the one situation where the user is asked what to do with it.
     */
    suspend fun wouldJoinExistingGroup(): Boolean = lock.withLock {
        try {
            val e = engine()
            if (e.isBootstrapped && e.isAnnounced) return@withLock false
            val dir = resolveFolder() ?: return@withLock false
            val folder = SyncFolder(dir, peerId())
            val othersPresent = folder.segments().keys.any { it != peerId() } || folder.snapshots().isNotEmpty()
            othersPresent && hasLocalTasks()
        } catch (_: Exception) {
            false
        }
    }

    /** Reloads the member list and folder size for the settings screen (reads a few short lines). */
    fun refreshMembers() {
        scope.launch {
            lock.withLock {
                val s = runCatching { currentSession() }.getOrNull() ?: return@withLock
                val members = runCatching { s.members() }.getOrDefault(emptyList())
                    .map { Member(it.name, it.isMe, it.lastWriteMs, it.bytes) }
                _ui.value = _ui.value.copy(members = members, folderBytes = runCatching { s.folderBytes() }.getOrDefault(0L))
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Start-up and triggers
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun startUp() {
        migrateLegacySettings()
        try {
            lock.withLock { engine() }
        } catch (e: Exception) {
            report(Topics.SYNC_FAILED, "Sync could not start: ${short(e)}")
        }
        refreshUi()
        if (isEnabled()) {
            publishStatus(SyncPhase.IDLE, null)
            syncCycle(quiet = false)
        } else {
            publishStatus(SyncPhase.DISABLED, null)
        }
        pollLoop()
    }

    /**
     * While the app is on screen, look at the folder every few seconds. This
     * is a directory listing — it reads no file contents and writes nothing —
     * and a sync only runs if a file actually grew or appeared, or something
     * local is still waiting to go out.
     */
    private suspend fun pollLoop() {
        while (scope.isActive) {
            delay(POLL_MS)
            if (!isEnabled() || bus.getLast(Topics.APP_FOREGROUND_CHANGED) != true) continue
            val dir = resolveFolder() ?: continue
            val changed = fingerprint(dir) != lastFingerprint
            val pending = runCatching { engine?.hasPendingLocalChanges() == true }.getOrDefault(false)
            if (changed || pending) syncCycle(quiet = true)
        }
    }

    private fun requestSync(quiet: Boolean) {
        if (!isEnabled()) return
        scope.launch { syncCycle(quiet) }
    }

    /**
     * Writing to the card costs a few file-system blocks however small the
     * change, so writes are batched rather than made per save:
     *
     *  - while the app is in use, wait until the user has been quiet for
     *    [PUSH_QUIET_MS] — saves in the meantime change the same rows again
     *    and again, and a row saved ten times is still ONE journal line;
     *  - but never wait longer than [PUSH_MAX_WAIT_MS] since the first unsent
     *    save;
     *  - and always write the moment the app leaves the screen (see init), which
     *    is the only moment another Android user can start reading.
     *
     * Nothing is at risk while a change waits: it is already recorded in the
     * database in the same transaction as the change itself.
     */
    private fun schedulePush() {
        if (!isEnabled()) return
        val now = clock.nowEpochMillis()
        if (pendingSince == 0L) pendingSince = now
        val wait = minOf(PUSH_QUIET_MS, maxOf(0L, PUSH_MAX_WAIT_MS - (now - pendingSince)))
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(wait)
            pushNow()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Sync
    // ═════════════════════════════════════════════════════════════════════════

    /** Writes local changes to the card and nothing else. Cheap; used on every save and on leaving the screen. */
    private suspend fun pushNow() {
        pendingSince = 0L
        if (!isEnabled()) return
        var published: SyncLocalPublished? = null
        var failure: String? = null
        lock.withLock {
            try {
                val e = engine()
                if (!e.isBootstrapped || !e.isAnnounced) return@withLock // joining is done by a full cycle
                val (lines, bytes) = currentSession().pushOnly()
                if (lines > 0) {
                    published = SyncLocalPublished(lines, bytes)
                    noteWritten(bytes)
                }
            } catch (e: Exception) {
                // Not an emergency: the changes stay in the outbox and go out
                // with the next successful write.
                failure = "Changes are waiting — folder not reachable (${short(e)})"
            }
        }
        published?.let { bus.publish(Topics.SYNC_LOCAL_PUBLISHED, it, CAPABILITY_ID) }
        failure?.let { publishStatus(SyncPhase.ERROR, it); report(Topics.SYNC_FAILED, it) }
        if (published != null && failure == null) publishStatus(SyncPhase.OK, null)
        refreshUi()
    }

    private suspend fun syncCycle(quiet: Boolean) {
        if (!isEnabled()) return
        if (!quiet) publishStatus(SyncPhase.SYNCING, null)

        var result: SyncSession.CycleResult? = null
        var failure: String? = null
        val notes = ArrayList<String>()
        var repaired = 0

        lock.withLock {
            try {
                val e = engine()
                val s = currentSession() // throws, before anything is touched, if the folder is unusable
                if (!e.isBootstrapped) {
                    // First time this user joins a group. Whatever happens
                    // next, the data as it was is kept on internal storage.
                    safetyCopy("before-join")?.let { notes.add("Safety copy taken before joining: ${it.name}") }
                    e.bootstrap()
                }
                val r = s.cycle(joinChoice())
                if (r.applied.applied > 0) {
                    repaired = TaskRunInvariants.enforceSingleRunningTask(db())
                    if (repaired > 0) runCatching { s.pushOnly() }
                }
                sessionDir?.let { lastFingerprint = fingerprint(it) }
                noteWritten(r.bytesWritten)
                result = r
            } catch (e: Exception) {
                failure = short(e)
            }
        }

        val r = result
        if (r == null) {
            val message = failure ?: "Sync failed"
            publishStatus(SyncPhase.ERROR, message)
            report(Topics.SYNC_FAILED, message)
            refreshUi()
            return
        }

        // ── Tell the rest of the app, in the order things happened ───────────
        for (n in notes) report(Topics.SYNC_MAINTENANCE, n)
        if (r.linesWritten > 0) {
            bus.publish(Topics.SYNC_LOCAL_PUBLISHED, SyncLocalPublished(r.linesWritten, r.bytesWritten), CAPABILITY_ID)
        }
        if (r.announced > 0) report(Topics.SYNC_MAINTENANCE, "Joined: shared ${r.announced} rows that only this user had")
        if (r.discarded > 0) report(Topics.SYNC_MAINTENANCE, "Joined: dropped ${r.discarded} rows the group did not have")
        if (r.snapshotLoaded) report(Topics.SYNC_MAINTENANCE, "Caught up from a snapshot")
        if (r.snapshotWritten) report(Topics.SYNC_MAINTENANCE, "Journal folded into a new snapshot")
        if (r.drift.isNotEmpty()) report(Topics.SYNC_MAINTENANCE, "Self-check: data differed from ${r.drift.joinToString()} at the same point — repairing from snapshots")
        if (r.repairLoaded > 0) report(Topics.SYNC_MAINTENANCE, "Repair: merged ${r.repairLoaded} snapshot(s) from other users")
        if (r.journalFilesRemoved > 0) report(Topics.SYNC_MAINTENANCE, "Removed ${r.journalFilesRemoved} old journal file(s) everyone has already read")
        if (r.damagedLines > 0) report(Topics.SYNC_MAINTENANCE, "Skipped ${r.damagedLines} damaged journal line(s)")
        if (r.gaps > 0) report(Topics.SYNC_MAINTENANCE, "Part of another member's journal was missing (${r.gaps})")
        if (r.applied.parked > 0) report(Topics.SYNC_MAINTENANCE, "Kept ${r.applied.parked} change(s) this app version cannot use yet")
        if (repaired > 0) report(Topics.SYNC_MAINTENANCE, "Two timers were running after merging; paused $repaired")
        if (r.applied.applied > 0) {
            _ui.value = _ui.value.copy(lastAppliedChanges = r.applied.applied)
            bus.publish(
                Topics.SYNC_REMOTE_APPLIED,
                SyncRemoteApplied("sync", r.applied.applied, r.applied.tables.toList()),
                CAPABILITY_ID,
            )
        }
        val writeError = r.writeError
        if (writeError != null) {
            val message = "Changes are waiting — folder not writable ($writeError)"
            publishStatus(SyncPhase.ERROR, message)
            report(Topics.SYNC_FAILED, message)
        } else {
            _ui.value = _ui.value.copy(lastSyncMs = clock.nowEpochMillis(), sessionSyncs = _ui.value.sessionSyncs + 1)
            publishStatus(SyncPhase.OK, null)
        }
        refreshUi()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Restore (backup-restore asks; this capability owns the merge engine)
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun restore(request: BackupRestoreRequest) {
        var outcome = BackupRestoreResult(false, "Restore did not run")
        var applied: SyncRemoteApplied? = null
        val notes = ArrayList<String>()

        lock.withLock {
            outcome = try {
                val e = engine()
                safetyCopy("before-restore")?.let { notes.add("Safety copy taken before restore: ${it.name}") }
                FileSyncDb(request.databasePath).use { source ->
                    require(SyncSchema.describe(source, "tasks") != null) {
                        "This file does not contain EEVDF data"
                    }
                    val mode = if (request.mode == RestoreMode.REPLACE) SyncEngine.RestoreMode.REPLACE
                    else SyncEngine.RestoreMode.MERGE
                    val r = e.restoreFrom(source, mode, TaskRunInvariants::neutraliseRestoredRow)
                    TaskRunInvariants.enforceSingleRunningTask(db())
                    val changes = r.added + r.updated + r.removed
                    if (changes > 0) applied = SyncRemoteApplied("restore", changes, r.tablesRestored.toList())
                    if (r.tablesSkipped.isNotEmpty()) {
                        notes.add("Backup tables this app version does not have were skipped: ${r.tablesSkipped.joinToString()}")
                    }
                    BackupRestoreResult(
                        ok = true,
                        message = "Added ${r.added}, updated ${r.updated}, removed ${r.removed}",
                        added = r.added, updated = r.updated, removed = r.removed,
                    )
                }
            } catch (e: Exception) {
                // The restore runs in one transaction: a failure here means
                // nothing at all was changed.
                BackupRestoreResult(false, "Nothing was changed: ${short(e)}")
            } finally {
                for (suffix in listOf("", "-wal", "-shm", "-journal")) File(request.databasePath + suffix).delete()
            }
        }

        for (n in notes) report(Topics.SYNC_MAINTENANCE, n)
        applied?.let { bus.publish(Topics.SYNC_REMOTE_APPLIED, it, CAPABILITY_ID) }
        bus.publish(Topics.BACKUP_RESTORE_FINISHED, outcome, CAPABILITY_ID)
        // If this user is in a sync group the restored rows are now ordinary
        // local changes; send them on their way.
        if (outcome.ok && isEnabled()) syncCycle(quiet = true)
    }

    // ── Safety copies ─────────────────────────────────────────────────────────

    private fun safetyDir() = File(appContext.filesDir, "safety-copies")

    private fun safetyCopies(): List<File> =
        safetyDir().listFiles { f -> f.name.endsWith(".db") }?.sortedByDescending { it.name } ?: emptyList()

    /** Returns the copy, or null if it could not be made (never a reason to block the user). */
    private fun safetyCopy(reason: String): File? = try {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(clock.nowEpochMillis()))
        val target = File(safetyDir(), "$stamp-$reason.db")
        db().copyTo(target)
        safetyCopies().drop(KEEP_SAFETY_COPIES).forEach { it.delete() }
        target
    } catch (_: Exception) {
        null
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Plumbing
    // ═════════════════════════════════════════════════════════════════════════

    private fun db(): RoomSyncDb = RoomSyncDb(TaskDatabase.getDatabase(appContext))

    /** Call only while holding [lock]. */
    private fun engine(): SyncEngine = engine ?: SyncEngine(db(), peerId(), clock::nowEpochMillis).also {
        it.init()
        engine = it
    }

    /** Call only while holding [lock]. Throws if no usable folder is set. */
    private fun currentSession(): SyncSession {
        val dir = resolveFolder() ?: throw IllegalStateException(folderProblem())
        val existing = session
        if (existing != null && sessionDir == dir) return existing
        return SyncSession(engine(), SyncFolder(dir, peerId()), clock::nowEpochMillis, appVersion(), memberName()).also {
            session = it
            sessionDir = dir
        }
    }

    private fun joinChoice(): SyncSession.JoinChoice =
        if (prefs.getString(KEY_JOIN_MODE, null) == JoinMode.GROUP_ONLY.name) SyncSession.JoinChoice.GROUP_ONLY
        else SyncSession.JoinChoice.KEEP_MINE

    private fun hasLocalTasks(): Boolean =
        runCatching { (db().queryLong("SELECT EXISTS(SELECT 1 FROM tasks)") ?: 0L) != 0L }.getOrDefault(false)

    /**
     * This member's identity. Kept in its own preferences file — not the
     * database, and not `eevdf_prefs` — so neither a restore of the data nor
     * a restore of the settings can ever hand one user another user's id.
     */
    private fun peerId(): String {
        prefs.getString(KEY_PEER, null)?.let { return it }
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.security.SecureRandom()
        val id = buildString { repeat(10) { append(alphabet[random.nextInt(alphabet.length)]) } }
        prefs.edit().putString(KEY_PEER, id).apply()
        return id
    }

    /** "Pixel 7 · user 10" — how this member appears to the others. */
    private fun memberName(): String {
        val androidUser = android.os.Process.myUid() / 100_000
        return "${Build.MODEL} · user $androidUser"
    }

    private fun appVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun noteWritten(bytes: Long) {
        if (bytes <= 0L) return
        _ui.value = _ui.value.copy(
            lastWrittenBytes = bytes,
            sessionWrittenBytes = _ui.value.sessionWrittenBytes + bytes,
        )
    }

    private fun refreshUi() {
        val e = engine
        _ui.value = _ui.value.copy(
            enabled = isEnabled(),
            joined = runCatching { e?.isBootstrapped == true && e.isAnnounced }.getOrDefault(false),
            folderLabel = folderUri()?.let { displayPath(it) },
            memberName = memberName(),
            pendingChanges = runCatching { e?.outboxSize() ?: 0L }.getOrDefault(0L),
        )
    }

    private suspend fun publishStatus(phase: SyncPhase, message: String?) {
        val current = _ui.value
        if (current.phase == phase && current.message == message) return
        _ui.value = current.copy(phase = phase, message = message)
        bus.publish(Topics.SYNC_STATUS_CHANGED, SyncStatus(phase, message), CAPABILITY_ID)
    }

    private suspend fun report(topic: com.eevdf.kernel.eventbus.Topic<String>, text: String) {
        bus.publish(topic, text, CAPABILITY_ID)
    }

    private fun short(e: Throwable): String = (e.message ?: e.javaClass.simpleName).take(120)

    /**
     * Carries the folder chosen in the old sync over to the new one, once.
     * Sync is deliberately NOT switched on automatically: with the old
     * design every user held a full copy, and which user joins first matters
     * (see the settings screen's first-run note), so that stays a decision.
     */
    private fun migrateLegacySettings() {
        if (prefs.getBoolean(KEY_LEGACY_DONE, false)) return
        val legacy = appContext.getSharedPreferences("eevdf_prefs", Context.MODE_PRIVATE)
        val edit = prefs.edit().putBoolean(KEY_LEGACY_DONE, true)
        legacy.getString("multiuser_sync_uri", null)?.let { if (prefs.getString(KEY_FOLDER, null) == null) edit.putString(KEY_FOLDER, it) }
        edit.apply()
        legacy.edit()
            .remove("multiuser_sync_enabled").remove("multiuser_sync_last_version")
            .apply()
    }

    // ── Folder access ─────────────────────────────────────────────────────────

    /**
     * A cheap "did anything change" check: the names and sizes of the files
     * other members write. No file is opened.
     */
    private fun fingerprint(dir: File): String {
        val me = "j-${peerId()}-"
        val sb = StringBuilder()
        for (sub in listOf("journal", "snap")) {
            val files = File(File(dir, SyncFolder.DIR), sub).listFiles() ?: continue
            for (f in files.sortedBy { it.name }) {
                if (f.name.startsWith(me)) continue
                sb.append(f.name).append(':').append(f.length()).append(';')
            }
        }
        return sb.toString()
    }

    private fun folderProblem(): String = when {
        folderUri() == null -> "No sync folder chosen"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager() ->
            "Allow 'All files access' for this app so it can use the sync folder"
        else -> "Sync folder is not reachable (is the SD card inserted?)"
    }

    private fun resolveFolder(): File? {
        val uri = folderUri() ?: return null
        val file = treeUriToFile(uri) ?: return null
        return if (file.isDirectory) file else null
    }

    /**
     * Turns the folder-picker's tree URI into a real path, so files are read
     * and appended with plain java.io — an append through the Storage Access
     * Framework cannot be flushed to the card reliably.
     *
     *   primary:Download/Sync   → /storage/emulated/<user>/Download/Sync
     *   ABCD-1234:Sync          → /storage/ABCD-1234/Sync      (SD card / USB)
     */
    private fun treeUriToFile(treeUri: Uri): File? = try {
        val rawPath = treeUri.path
        if (rawPath == null || !rawPath.startsWith("/tree/")) {
            null
        } else {
            val docId = rawPath.removePrefix("/tree/").substringBefore("/document/")
            val colon = docId.indexOf(':')
            if (colon < 0) {
                null
            } else {
                val volume = docId.substring(0, colon)
                val relative = docId.substring(colon + 1)
                val root: File? = if (volume.equals("primary", ignoreCase = true)) {
                    Environment.getExternalStorageDirectory()
                } else {
                    val sm = appContext.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                    sm.storageVolumes.firstOrNull { it.uuid?.equals(volume, ignoreCase = true) == true }?.directory
                        ?: File("/storage/$volume").takeIf { it.isDirectory }
                }
                root?.let { if (relative.isEmpty()) it else File(it, relative) }
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun displayPath(uri: Uri): String {
        val docId = (uri.path ?: return uri.toString()).removePrefix("/tree/").substringBefore("/document/")
        val colon = docId.indexOf(':')
        if (colon < 0) return docId
        val volume = docId.substring(0, colon)
        val relative = docId.substring(colon + 1)
        val label = if (volume.equals("primary", ignoreCase = true)) "Internal storage" else "SD card ($volume)"
        return if (relative.isEmpty()) label else "$label / $relative"
    }

    companion object {
        const val CAPABILITY_ID = "multi-device-sync"

        private const val PREFS = "eevdf_sync"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_FOLDER = "folder_uri"
        private const val KEY_PEER = "peer_id"
        private const val KEY_JOIN_MODE = "join_mode"
        private const val KEY_LEGACY_DONE = "legacy_migrated"

        private const val POLL_MS = 10_000L
        private const val PUSH_QUIET_MS = 5L * 60L * 1000L
        private const val PUSH_MAX_WAIT_MS = 15L * 60L * 1000L
        private const val KEEP_SAFETY_COPIES = 5
    }
}
