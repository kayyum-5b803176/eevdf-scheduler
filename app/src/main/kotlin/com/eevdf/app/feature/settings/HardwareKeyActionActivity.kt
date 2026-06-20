package com.eevdf.app.feature.settings

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.eevdf.app.R

/**
 * Lists the three assignable hardware keys (Volume Up / Volume Down / Power).
 * Each row shows the key name and its currently bound action, and opens
 * [HardwareKeyOptionActivity] for that key.
 *
 * Reached from the "Hardware Keys" card on [ButtonActionActivity].
 *
 * The bound-action summary is refreshed in [onResume] so it updates after the
 * user returns from the option page.
 */
class HardwareKeyActionActivity : AppCompatActivity() {

    private lateinit var rowVolumeUp:   LinearLayout
    private lateinit var rowVolumeDown: LinearLayout
    private lateinit var rowPower:      LinearLayout

    private lateinit var tvVolumeUpValue:   TextView
    private lateinit var tvVolumeDownValue: TextView
    private lateinit var tvPowerValue:      TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hardware_key_action)

        val toolbar = findViewById<Toolbar>(R.id.hwKeyActionToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Hardware Keys"

        rowVolumeUp   = findViewById(R.id.rowVolumeUp)
        rowVolumeDown = findViewById(R.id.rowVolumeDown)
        rowPower      = findViewById(R.id.rowPower)

        tvVolumeUpValue   = findViewById(R.id.tvVolumeUpValue)
        tvVolumeDownValue = findViewById(R.id.tvVolumeDownValue)
        tvPowerValue      = findViewById(R.id.tvPowerValue)

        rowVolumeUp.setOnClickListener   { openOption(HardwareKeyPrefs.KEY_VOLUME_UP) }
        rowVolumeDown.setOnClickListener { openOption(HardwareKeyPrefs.KEY_VOLUME_DOWN) }
        rowPower.setOnClickListener      { openOption(HardwareKeyPrefs.KEY_POWER) }
    }

    override fun onResume() {
        super.onResume()
        refreshSummaries()
    }

    private fun refreshSummaries() {
        tvVolumeUpValue.text =
            HardwareKeyPrefs.actionLabel(HardwareKeyPrefs.getAction(this, HardwareKeyPrefs.KEY_VOLUME_UP))
        tvVolumeDownValue.text =
            HardwareKeyPrefs.actionLabel(HardwareKeyPrefs.getAction(this, HardwareKeyPrefs.KEY_VOLUME_DOWN))
        tvPowerValue.text =
            HardwareKeyPrefs.actionLabel(HardwareKeyPrefs.getAction(this, HardwareKeyPrefs.KEY_POWER))
    }

    private fun openOption(keyId: String) {
        startActivity(
            Intent(this, HardwareKeyOptionActivity::class.java)
                .putExtra(HardwareKeyOptionActivity.EXTRA_KEY, keyId)
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
