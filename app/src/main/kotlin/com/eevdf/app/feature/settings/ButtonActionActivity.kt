package com.eevdf.app.feature.settings

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import com.eevdf.app.R
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Button Action settings screen.
 *
 * Currently exposes a single option:
 *   • Quick Action — when enabled a second floating action button appears in
 *     MainActivity above the "Add Task" FAB.  Tapping it selects the active
 *     interrupt task (INT-A or INT-B, whichever is currently shown) and then
 *     immediately starts the timer.
 */
class ButtonActionActivity : AppCompatActivity() {

    private lateinit var switchQuickAction: SwitchMaterial
    private lateinit var tvQuickActionDesc: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_button_action)

        val toolbar = findViewById<Toolbar>(R.id.buttonActionToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Button Action"

        switchQuickAction = findViewById(R.id.switchQuickAction)
        tvQuickActionDesc = findViewById(R.id.tvQuickActionDesc)

        // ── Load saved state ──────────────────────────────────────────────────
        switchQuickAction.isChecked = QuickActionPrefs.isQuickActionEnabled(this)

        // ── Persist changes immediately ───────────────────────────────────────
        switchQuickAction.setOnCheckedChangeListener { _, isChecked ->
            QuickActionPrefs.setQuickActionEnabled(this, isChecked)
        }

        // ── Hardware Keys sub-page ────────────────────────────────────────────
        findViewById<CardView>(R.id.cardHardwareKeys).setOnClickListener {
            startActivity(Intent(this, HardwareKeyActionActivity::class.java))
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
