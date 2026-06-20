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
        if (keyId != null) {
            when (com.eevdf.app.feature.settings.HardwareKeyPrefs.actionForKey(this, keyId)) {
                com.eevdf.app.feature.settings.HardwareKeyPrefs.ACTION_STOP -> {
                    sendBroadcast(
                        Intent(this, AlarmStopReceiver::class.java).apply {
                            action = AlarmStopReceiver.ACTION_TIMER_EXPIRED
                        }
                    )
                    finish()
                    return true
                }
                com.eevdf.app.feature.settings.HardwareKeyPrefs.ACTION_RESTART -> {
                    // Tear the alarm down, then bring MainActivity forward with a
                    // restart request it will act on once it has the ViewModel.
                    sendBroadcast(
                        Intent(this, AlarmStopReceiver::class.java).apply {
                            action = AlarmStopReceiver.ACTION_TIMER_EXPIRED
                        }
                    )
                    val restart = Intent(
                        this,
                        com.eevdf.app.feature.task.MainActivity::class.java
                    ).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(EXTRA_RESTART_AFTER_EXPIRE, true)
                    }
                    startActivity(restart)
                    finish()
                    return true
                }
                else -> { /* NONE — let the system handle the key normally */ }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
