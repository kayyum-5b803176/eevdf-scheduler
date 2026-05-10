package com.eevdf.scheduler.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.eevdf.scheduler.R
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnOpenDataBackup:    MaterialButton
    private lateinit var btnOpenSoundVib:      MaterialButton
    private lateinit var btnOpenAutoSwitch:    MaterialButton
    private lateinit var btnOpenMultiUserSync: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.settingsToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        btnOpenDataBackup = findViewById(R.id.btnOpenDataBackup)
        btnOpenSoundVib   = findViewById(R.id.btnOpenSoundVibration)
        btnOpenAutoSwitch    = findViewById(R.id.btnOpenAutoSwitch)
        btnOpenMultiUserSync = findViewById(R.id.btnOpenMultiUserSync)

        btnOpenDataBackup.setOnClickListener {
            startActivity(Intent(this, DataBackupActivity::class.java))
        }
        btnOpenSoundVib.setOnClickListener {
            startActivity(Intent(this, SoundVibrationActivity::class.java))
        }
        btnOpenAutoSwitch.setOnClickListener {
            startActivity(Intent(this, AutoSwitchActivity::class.java))
        }
        btnOpenMultiUserSync.setOnClickListener {
            startActivity(Intent(this, MultiUserSyncActivity::class.java))
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
