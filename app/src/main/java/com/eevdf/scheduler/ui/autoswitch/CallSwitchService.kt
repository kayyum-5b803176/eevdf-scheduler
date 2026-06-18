package com.eevdf.scheduler.ui.autoswitch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.eevdf.scheduler.R
import com.eevdf.scheduler.db.TaskDatabase
import com.eevdf.scheduler.db.TaskRepository
import com.eevdf.scheduler.model.timer.TimerState
import com.eevdf.scheduler.model.timer.timerState
import com.eevdf.scheduler.model.runlog.RunSession
import com.eevdf.scheduler.model.task.Task
import com.eevdf.scheduler.model.timer.withTimerState
import com.eevdf.scheduler.ui.alarm.AlarmForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Background foreground-service that performs call-task switching without
 * requiring MainActivity to be alive.
 *
 * ── Why a Service? ────────────────────────────────────────────────────────────
 *
 * The original flow was:
 *   CallStateReceiver → CallEvents.postValue() → MainActivity observer
 *   → TaskCallSwitchDelegate → pauseTimer() / startTimer()
 *
 * This chain has two failure modes:
 *   1. MainActivity is dead (app backgrounded, process cached) — LiveData
 *      observers don't fire; the switch never happens.
 *   2. A call arrives while the app is in the background — the process may
 *      be alive but the Activity is stopped; postValue() queues but the
 *      observer won't fire until onStart(), i.e. never unless the user opens
 *      the app.
 *
 * This service fixes both by running the switch directly against Room in
 * a coroutine, then posting to [CallEvents] so that IF MainActivity is alive,
 * its ViewModel in-memory state (savedTaskBeforeCall etc.) stays consistent
 * with what we wrote to DB.
 *
 * ── "Open once" requirement ───────────────────────────────────────────────────
 *
 * The user wants to open the app once so it registers its LiveData observers,
 * then never need to open it again for subsequent calls.  After the first open,
 * MainActivity calls [TaskViewModel.syncFromDb] on every onResume() so the
 * ViewModel picks up whatever DB state this service wrote while the app was
 * backgrounded.  The service does the DB work; the ViewModel reconciles.
 *
 * ── Thread safety ─────────────────────────────────────────────────────────────
 *
 * All DB work runs on [Dispatchers.IO].  LiveData posts use postValue() so they
 * are main-thread safe.  The service is single-instance (START_NOT_STICKY) and
 * stops itself after each operation to avoid accumulating state.
 *
 * ── Lifecycle ─────────────────────────────────────────────────────────────────
 *
 *   CALL_STARTED intent → onStartCommand:
 *     1. Read running task from DB; snapshot its elapsed time → Paused state.
 *     2. Write Paused task back to DB.
 *     3. Read call task from DB.
 *     4. Write Running state for call task to DB.
 *     5. Start AlarmForegroundService with call task countdown.
 *     6. Start BubbleOverlayService (if bubble enabled).
 *     7. Post CallEvents.CALL_STARTED so ViewModel in-memory state syncs.
 *     8. stopSelf().
 *
 *   CALL_ENDED intent → onStartCommand:
 *     1. Read call task from DB; pause it.
 *     2. Read saved-task-id from prefs (written here on CALL_STARTED).
 *     3. If saved task exists, restore it (resume if wasRunning).
 *     4. Stop AlarmForegroundService.
 *     5. Stop BubbleOverlayService.
 *     6. Post CallEvents.CALL_ENDED.
 *     7. stopSelf().
 */
class CallSwitchService : Service() {

    companion object {
        const val ACTION_CALL_STARTED = "com.eevdf.callswitch.CALL_STARTED"
        const val ACTION_CALL_ENDED   = "com.eevdf.callswitch.CALL_ENDED"

        private const val CHANNEL_ID = "eevdf_callswitch_channel"
        private const val NOTIF_ID   = 9_003

        // Prefs keys for state we need to persist across the call
        private const val KEY_SAVED_TASK_ID   = "cs_saved_task_id"
        private const val KEY_WAS_RUNNING      = "cs_was_running"

        fun intentStarted(ctx: Context) =
            Intent(ctx, CallSwitchService::class.java).apply { action = ACTION_CALL_STARTED }

        fun intentEnded(ctx: Context) =
            Intent(ctx, CallSwitchService::class.java).apply { action = ACTION_CALL_ENDED }
    }

