package com.eevdf.app.feature.autoswitch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.eevdf.app.R
import com.eevdf.data.task.TaskDatabase
import com.eevdf.data.task.TaskRepository
import com.eevdf.data.runlog.RunSession
import com.eevdf.app.feature.task.timer.TimerState
import com.eevdf.app.feature.task.timer.timerState
import com.eevdf.app.feature.task.timer.withTimerState
import com.eevdf.app.feature.alarm.AlarmForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the floating call-state bubble overlay.
 *
 * Lifecycle:
 *   Start  — [TaskCallSwitchDelegate] sends [ACTION_CALL_STARTED] when a call begins.
 *            Also sent by MainActivity when a call arrives and any timer is running
 *            (new mechanism: show bubble even before the call task is running).
 *   Poll   — every [POLL_MS] ms, checks the foreground package via UsageStats;
 *             shows or hides the bubble accordingly.
 *   Stop   — [ACTION_CALL_ENDED] hides the bubble and calls stopSelf().
 *
 * Show condition (all must be true):
 *   1. A call is currently active.
 *   2. [Settings.canDrawOverlays] is granted.
 *   3. The foreground package is NOT the EEVDF app itself.
 *   4. The configured app-list is either empty (show on any app) OR contains
 *      the foreground package.
 *
 * Bubble colour:
 *   green (#4CAF50) — call-assigned task timer is the active timer
 *                     (tap will pause/resume the call task)
 *   blue  (#1565C0) — another task timer is running
 *                     (tap will switch to the call task)
 *
 * Tap behaviour:
 *   Fires [BubbleEventBus.onBubbleTap] which is wired in MainActivity to
 *   [TaskViewModel.handleBubbleTap] — that method decides whether to
 *   pause-current-and-start-call-task or toggle-call-task-timer.
 *
 * Haptic feedback:
 *   A short confirmation buzz is produced on every tap so the user gets
 *   tactile acknowledgement even while looking at another app.
 *
 * Timer state / tap are communicated through [BubbleEventBus] — a volatile
 * in-process singleton — so no IPC or broadcast overhead is needed.
 */
class BubbleOverlayService : Service() {

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        /** Send to start the service and signal a call began. */
        const val ACTION_CALL_STARTED = "com.eevdf.bubble.CALL_STARTED"
        /** Send to signal the call ended; service will clean up and stop. */
        const val ACTION_CALL_ENDED   = "com.eevdf.bubble.CALL_ENDED"

        private const val CHANNEL_ID = "eevdf_bubble_channel"
        private const val NOTIF_ID   = 9_002
        private const val POLL_MS    = 1_000L
        private const val EEVDF_PKG  = "com.eevdf.scheduler"

        /** True while the service is alive — lets MainActivity skip state pushes. */
        @Volatile var isRunning: Boolean = false
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private lateinit var windowManager: WindowManager
    private var bubbleView:   View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var callActive = false

    private val handler = Handler(Looper.getMainLooper())
    private val job     = SupervisorJob()
    private val scope   = CoroutineScope(Dispatchers.IO + job)
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (callActive) recheckVisibility()
            updateDotColor()                    // always sync colour from bus
            handler.postDelayed(this, POLL_MS)
        }
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning     = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        handler.post(pollRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CALL_STARTED -> { callActive = true;  recheckVisibility() }
            ACTION_CALL_ENDED   -> { callActive = false; hideBubble();  stopSelf() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(pollRunnable)
        hideBubble()
        scope.cancel()
    }

    // ── Visibility decision ───────────────────────────────────────────────────

    private fun recheckVisibility() {
        if (!callActive || !Settings.canDrawOverlays(this)) {
            hideBubble(); return
        }
        val foreground = getForegroundPackage() ?: run { hideBubble(); return }
        if (foreground == EEVDF_PKG) { hideBubble(); return }

        val appList   = AutoSwitchPrefs.getBubbleAppList(this)
        val shouldShow = appList.isEmpty() || foreground in appList

        if (shouldShow) showBubble() else hideBubble()
    }

    // ── Bubble window ─────────────────────────────────────────────────────────

    private fun showBubble() {
        if (bubbleView?.isAttachedToWindow == true) return   // already visible

        val lp   = buildLayoutParams()
        layoutParams = lp
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
        bubbleView = view

        updateDotColor(view)
        wireTouch(view, lp)

        windowManager.addView(view, lp)
    }

    private fun hideBubble() {
        val v = bubbleView ?: return
        if (v.isAttachedToWindow) {
            try { windowManager.removeView(v) } catch (_: Exception) { /* view already detached */ }
        }
        bubbleView = null
    }

    // ── Layout params ─────────────────────────────────────────────────────────

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val savedX = AutoSwitchPrefs.getBubbleX(this)
        val savedY = AutoSwitchPrefs.getBubbleY(this)

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).also { lp ->
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x       = if (savedX >= 0) savedX else 0
            lp.y       = if (savedY >= 0) savedY else 200
        }
    }

    // ── Touch wiring ──────────────────────────────────────────────────────────

    private fun wireTouch(view: View, lp: WindowManager.LayoutParams) {
        if (!AutoSwitchPrefs.isBubbleDraggable(this)) {
            // Fixed mode — simple click listener with haptic
            view.setOnClickListener {
                performHapticFeedback()
                dispatchTap()
            }
            return
        }

        // Draggable mode — distinguish tap (no move) from drag
        var initX      = 0;    var initY      = 0
        var initTouchX = 0f;   var initTouchY = 0f
        var moved      = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = lp.x;          initY = lp.y
                    initTouchX = event.rawX; initTouchY = event.rawY
                    moved = false
                    false                    // let click listener fire on a clean tap
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initTouchX).toInt()
                    val dy = (event.rawY - initTouchY).toInt()
                    if (!moved && (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8))
                        moved = true
                    if (moved) {
                        lp.x = initX + dx
                        lp.y = initY + dy
                        if (bubbleView?.isAttachedToWindow == true)
                            windowManager.updateViewLayout(view, lp)
                    }
                    moved                    // consume only if actually dragging
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        performHapticFeedback()
                        dispatchTap()
                    } else {
                        AutoSwitchPrefs.saveBubblePosition(this, lp.x, lp.y)
                    }
                    moved                    // consume only if we dragged
                }
                else -> false
            }
        }
    }

    // ── Tap dispatch ──────────────────────────────────────────────────────────

    /**
     * Single dispatch point for all bubble taps.
     *
     * [BubbleEventBus.onBubbleTap] is non-null only while MainActivity is in
     * STARTED/RESUMED state (set in onStart, cleared in onStop).  When it is
     * non-null the Activity is on screen, its LiveData observers are active, and
     * the ViewModel's in-memory timer engine is authoritative — use that path.
     *
     * When null (Activity backgrounded or dead) the observer chain is broken:
     * timerCardAction won't fire, callTaskRunning won't update, and the ViewModel
     * state may be stale from before syncFromDb() last ran.  Fall through to
     * [handleTapInBackground] which reads and writes Room directly, credits
     * vruntime + RunLog, and updates BubbleEventBus fields immediately.
     */
    private fun dispatchTap() {
        val tap = BubbleEventBus.onBubbleTap
        if (tap != null) {
            tap.invoke()          // Activity visible — ViewModel path
        } else {
            handleTapInBackground()   // Activity gone — direct DB path
        }
    }

    /**
     * Handles a bubble tap entirely in the background without the Activity.
     *
     * Decision table (mirrors TaskViewModel.handleBubbleTap):
     *   Running task == call task → pause call task             (green → blue)
     *   Running task != call task → pause it, start call task   (blue → green)
     *   No task running           → start call task             (blue → green)
     *
     * vruntime and RunLog are credited on every pause — same pattern as
     * CallSwitchService — so stats are never lost regardless of Activity state.
     * BubbleEventBus.callTaskRunning is updated synchronously before the handler
     * post so updateDotColor() sees the new value on the very next frame.
     */
    private fun handleTapInBackground() {
        val callTaskId = AutoSwitchPrefs.getCallTaskId(this) ?: return
        scope.launch {
            val db   = TaskDatabase.getDatabase(this@BubbleOverlayService)
            val repo = TaskRepository(db.taskDao(), this@BubbleOverlayService)
            val nowMs = System.currentTimeMillis()

            val runningTask = repo.getRunningTask()

            if (runningTask?.id == callTaskId) {
                // ── Call task is running → pause it ──────────────────────────
                val startEpoch = runningTask.startTimeEpoch
                val paused     = runningTask.withTimerState(
                    TimerState.pause(runningTask.timerState, nowMs))
                repo.update(paused)

                val session = RunSession.Paused(runningTask.id, startEpoch, nowMs)
                if (session.wallClockSeconds > 0 && startEpoch > 0L)
                    repo.updateVruntimeAfterRun(paused, session)

                BubbleEventBus.callTaskRunning = false
                BubbleEventBus.anyTimerRunning = false
                BubbleEventBus.timerRunning    = false
                AlarmForegroundService.timerPause(this@BubbleOverlayService)

            } else {
                // ── Switch to call task ───────────────────────────────────────
                if (runningTask != null) {
                    val startEpoch = runningTask.startTimeEpoch
                    val paused     = runningTask.withTimerState(
                        TimerState.pause(runningTask.timerState, nowMs))
                    repo.update(paused)

                    val session = RunSession.Paused(runningTask.id, startEpoch, nowMs)
                    if (session.wallClockSeconds > 0 && startEpoch > 0L)
                        repo.updateVruntimeAfterRun(paused, session)
                }

                val callTask = repo.getTaskById(callTaskId)
                if (callTask == null || callTask.isCompleted) return@launch

                val runState       = TimerState.resume(callTask.timerState, nowMs)
                val runningCallTask = callTask.withTimerState(runState)
                repo.update(runningCallTask)

                BubbleEventBus.callTaskRunning = true
                BubbleEventBus.anyTimerRunning = true
                BubbleEventBus.timerRunning    = true
                AlarmForegroundService.timerStart(
                    this@BubbleOverlayService,
                    callTask.name,
                    runningCallTask.remainingSeconds,
                    callTask.taskType,
                    runningCallTask.remainingSeconds
                )
            }

            // Force dot repaint immediately — don't wait for the next poll tick
            handler.post { updateDotColor() }
        }
    }

    // ── Haptic feedback ───────────────────────────────────────────────────────

    /**
     * Short confirmation buzz on bubble tap.
     * Uses EFFECT_CLICK (API 29+) for a crisp tick, falls back to a 40ms
     * pulse on older devices.  The vibration is intentionally brief so it
     * feels like acknowledgement, not an alarm.
     */
    private fun performHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator?.let { vib ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                    } else {
                        @Suppress("DEPRECATION")
                        vib.vibrate(40L)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vib?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } else {
                    @Suppress("DEPRECATION")
                    vib?.vibrate(40L)
                }
            }
        } catch (_: Exception) { /* Vibrator not available — silent fallback */ }
    }

    // ── Dot colour ────────────────────────────────────────────────────────────

    /**
     * Reads [BubbleEventBus.callTaskRunning] and tints the status dot:
     *
     *   callTaskRunning = true  → green (#4CAF50)
     *     The call-assigned task is the active timer.
     *     Tap will pause / resume that task directly.
     *
     *   callTaskRunning = false → blue (#1565C0)
     *     Another task is running (or timer is paused).
     *     Tap will interrupt current work and switch to the call task.
     *
     * The colour difference lets the user immediately tell "am I on the
     * call task?" without reading any text label on the bubble.
     */
    private fun updateDotColor(view: View? = bubbleView) {
        val dot = view?.findViewById<View>(R.id.bubbleDot) ?: return
        val color = if (BubbleEventBus.callTaskRunning)
            Color.parseColor("#4CAF50")   // green  — call task is active
        else
            Color.parseColor("#1565C0")   // blue   — another task / switch needed
        dot.backgroundTintList = ColorStateList.valueOf(color)
    }

    // ── Foreground app detection ──────────────────────────────────────────────

    /**
     * Queries [UsageStatsManager] for the most-recently-used app in the last
     * 5 seconds — a reliable proxy for the current foreground package.
     * Returns null if the PACKAGE_USAGE_STATS permission is not granted or the
     * usage-stats list is empty.
     */
    private fun getForegroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now   = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 5_000L, now)
        return stats?.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Call Bubble", NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java)
                    ?.createNotificationChannel(it) }
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EEVDF – call bubble active")
            .setContentText("Tap the bubble to switch between call task and current task")
            .setSmallIcon(R.drawable.outline_skip_next_24)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
}
