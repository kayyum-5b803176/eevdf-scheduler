package com.eevdf.scheduler.ui.autoswitch

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
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.eevdf.scheduler.R

/**
 * Foreground service that owns the floating call-state bubble overlay.
 *
 * Lifecycle:
 *   Start  — [TaskCallSwitchDelegate] sends [ACTION_CALL_STARTED] when a call begins.
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
            // Fixed mode — simple click listener
            view.setOnClickListener { BubbleEventBus.onBubbleTap?.invoke() }
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
                        BubbleEventBus.onBubbleTap?.invoke()
                    } else {
                        AutoSwitchPrefs.saveBubblePosition(this, lp.x, lp.y)
                    }
                    moved                    // consume only if we dragged
                }
                else -> false
            }
        }
    }

    // ── Dot colour ────────────────────────────────────────────────────────────

    /**
     * Reads [BubbleEventBus.timerRunning] and tints the status dot:
     *   running → green #4CAF50
     *   paused  → amber #FF9800
     */
    private fun updateDotColor(view: View? = bubbleView) {
        val dot = view?.findViewById<View>(R.id.bubbleDot) ?: return
        val color = if (BubbleEventBus.timerRunning)
            Color.parseColor("#4CAF50")
        else
            Color.parseColor("#FF9800")
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
            .setContentText("Tap the bubble to pause / resume your call task")
            .setSmallIcon(R.drawable.outline_skip_next_24)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
}