    private val job   = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Handling call…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CALL_STARTED -> handleCallStarted(startId)
            ACTION_CALL_ENDED   -> handleCallEnded(startId)
            else                -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ── Call started ──────────────────────────────────────────────────────────

    private fun handleCallStarted(startId: Int) {
        val callTaskId = AutoSwitchPrefs.getCallTaskId(this)
        if (callTaskId == null) { stopSelf(startId); return }

        scope.launch {
            val db   = TaskDatabase.getDatabase(this@CallSwitchService)
            val repo = TaskRepository(db.taskDao(), this@CallSwitchService)

            val nowMs    = System.currentTimeMillis()

            // ── 1. Pause currently running task (if any) ─────────────────────
            val running = repo.getRunningTask()
            val savedTaskId: String?
            val wasRunning: Boolean

            if (running != null && running.id != callTaskId) {
                // Snapshot elapsed time at the moment the call arrived.
                // We need the startTimeEpoch BEFORE withTimerState overwrites it,
                // so read it from the DB row directly here.
                val startEpoch  = running.startTimeEpoch   // epoch when timer last started
                val pausedState = TimerState.pause(running.timerState, nowMs)
                val pausedTask  = running.withTimerState(pausedState)
                repo.update(pausedTask)

                // ── Credit vruntime + RunLog for the interrupted task ─────────
                // This is what TaskViewModel.pauseTimer() does via applyVruntimeUpdate().
                // CallSwitchService bypasses the ViewModel, so we must do it here
                // or the session is silently lost — vruntime, trt, and RunLog never
                // reflect this run.
                val session = RunSession.Paused(
                    taskId       = running.id,
                    startEpochMs = startEpoch,
                    endEpochMs   = nowMs
                )
                if (session.wallClockSeconds > 0 && startEpoch > 0L) {
                    repo.updateVruntimeAfterRun(pausedTask, session)
                }

                savedTaskId = running.id
                wasRunning  = true
            } else {
                // No timer was running, or the call task was already running
                savedTaskId = null
                wasRunning  = false
            }

            // Persist so CALL_ENDED can restore even if the process is killed
            saveSwitchState(savedTaskId, wasRunning)

            // ── 2. Start call task timer ──────────────────────────────────────
            val callTask = repo.getTaskById(callTaskId)
            if (callTask == null || callTask.isCompleted) {
                stopSelf(startId)
                return@launch
            }

            // Only switch if call task is not already running
            if (callTask.isRunning) {
                // Already running (user started it manually before call) — just show bubble
            } else {
                val runningState = TimerState.resume(callTask.timerState, nowMs)
                val runningTask  = callTask.withTimerState(runningState)
                repo.update(runningTask)

                // Update AlarmForegroundService notification to show call task countdown
                AlarmForegroundService.timerStart(
                    this@CallSwitchService,
                    callTask.name,
                    runningTask.remainingSeconds,
                    callTask.taskType,
                    runningTask.remainingSeconds
                )
            }

            // ── 3. Start bubble overlay ───────────────────────────────────────
            if (AutoSwitchPrefs.isBubbleEnabled(this@CallSwitchService)) {
                androidx.core.content.ContextCompat.startForegroundService(
                    this@CallSwitchService,
                    Intent(this@CallSwitchService, BubbleOverlayService::class.java)
                        .apply { action = BubbleOverlayService.ACTION_CALL_STARTED }
                )
            }

            // ── 4. Sync BubbleEventBus ────────────────────────────────────────
            BubbleEventBus.anyTimerRunning  = true
            BubbleEventBus.callTaskRunning  = true
            BubbleEventBus.timerRunning     = true

            // ── 5. Notify ViewModel (if Activity is alive) ────────────────────
            // CallEvents uses postValue so it's safe from a background thread.
            // If MainActivity is dead this is a no-op; ViewModel will reconcile
            // via syncFromDb() on the next onResume().
            CallEvents.event.postValue(CallEvents.Type.CALL_STARTED)

            stopSelf(startId)
        }
    }

    // ── Call ended ────────────────────────────────────────────────────────────

