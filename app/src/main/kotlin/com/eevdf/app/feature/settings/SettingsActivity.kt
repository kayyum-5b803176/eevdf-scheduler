package com.eevdf.app.feature.settings

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.eevdf.app.R
import com.google.android.material.tabs.TabLayout
import com.eevdf.app.core.nav.AppRoutes

class SettingsActivity : AppCompatActivity() {

    private lateinit var tabLayout:          TabLayout
    private lateinit var scrollView:         ScrollView

    private lateinit var tabContentPlatform:   LinearLayout
    private lateinit var tabContentApp:      LinearLayout
    private lateinit var tabContentCore:     LinearLayout
    private lateinit var tabContentData:     LinearLayout

    private lateinit var rowVisual:          LinearLayout
    private lateinit var rowSoundVibration:  LinearLayout
    private lateinit var rowButtonAction:    LinearLayout
    private lateinit var rowAutoSwitch:      LinearLayout
    private lateinit var rowDataBackup:      LinearLayout
    private lateinit var rowMultiUserSync:   LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.settingsToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        scrollView          = findViewById(R.id.settingsScrollView)
        tabLayout           = findViewById(R.id.settingsTabLayout)
        tabContentPlatform    = findViewById(R.id.tabContentPlatform)
        tabContentApp       = findViewById(R.id.tabContentApp)
        tabContentCore      = findViewById(R.id.tabContentCore)
        tabContentData      = findViewById(R.id.tabContentData)

        rowVisual           = findViewById(R.id.rowVisual)
        rowSoundVibration   = findViewById(R.id.rowSoundVibration)
        rowButtonAction     = findViewById(R.id.rowButtonAction)
        rowAutoSwitch       = findViewById(R.id.rowAutoSwitch)
        rowDataBackup       = findViewById(R.id.rowDataBackup)
        rowMultiUserSync    = findViewById(R.id.rowMultiUserSync)

        tabLayout.addTab(tabLayout.newTab().setText("platform"))
        tabLayout.addTab(tabLayout.newTab().setText("app"))
        tabLayout.addTab(tabLayout.newTab().setText("core"))
        tabLayout.addTab(tabLayout.newTab().setText("data"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showTab(tab.position)
                scrollView.smoothScrollTo(0, 0)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        showTab(0)

        rowVisual.setOnClickListener {
            startActivity(Intent(this, UiCustomizationActivity::class.java))
        }
        rowSoundVibration.setOnClickListener {
            startActivity(Intent(this, SoundVibrationActivity::class.java))
        }
        rowButtonAction.setOnClickListener {
            startActivity(Intent(this, ButtonActionActivity::class.java))
        }
        rowAutoSwitch.setOnClickListener {
            startActivity(AppRoutes.autoSwitch(this))
        }
        rowDataBackup.setOnClickListener {
            startActivity(AppRoutes.backup(this))
        }
        rowMultiUserSync.setOnClickListener {
            startActivity(AppRoutes.sync(this))
        }
    }

    private fun showTab(position: Int) {
        tabContentPlatform.visibility = if (position == 0) View.VISIBLE else View.GONE
        tabContentApp.visibility    = if (position == 1) View.VISIBLE else View.GONE
        tabContentCore.visibility   = if (position == 2) View.VISIBLE else View.GONE
        tabContentData.visibility   = if (position == 3) View.VISIBLE else View.GONE
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
