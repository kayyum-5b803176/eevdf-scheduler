package com.eevdf.scheduler.ui

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
import com.eevdf.scheduler.R
import com.google.android.material.button.MaterialButton

/**
 * Full-screen alarm activity — shown over the lock screen when a task timer expires.
 * Behaves like Google Clock's alarm screen: wakes the display, shows over other apps,
 * and keeps ringing until the user taps Stop.
 */
class AlarmActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TASK_NAME = "task_name"

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
        ContextCompat.registerReceiver(
            this,
            stopReceiver,
            IntentFilter(AlarmStopReceiver.ACTION_STOP_ALARM),
            ContextCompat.RECEIVER_NOT_EXPORTED
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
}
