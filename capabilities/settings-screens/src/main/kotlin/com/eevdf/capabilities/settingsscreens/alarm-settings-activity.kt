package com.eevdf.capabilities.settingsscreens

import android.os.Bundle
import android.view.MenuItem
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.eevdf.capabilities.designsystem.entities.ToggleCardEntity
import com.eevdf.capabilities.designsystem.renderers.renderToggleCard
import com.eevdf.capabilities.settingsstorage.state.WakePrefs

/**
 * Settings → platform → alarm: the one setting of the scheduled wake (`task-wake`).
 *
 *   • Re-arm wakes after reboot — ON (default): after the phone restarts, every wake that is
 *     still in the future is put back into AlarmManager. OFF: nothing is re-armed at boot;
 *     wakes are re-armed the next time the app is opened. A wake whose time passed while the
 *     phone was off is reported as "missed" either way — this switch never silences that.
 *
 * Only the preference lives here ([WakePrefs]); `task-wake` reads it. Nothing in this screen
 * knows how a wake works.
 */
class AlarmSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm_settings)

        val toolbar = findViewById<Toolbar>(R.id.alarmToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Alarm"

        findViewById<FrameLayout>(R.id.rearmOnBootContainer).addView(
            renderToggleCard(
                this,
                ToggleCardEntity(
                    title = "Re-arm wakes after reboot",
                    description = "When on, a task's scheduled wake that is still in the future is set again after the phone restarts, so it still fires on time. When off, wakes are set again the next time you open the app. A wake that came due while the phone was off is always reported as missed.",
                    checked = WakePrefs.isRearmOnBootEnabled(this),
                    onCheckedChange = { isChecked -> WakePrefs.setRearmOnBootEnabled(this, isChecked) },
                ),
            )
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
