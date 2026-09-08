package com.eevdf.capabilities.settingsscreens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.eevdf.capabilities.settingsscreens.R
import com.eevdf.capabilities.designsystem.entities.ToggleCardEntity
import com.eevdf.capabilities.designsystem.renderers.renderToggleCard
import com.google.android.material.button.MaterialButton
import com.eevdf.capabilities.settingsstorage.state.VibrationPrefs

class SoundVibrationActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("eevdf_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sound_vibration)

        val toolbar = findViewById<Toolbar>(R.id.svToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Sound & Vibration"

        // Profiles button
        findViewById<MaterialButton>(R.id.btnSvOpenProfiles).setOnClickListener {
            startActivity(Intent(this, ProfileSettingsActivity::class.java))
        }

        // Haptic toggle — RESOLVED: was a hand-built SwitchMaterial card,
        // now a real ToggleCard instance via the shared renderer.
        findViewById<android.widget.FrameLayout>(R.id.hapticCardContainer).addView(
            renderToggleCard(
                this,
                ToggleCardEntity(
                    title = "Haptic Feedback on Buttons",
                    description = "Short vibration on every UI button tap",
                    checked = prefs.getBoolean(VibrationPrefs.KEY_HAPTIC, VibrationPrefs.DEFAULT_HAPTIC),
                    onCheckedChange = { checked ->
                        prefs.edit().putBoolean(VibrationPrefs.KEY_HAPTIC, checked).apply()
                    },
                ),
            )
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
