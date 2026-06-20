package com.eevdf.app.feature.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.eevdf.app.R
import com.google.android.material.button.MaterialButton
import com.eevdf.app.feature.notification.NotificationHelper

/**
 * Full-screen alarm activity — shown over the lock screen when a task timer expires.
 * Behaves like Google Clock's alarm screen: wakes the display, shows over other apps,
 * and keeps ringing until the user taps Stop.
 */
class AlarmActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TASK_NAME = "task_name"

        /** Intent extra MainActivity reads to perform a "stop and start" restart. */
        const val EXTRA_RESTART_AFTER_EXPIRE = "restart_after_expire"

        /** Launch this activity from anywhere (notification, broadcast). */
        fun createIntent(context: Context, taskName: String): Intent =
            Intent(context, AlarmActivity::class.java).apply {
                putExtra(EXTRA_TASK_NAME, taskName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
            }
    }

    private lateinit var tvTaskName: TextView
    private lateinit var tvElapsed: TextView
    private lateinit var btnStop: MaterialButton

    private val handler = Handler(Looper.getMainLooper())
    private var elapsedSeconds = 0L

    private val elapsedRunnable = object : Runnable {
        override fun run() {
            elapsedSeconds++
            tvElapsed.text = NotificationHelper.formatElapsed(elapsedSeconds)
            handler.postDelayed(this, 1000L)
        }
    }

    /** Listens for the ViewModel stopping the alarm (e.g. user taps Stop in MainActivity) */
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finish()
        }
    }

    /**
     * Screen-off detection — the AOSP DeskClock approach to the Power button.
     *
     * A normal app cannot intercept KEYCODE_POWER: Android's window-policy layer
     * turns the screen off before any app window sees the key.  What an app CAN
     * observe is the resulting Intent.ACTION_SCREEN_OFF broadcast.  DeskClock
     * registers for it on its alarm screen and maps it to the configured
     * power-button behaviour.  We do the same: when the Power key is bound to a
     * timer-expire action and the screen goes off while the alarm is ringing, we
     * run that action.
     *
     * Must be registered dynamically — ACTION_SCREEN_OFF is not deliverable to a
     * manifest-declared receiver.
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return
            // Only react if the user actually assigned the Power key to an action.
            val action = com.eevdf.app.feature.settings.HardwareKeyPrefs
                .actionForKey(context, com.eevdf.app.feature.settings.HardwareKeyPrefs.KEY_POWER)
            if (action == com.eevdf.app.feature.settings.HardwareKeyPrefs.ACTION_NONE) return
            handleExpireKey(com.eevdf.app.feature.settings.HardwareKeyPrefs.KEY_POWER)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake screen + show over lock screen — same flags Google Clock uses
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_alarm)

        tvTaskName = findViewById(R.id.tvAlarmActivityTaskName)
        tvElapsed  = findViewById(R.id.tvAlarmActivityElapsed)
        btnStop    = findViewById(R.id.btnAlarmActivityStop)

        val taskName = intent.getStringExtra(EXTRA_TASK_NAME) ?: "Task"
        tvTaskName.text = taskName
        tvElapsed.text  = "0:00"

        btnStop.setOnClickListener {
            // Tell the notification Stop receiver to cancel everything
            val stopIntent = Intent(this, AlarmStopReceiver::class.java).apply {
                action = AlarmStopReceiver.ACTION_TIMER_EXPIRED
            }
            sendBroadcast(stopIntent)
            finish()
        }

        handler.postDelayed(elapsedRunnable, 1000L)

        // Register screen-off detection for the whole visible lifetime of the
        // alarm.  Registered here (not in onResume) because the screen turning off
        // drives the activity into onPause/onStop — we must still be listening at
        // that moment to catch ACTION_SCREEN_OFF.  Unregistered in onDestroy.
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(AlarmStopReceiver.ACTION_STOP_ALARM)
            addAction(AlarmForegroundService.ACTION_ALARM_DONE)
        }
        ContextCompat.registerReceiver(
            this,
            stopReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stopReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(elapsedRunnable)
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
    }

    /**
     * Hardware-key handling while the full-screen alarm is showing (e.g. over the
     * lock screen, when MainActivity is not foreground).  Mirrors MainActivity's
     * dispatcher: the pressed key's configured action runs only here, during the
     * expire event.
     *
     * STOP tears the alarm down via the existing receiver.  RESTART tears it down
     * and asks MainActivity to start a fresh slice for the expired task.
     */
    /**
     * Earliest key hook — runs before onKeyDown/onKeyUp and before the view
     * hierarchy.  We route hardware keys here so we get the best chance of
     * catching them, including a best-effort attempt at the Power key.
     *
     * IMPORTANT (Bug fix — power button turned the screen off instead of acting):
     * On the vast majority of Android devices the Power key is consumed by the
     * system's window-policy layer (PhoneWindowManager) BEFORE any app window
     * sees it, so the screen turns off and this method is never even called for
     * KEYCODE_POWER.  A normal (non-system) app cannot override that.  We handle
     * power here when the device *does* deliver it, and consume it so it does not
     * additionally fall through to default handling — but reliable power-button
     * control requires system privileges we do not have, which is surfaced to the
     * user in the Hardware Keys settings screen.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val keyId = when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP   ->
                    com.eevdf.app.feature.settings.HardwareKeyPrefs.KEY_VOLUME_UP
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN ->
                    com.eevdf.app.feature.settings.HardwareKeyPrefs.KEY_VOLUME_DOWN
                android.view.KeyEvent.KEYCODE_POWER       ->
                    com.eevdf.app.feature.settings.HardwareKeyPrefs.KEY_POWER
                else                                      -> null
            }
            if (keyId != null && handleExpireKey(keyId)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Runs the configured action for [keyId]; returns true if the key was consumed.
     * Shared by dispatchKeyEvent and onKeyDown.
     */
    private fun handleExpireKey(keyId: String): Boolean {
        return when (com.eevdf.app.feature.settings.HardwareKeyPrefs.actionForKey(this, keyId)) {
            com.eevdf.app.feature.settings.HardwareKeyPrefs.ACTION_STOP -> {
                sendBroadcast(
                    Intent(this, AlarmStopReceiver::class.java).apply {
                        action = AlarmStopReceiver.ACTION_TIMER_EXPIRED
                    }
                )
                finish()
                true
            }
            com.eevdf.app.feature.settings.HardwareKeyPrefs.ACTION_RESTART -> {
                // Do NOT fire the stop receiver here — that would broadcast
                // ACTION_STOP_ALARM and cause MainActivity's VM to null the
                // in-memory restore-task before restartAfterExpire() runs, so
                // the timer would stop but never start.  restartAfterExpire()
                // tears the alarm down itself.  Pass the task name so the VM can
                // resolve the task from the DB if its process was recreated.
                val taskName = intent.getStringExtra(EXTRA_TASK_NAME)
                val restart = Intent(
                    this,
                    com.eevdf.app.feature.task.MainActivity::class.java
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_RESTART_AFTER_EXPIRE, true)
                    putExtra(EXTRA_TASK_NAME, taskName)
                }
                startActivity(restart)
                finish()
                true
            }
            else -> false   // NONE — not consumed
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val keyId = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP   ->
                com.eevdf.app.feature.settings.HardwareKeyPrefs.KEY_VOLUME_UP
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN ->
                com.eevdf.app.feature.settings.HardwareKeyPrefs.KEY_VOLUME_DOWN
            android.view.KeyEvent.KEYCODE_POWER       ->
                com.eevdf.app.feature.settings.HardwareKeyPrefs.KEY_POWER
            else                                      -> null
        }
        if (keyId != null && handleExpireKey(keyId)) return true
        return super.onKeyDown(keyCode, event)
    }
}
