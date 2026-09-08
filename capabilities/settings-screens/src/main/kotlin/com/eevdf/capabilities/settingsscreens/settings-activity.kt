package com.eevdf.capabilities.settingsscreens

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.eevdf.capabilities.navigationroutes.AppRoutes
import com.eevdf.capabilities.settingsscreens.R
import com.eevdf.capabilities.designsystem.entities.NavCardEntity
import com.eevdf.capabilities.designsystem.renderers.renderNavCard
import com.google.android.material.tabs.TabLayout

/**
 * RESOLVED (typed-entity + centralized-renderer redesign): this used to
 * hold real `NavCardView` instances declared as XML tags in its own
 * layout, populated via `findViewById<NavCardView>(...).apply { ... }`.
 * That XML tag was itself a construction reference to a design-system
 * output class — not allowed under the redesign's rule 3, even though the
 * View instance itself was already correct. Every card here is now built
 * as a [NavCardEntity] and handed to [renderNavCard], the one place in the
 * app allowed to construct a `NavCardView` — this screen no longer imports
 * `design-system.output.NavCardView` at all. See `activity_settings.xml`'s
 * own comment for the matching XML-side change (four now-empty containers,
 * populated here instead of declared there).
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
     * Every NavCard on this screen — one [NavCardEntity] per row, added to
     * its tab's container via [renderNavCard]. Order within each container
     * matches the original XML tag order exactly.
     */
    private fun bindCards() {
        tabContentPlatform.addView(renderNavCard(this, NavCardEntity(
            title = "display",
            subtitle = "appearance, layout, density",
            onNavigate = { startActivity(Intent(this@SettingsActivity, DisplaySettingsActivity::class.java)) },
        )))
        tabContentPlatform.addView(renderNavCard(this, NavCardEntity(
            title = "sound and vibration",
            subtitle = "profiles, patterns, haptic feedback",
            onNavigate = { startActivity(Intent(this@SettingsActivity, SoundVibrationActivity::class.java)) },
        )))
        tabContentPlatform.addView(renderNavCard(this, NavCardEntity(
            title = "notification",
            subtitle = "lock screen overlay, exclude app",
            onNavigate = { startActivity(Intent(this@SettingsActivity, NotificationSettingsActivity::class.java)) },
        )))
        tabContentPlatform.addView(renderNavCard(this, NavCardEntity(
            title = "permission",
            subtitle = "notifications, full-screen, battery, alarms",
            onNavigate = { startActivity(Intent(this@SettingsActivity, PermissionsActivity::class.java)) },
        )))

        tabContentApp.addView(renderNavCard(this, NavCardEntity(
            title = "control",
            subtitle = "hardware keys, quick action",
            onNavigate = { startActivity(Intent(this@SettingsActivity, ButtonActionActivity::class.java)) },
        )))
        tabContentApp.addView(renderNavCard(this, NavCardEntity(
            title = "event handle service",
            subtitle = "no settings available yet",
            navigable = false,
        )))
        tabContentApp.addView(renderNavCard(this, NavCardEntity(
            title = "auto switch",
            subtitle = "pause and resume on incoming calls",
            onNavigate = { startActivity(AppRoutes.autoSwitch(this@SettingsActivity)) },
        )))

        tabContentCore.addView(renderNavCard(this, NavCardEntity(
            title = "system config",
            subtitle = "no settings available yet",
            navigable = false,
        )))
        tabContentCore.addView(renderNavCard(this, NavCardEntity(
            title = "core algorithm",
            subtitle = "no settings available yet",
            navigable = false,
        )))
        tabContentCore.addView(renderNavCard(this, NavCardEntity(
            title = "rules",
            subtitle = "no settings available yet",
            navigable = false,
        )))

        tabContentData.addView(renderNavCard(this, NavCardEntity(
            title = "data and backup",
            subtitle = "export and import app data",
            onNavigate = { startActivity(AppRoutes.backup(this@SettingsActivity)) },
        )))
        tabContentData.addView(renderNavCard(this, NavCardEntity(
            title = "multiuser sync",
            subtitle = "share task state across devices",
            onNavigate = { startActivity(AppRoutes.sync(this@SettingsActivity)) },
        )))
        tabContentData.addView(renderNavCard(this, NavCardEntity(
            title = "logs",
            subtitle = "no settings available yet",
            navigable = false,
        )))
        tabContentData.addView(renderNavCard(this, NavCardEntity(
            title = "about",
            subtitle = "no settings available yet",
            navigable = false,
        )))
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
