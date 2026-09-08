package com.eevdf.capabilities.settingsscreens

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.eevdf.capabilities.settingsscreens.R
import com.eevdf.capabilities.designsystem.entities.NavCardEntity
import com.eevdf.capabilities.designsystem.entities.ToggleCardEntity
import com.eevdf.capabilities.designsystem.renderers.renderNavCard
import com.eevdf.capabilities.designsystem.renderers.renderToggleCard
import com.eevdf.capabilities.settingsstorage.state.QuickActionPrefs

/**
 * Button Action settings screen.
 *
 * Currently exposes a single option:
 *   • Quick Action — when enabled a second floating action button appears in
 *     MainActivity above the "Add Task" FAB.  Tapping it selects the active
 *     interrupt task (INT-A or INT-B, whichever is currently shown) and then
 *     immediately starts the timer.
 *
 * RESOLVED (typed-entity + centralized-renderer redesign): both rows used
 * to be hand-authored View trees duplicating ToggleCardView's/NavCardView's
 * internal layouts by hand. Now real instances via the shared renderers.
 */
class ButtonActionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_button_action)

        val toolbar = findViewById<Toolbar>(R.id.buttonActionToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Button Action"

        findViewById<FrameLayout>(R.id.quickActionContainer).addView(
            renderToggleCard(
                this,
                ToggleCardEntity(
                    title = "Quick Action",
                    description = "Adds a floating button above the Add Task button. Tap it to instantly select the current interrupt task (INT-A or INT-B) and start the timer.",
                    checked = QuickActionPrefs.isQuickActionEnabled(this),
                    onCheckedChange = { isChecked -> QuickActionPrefs.setQuickActionEnabled(this, isChecked) },
                ),
            )
        )

        findViewById<FrameLayout>(R.id.hardwareKeysContainer).addView(
            renderNavCard(
                this,
                NavCardEntity(
                    title = "Hardware Keys",
                    subtitle = "Use Volume Up, Volume Down, or the Power button to Stop or Restart a task when its timer expires.",
                    onNavigate = { startActivity(Intent(this, HardwareKeyActionActivity::class.java)) },
                ),
            )
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
