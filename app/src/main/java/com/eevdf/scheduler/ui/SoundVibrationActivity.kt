package com.eevdf.scheduler.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.eevdf.scheduler.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class SoundVibrationActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("eevdf_prefs", Context.MODE_PRIVATE) }

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

        // Haptic switch
        val switchHaptic = findViewById<SwitchMaterial>(R.id.switchSvHaptic)
        switchHaptic.isChecked = prefs.getBoolean(VibrationManager.KEY_HAPTIC, VibrationManager.DEFAULT_HAPTIC)
        switchHaptic.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(VibrationManager.KEY_HAPTIC, checked).apply()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
