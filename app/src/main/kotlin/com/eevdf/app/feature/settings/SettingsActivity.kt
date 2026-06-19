package com.eevdf.app.feature.settings

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.eevdf.app.R
import com.google.android.material.button.MaterialButton
import com.eevdf.app.feature.backup.DataBackupActivity
import com.eevdf.app.feature.autoswitch.AutoSwitchActivity
import com.eevdf.app.feature.sync.MultiUserSyncActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnOpenDataBackup:       MaterialButton
    private lateinit var btnOpenSoundVib:         MaterialButton
    private lateinit var btnOpenAutoSwitch:       MaterialButton
    private lateinit var btnOpenMultiUserSync:    MaterialButton
    private lateinit var btnOpenUiCustomization:  MaterialButton
    private lateinit var btnOpenButtonAction:     MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.settingsToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        btnOpenDataBackup      = findViewById(R.id.btnOpenDataBackup)
        btnOpenSoundVib        = findViewById(R.id.btnOpenSoundVibration)
        btnOpenAutoSwitch      = findViewById(R.id.btnOpenAutoSwitch)
        btnOpenMultiUserSync   = findViewById(R.id.btnOpenMultiUserSync)
        btnOpenUiCustomization = findViewById(R.id.btnOpenUiCustomization)
        btnOpenButtonAction    = findViewById(R.id.btnOpenButtonAction)

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
        btnOpenUiCustomization.setOnClickListener {
            startActivity(Intent(this, UiCustomizationActivity::class.java))
        }
        btnOpenButtonAction.setOnClickListener {
            startActivity(Intent(this, ButtonActionActivity::class.java))
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