    private fun handleCallEnded(startId: Int) {
        val callTaskId = AutoSwitchPrefs.getCallTaskId(this)

        scope.launch {
            val db   = TaskDatabase.getDatabase(this@CallSwitchService)
            val repo = TaskRepository(db.taskDao(), this@CallSwitchService)

            val nowMs = System.currentTimeMillis()

            // ── 1. Pause call task ────────────────────────────────────────────
            if (callTaskId != null) {
                val callTask = repo.getRunningTask()
                    ?.takeIf { it.id == callTaskId }
                    ?: repo.getTaskById(callTaskId)
                if (callTask != null && callTask.isRunning) {
                    val startEpoch = callTask.startTimeEpoch
                    val paused     = callTask.withTimerState(TimerState.pause(callTask.timerState, nowMs))
                    repo.update(paused)

                    // Credit vruntime + RunLog for the call task session
                    val session = RunSession.Paused(
                        taskId       = callTask.id,
                        startEpochMs = startEpoch,
                        endEpochMs   = nowMs
                    )
                    if (session.wallClockSeconds > 0 && startEpoch > 0L) {
                        repo.updateVruntimeAfterRun(paused, session)
                    }
                }
            }

            // ── 2. Restore previous task ──────────────────────────────────────
            val (savedTaskId, wasRunning) = loadSwitchState()
            clearSwitchState()

            if (savedTaskId != null) {
                val savedTask = repo.getTaskById(savedTaskId)
                if (savedTask != null && !savedTask.isCompleted) {
                    if (wasRunning) {
                        val resumed = savedTask.withTimerState(TimerState.resume(savedTask.timerState, nowMs))
                        repo.update(resumed)
                        AlarmForegroundService.timerStart(
                            this@CallSwitchService,
                            savedTask.name,
                            resumed.remainingSeconds,
                            savedTask.taskType,
                            resumed.remainingSeconds
                        )
                    }
                    // If paused before — leave it paused (no AlarmForegroundService start)
                } else {
                    // Saved task gone — stop foreground service notification
                    AlarmForegroundService.timerPause(this@CallSwitchService)
                }
            } else {
                AlarmForegroundService.timerPause(this@CallSwitchService)
            }

            // ── 3. Stop bubble ────────────────────────────────────────────────
            this@CallSwitchService.startService(
                Intent(this@CallSwitchService, BubbleOverlayService::class.java)
                    .apply { action = BubbleOverlayService.ACTION_CALL_ENDED }
            )

            // ── 4. Sync BubbleEventBus ────────────────────────────────────────
            val restoredRunning = wasRunning && savedTaskId != null
            BubbleEventBus.anyTimerRunning = restoredRunning
            BubbleEventBus.callTaskRunning = false
            BubbleEventBus.timerRunning    = restoredRunning

            // ── 5. Notify ViewModel ───────────────────────────────────────────
            CallEvents.event.postValue(CallEvents.Type.CALL_ENDED)

            stopSelf(startId)
        }
    }

    // ── Switch-state persistence ──────────────────────────────────────────────

    /**
     * Persists the pre-call state across process death.
     * Uses a dedicated SharedPreferences file separate from AutoSwitchPrefs
     * so they don't collide on key names.
     */
    private fun saveSwitchState(savedTaskId: String?, wasRunning: Boolean) {
        getSharedPreferences("call_switch_state", Context.MODE_PRIVATE).edit()
            .putString(KEY_SAVED_TASK_ID, savedTaskId)
            .putBoolean(KEY_WAS_RUNNING, wasRunning)
            .apply()
    }

    private fun loadSwitchState(): Pair<String?, Boolean> {
        val prefs = getSharedPreferences("call_switch_state", Context.MODE_PRIVATE)
        return Pair(
            prefs.getString(KEY_SAVED_TASK_ID, null),
            prefs.getBoolean(KEY_WAS_RUNNING, false)
        )
    }

    private fun clearSwitchState() {
        getSharedPreferences("call_switch_state", Context.MODE_PRIVATE).edit()
            .remove(KEY_SAVED_TASK_ID)
            .remove(KEY_WAS_RUNNING)
            .apply()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Call Switch", NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java)
                    ?.createNotificationChannel(it) }
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EEVDF – call task switch")
            .setContentText(text)
            .setSmallIcon(R.drawable.outline_skip_next_24)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(false)
            .build()
}
