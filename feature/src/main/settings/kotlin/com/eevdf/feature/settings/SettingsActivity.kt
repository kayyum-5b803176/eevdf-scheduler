package com.eevdf.feature.settings

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.eevdf.contract.nav.AppRoutes
import com.eevdf.feature.R
import com.eevdf.feature.ui.NavCardView
import com.google.android.material.tabs.TabLayout

/**
 * Migrated onto NavCardView (see ARCHITECTURE.md's "universal adoption"
 * phase) — every card here is a real NavCardView instance, wired the same
 * way LayoutDemoActivity's own demo cards are, not hand-authored XML that
 * happens to look the same. This is the practical difference universal
 * adoption is meant to close: adjusting the Layout demo page's scale
 * sliders now visibly changes this real, currently-shipping screen too.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var tabLayout:          TabLayout
    private lateinit var scrollView:         ScrollView

    private lateinit var tabContentPlatform: LinearLayout
    private lateinit var tabContentApp:      LinearLayout
    private lateinit var tabContentCore:     LinearLayout
    private lateinit var tabContentData:     LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.settingsToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        scrollView           = findViewById(R.id.settingsScrollView)
        tabLayout             = findViewById(R.id.settingsTabLayout)
        tabContentPlatform    = findViewById(R.id.tabContentPlatform)
        tabContentApp         = findViewById(R.id.tabContentApp)
        tabContentCore        = findViewById(R.id.tabContentCore)
        tabContentData        = findViewById(R.id.tabContentData)

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
        bindCards()
    }

    /**
     * Every NavCardView on this screen — title/subtitle/navigable/onNavigate
     * set here, not as XML attributes (NavCardView has none defined; these
     * are plain Kotlin properties, the same shape LayoutDemoActivity's
     * NavCardView.create() calls set at construction time instead).
     */
    private fun bindCards() {
        findViewById<NavCardView>(R.id.rowVisual).apply {
            title = "display"
            subtitle = "appearance, layout, density, overlay"
            onNavigate = { startActivity(Intent(this@SettingsActivity, DisplaySettingsActivity::class.java)) }
        }
        findViewById<NavCardView>(R.id.rowSoundVibration).apply {
            title = "sound and vibration"
            subtitle = "profiles, patterns, haptic feedback"
            onNavigate = { startActivity(Intent(this@SettingsActivity, SoundVibrationActivity::class.java)) }
        }
        findViewById<NavCardView>(R.id.rowButtonAction).apply {
            title = "control"
            subtitle = "hardware keys, quick action"
            onNavigate = { startActivity(Intent(this@SettingsActivity, ButtonActionActivity::class.java)) }
        }
        findViewById<NavCardView>(R.id.rowEventHandleService).apply {
            title = "event handle service"
            subtitle = "no settings available yet"
            navigable = false
        }
        findViewById<NavCardView>(R.id.rowAutoSwitch).apply {
            title = "auto switch"
            subtitle = "pause and resume on incoming calls"
            onNavigate = { startActivity(AppRoutes.autoSwitch(this@SettingsActivity)) }
        }
        findViewById<NavCardView>(R.id.rowSystemConfig).apply {
            title = "system config"
            subtitle = "no settings available yet"
            navigable = false
        }
        findViewById<NavCardView>(R.id.rowCoreAlgorithm).apply {
            title = "core algorithm"
            subtitle = "no settings available yet"
            navigable = false
        }
        findViewById<NavCardView>(R.id.rowRules).apply {
            title = "rules"
            subtitle = "no settings available yet"
            navigable = false
        }
        findViewById<NavCardView>(R.id.rowDataBackup).apply {
            title = "data and backup"
            subtitle = "export and import app data"
            onNavigate = { startActivity(AppRoutes.backup(this@SettingsActivity)) }
        }
        findViewById<NavCardView>(R.id.rowMultiUserSync).apply {
            title = "multiuser sync"
            subtitle = "share task state across devices"
            onNavigate = { startActivity(AppRoutes.sync(this@SettingsActivity)) }
        }
        findViewById<NavCardView>(R.id.rowLogs).apply {
            title = "logs"
            subtitle = "no settings available yet"
            navigable = false
        }
        findViewById<NavCardView>(R.id.rowAbout).apply {
            title = "about"
            subtitle = "no settings available yet"
            navigable = false
        }
    }

    private fun showTab(position: Int) {
        tabContentPlatform.visibility = if (position == 0) View.VISIBLE else View.GONE
        tabContentApp.visibility      = if (position == 1) View.VISIBLE else View.GONE
        tabContentCore.visibility     = if (position == 2) View.VISIBLE else View.GONE
        tabContentData.visibility     = if (position == 3) View.VISIBLE else View.GONE
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
