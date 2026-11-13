package com.eevdf.capabilities.multidevicesync.logic

import com.eevdf.kernel.clock.Clock
import com.eevdf.kernel.eventbus.AlarmTimerStartRequest
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Makes an expired timer's alarm behave as ONE alarm across the synced phones.
 *
 * ── What it does ──────────────────────────────────────────────────────────────
 *
 *  STOP EVERYWHERE  When this phone's alarm is stopped (in the app, from the
 *                   notification, from the full-screen alarm, or by its own
 *                   1-hour limit), it writes "stopped" to the shared alarm
 *                   record and sends it at once. When a merge brings in
 *                   "stopped" for the alarm ringing here, it stops it here
 *                   exactly as the Stop button would ([Topics.ALARM_STOP_REQUESTED]).
 *
 *  RING MODES       Answers [Topics.ALARM_SHOULD_RING_REQUESTED] from memory
 *                   (see [RingPolicy]). Handover keeps track of the phone that
 *                   last started, paused or changed a timer.
 *
 *  APP CLOSED       While the app is not on screen (with background sync on),
 *                   a timer started or stopped on the other phone schedules or
 *                   cancels this phone's alarm, so it still rings — or stays
 *                   quiet — correctly.
 *
 * ── Wiring (rule 3) ──────────────────────────────────────────────────────────
 *
 * Bus only: listens to alarm.ringing and alarm.stopped, publishes the
 * existing alarm commands. The shared record lives in [SharedState].
 */
internal class AlarmSync(
    private val bus: EventBus,
    private val clock: Clock,
    private val scope: CoroutineScope,
    /** The sync lock: shared-table writes never overlap a sync or a Bluetooth round. */
    private val lock: Mutex,
    private val syncEnabled: () -> Boolean,
    private val database: () -> SyncDb,
    /** This phone's id (the sync folder's), or null when no folder is set. */
    private val phoneId: () -> String?,
    private val phoneName: () -> String,
    /** The phone id at the other end of a live Bluetooth link, or null. */
    private val linkedPhone: () -> String?,
    private val appOnScreen: () -> Boolean,
    /** Gets local changes to the other phone(s) promptly. */
    private val sendSoon: () -> Unit,
    private val report: suspend (String) -> Unit,
) {
    private data class Ringing(val alarmId: String, val taskId: String, val name: String)

    // Memory-only copies, so the ring question is answered without disk access
    // (it is asked on the alarm's own path, which must never wait on storage).
    @Volatile private var ringMode: RingMode = RingMode.SWARM
    @Volatile private var ownerDevice: String? = null
    @Volatile var ownerName: String? = null
        private set
    @Volatile private var myDevice: String? = null
    @Volatile private var lastRun: SharedState.RunningTask? = null
    @Volatile private var lastRunStopped = false
    @Volatile private var ringing: Ringing? = null
    /** The run this phone's alarm was last scheduled for while the app was closed. */
    @Volatile private var scheduledRunId: String? = null
    private var ownerJob: Job? = null
    @Volatile private var lastPrune = 0L

    val mode: RingMode get() = ringMode

    fun start() {
        bus.respondTo(Topics.ALARM_SHOULD_RING_REQUESTED, CAPABILITY_ID) { taskName -> shouldRing(taskName) }
        bus.subscribe(Topics.ALARM_RINGING, CAPABILITY_ID) { event ->
            scope.launch { onRinging(event.taskName) }
        }
        bus.subscribe(Topics.ALARM_STOPPED, CAPABILITY_ID) { taskName ->
            scope.launch { onStopped(taskName) }
        }
    }

    // ── The ring question ─────────────────────────────────────────────────────

    fun shouldRing(taskName: String): Boolean {
        if (!syncEnabled()) return true
        val run = lastRun?.takeIf { it.name == taskName }
        val owner = ownerDevice
        return RingPolicy.shouldRing(
            mode = ringMode,
            myDevice = myDevice,
            ownerDevice = owner,
            ownerReachable = owner != null && linkedPhone() == owner,
            alarmStoppedElsewhere = run != null && lastRunStopped,
        )
    }

    // ── Local events ──────────────────────────────────────────────────────────

    private suspend fun onRinging(taskName: String) {
        if (!syncEnabled()) return
        val run = lastRun?.takeIf { it.name == taskName }
            ?: runCatching { SharedState(database()).runningTask() }.getOrNull()?.takeIf { it.name == taskName }
            ?: return // not a run the shared data knows: nothing to coordinate
        val r = Ringing(run.alarmId, run.taskId, run.name)
        ringing = r
        val alreadyStopped = lock.withLock {
            val shared = SharedState(database())
            shared.raise(r.alarmId, r.taskId, r.name, clock.nowEpochMillis())
            shared.alarm(r.alarmId)?.stopped == true
        }
        if (alreadyStopped) stopHere(r, "already stopped on another phone")
    }

    private suspend fun onStopped(taskName: String) {
        val r = ringing ?: return
        if (taskName.isNotEmpty() && taskName != r.name) return
        ringing = null // before writing: our own echo of a remote stop must not write again
        if (!syncEnabled()) return
        val wrote = lock.withLock {
            SharedState(database()).stop(r.alarmId, r.taskId, r.name, clock.nowEpochMillis(), myDevice ?: "?")
        }
        if (wrote) sendSoon()
        lastRunStopped = true
    }

    /** Stops the alarm on this phone because it was stopped elsewhere. */
    private suspend fun stopHere(r: Ringing, why: String) {
        if (ringing?.alarmId != r.alarmId) return
        ringing = null
        bus.publish(Topics.ALARM_STOP_REQUESTED, Unit, CAPABILITY_ID)
        report("Alarm for \"${r.name}\" $why")
    }

    /** A task was saved here: this phone is now the one in use (Handover). */
    fun onLocalAction() {
        if (!syncEnabled()) return
        ownerJob?.cancel()
        ownerJob = scope.launch {
            delay(OWNER_DEBOUNCE_MS)
            val me = phoneId() ?: return@launch
            lock.withLock { SharedState(database()).setRingOwner(me, phoneName()) }
            refresh()
        }
    }

    /** The app is leaving the screen: remember which run this phone's alarm is set for. */
    fun onBackground() {
        scheduledRunId = lastRun?.alarmId
    }

    suspend fun setRingMode(mode: RingMode) {
        lock.withLock { SharedState(database()).setRingMode(mode) }
        refresh()
        sendSoon()
    }

    // ── After changes arrived ─────────────────────────────────────────────────

    /** Re-reads the shared settings and the running task into memory. */
    suspend fun refresh() {
        try {
            myDevice = phoneId()
            val shared = SharedState(database())
            ringMode = shared.ringMode()
            val (owner, name) = shared.ringOwner()
            ownerDevice = owner
            ownerName = name
            val run = shared.runningTask()
            // Sticky: after expiry the task row is reset, but the ringing that
            // follows must still be matched to the run that just ended.
            if (run != null) lastRun = run
            lastRunStopped = lastRun?.let { shared.alarm(it.alarmId)?.stopped } == true
            val now = clock.nowEpochMillis()
            if (now - lastPrune > PRUNE_EVERY_MS) {
                lastPrune = now
                lock.withLock { shared.prune(now - KEEP_ALARMS_MS) }
            }
        } catch (_: Exception) {
            // never let bookkeeping break a sync
        }
    }

    /** Call after a merge brought in changes. */
    suspend fun afterMerge() {
        refresh()
        val r = ringing
        if (r != null) {
            val row = runCatching { SharedState(database()).alarm(r.alarmId) }.getOrNull()
            // Any "stopped" counts — including one written by another Android
            // user of this same phone (same phone id). This phone's own stop
            // never gets here: it clears [ringing] before writing.
            if (row?.stopped == true) stopHere(r, "stopped elsewhere")
        }
        if (!appOnScreen()) keepAlarmInStep()
    }

    /**
     * App closed: the task list screen is not around to reschedule this phone's
     * alarm, so do the two things it would have done after a merge.
     */
    private suspend fun keepAlarmInStep() {
        if (ringing != null) return
        val run = runCatching { SharedState(database()).runningTask() }.getOrNull()
        val now = clock.nowEpochMillis()
        if (run != null && run.alarmId != scheduledRunId) {
            val left = run.remainingSeconds(now)
            if (left > 0) {
                bus.publish(
                    Topics.ALARM_TIMER_START_REQUESTED,
                    AlarmTimerStartRequest(run.name, left, run.taskType, left),
                    CAPABILITY_ID,
                )
                scheduledRunId = run.alarmId
            }
        } else if (run == null && scheduledRunId != null) {
            // Paused or stopped on the other phone: this phone must not ring for it.
            bus.publish(Topics.ALARM_TIMER_PAUSE_REQUESTED, Unit, CAPABILITY_ID)
            scheduledRunId = null
        }
    }

    private companion object {
        const val CAPABILITY_ID = "multi-device-sync"
        const val OWNER_DEBOUNCE_MS = 1_000L
        const val PRUNE_EVERY_MS = 24L * 60L * 60L * 1000L
        const val KEEP_ALARMS_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
