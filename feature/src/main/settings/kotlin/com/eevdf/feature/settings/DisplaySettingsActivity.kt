package com.eevdf.feature.settings

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import com.eevdf.feature.R
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.eevdf.feature.shared.prefs.DisplayPrefs

class DisplaySettingsActivity : AppCompatActivity() {

    private lateinit var tabLayout:        TabLayout
    private lateinit var tabContentUi:      LinearLayout
    private lateinit var tabContentRender:  LinearLayout

    private lateinit var darkModeToggleGroup: MaterialButtonToggleGroup


    private lateinit var switchAutoAdjust:   SwitchMaterial
    private lateinit var switchSimpleMode:   SwitchMaterial
    private lateinit var switchUnitFormat:   SwitchMaterial

    // ── Window Calibrate: live stats + profile cards ──────────────────────────
    private lateinit var tvWindowLiveStats:  TextView
    private lateinit var cardCalFloat:       CardView
    private lateinit var cardCalNormal:      CardView
    private lateinit var cardCalMini:        CardView
    private lateinit var tvCalFloatDims:     TextView
    private lateinit var tvCalNormalDims:    TextView
    private lateinit var tvCalMiniDims:      TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_display_settings)

        val toolbar = findViewById<Toolbar>(R.id.displaySettingsToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Display"

        // ── ui / render tabs ────────────────────────────────────────────────
        tabLayout       = findViewById(R.id.tabLayout)
        tabContentUi     = findViewById(R.id.tabContentUi)
        tabContentRender = findViewById(R.id.tabContentRender)

        tabLayout.addTab(tabLayout.newTab().setText("ui"))
        tabLayout.addTab(tabLayout.newTab().setText("render"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val showUi = tab.position == 0
                tabContentUi.visibility     = if (showUi) View.VISIBLE else View.GONE
                tabContentRender.visibility = if (showUi) View.GONE    else View.VISIBLE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        findViewById<LinearLayout>(R.id.rowLayoutDemo).setOnClickListener {
            startActivity(Intent(this, LayoutDemoActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.rowColorMatrix).setOnClickListener {
            startActivity(Intent(this, ColorMatrixActivity::class.java))
        }

        switchAutoAdjust  = findViewById(R.id.switchAutoAdjust)
        switchSimpleMode  = findViewById(R.id.switchSimpleMode)
        switchUnitFormat  = findViewById(R.id.switchUnitFormat)

        darkModeToggleGroup = findViewById(R.id.darkModeToggleGroup)

        // ── Dark mode toggle ──────────────────────────────────────────────
        darkModeToggleGroup.check(when (DisplayPrefs.getDarkMode(this)) {
            "light" -> R.id.btnDarkModeLight
            "dark"  -> R.id.btnDarkModeDark
            else    -> R.id.btnDarkModeSystem
        })
        darkModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnDarkModeLight  -> "light"
                R.id.btnDarkModeDark   -> "dark"
                else                   -> "system"
            }
            DisplayPrefs.setDarkMode(this, mode)
            DisplayPrefs.applyDarkMode(this)
        }

        tvWindowLiveStats = findViewById(R.id.tvWindowLiveStats)
        cardCalFloat      = findViewById(R.id.cardCalFloat)
        cardCalNormal     = findViewById(R.id.cardCalNormal)
        cardCalMini       = findViewById(R.id.cardCalMini)
        tvCalFloatDims    = findViewById(R.id.tvCalFloatDims)
        tvCalNormalDims   = findViewById(R.id.tvCalNormalDims)
        tvCalMiniDims     = findViewById(R.id.tvCalMiniDims)

        // ── Load saved prefs ──────────────────────────────────────────────────
        switchAutoAdjust.isChecked = DisplayPrefs.isAutoAdjustEnabled(this)
        switchSimpleMode.isChecked = DisplayPrefs.isSimpleModeEnabled(this)
        switchUnitFormat.isChecked = DisplayPrefs.isUnitFormatEnabled(this)

        // ── Switches ─────────────────────────────────────────────────────────
        switchAutoAdjust.setOnCheckedChangeListener { _, isChecked ->
            DisplayPrefs.setAutoAdjustEnabled(this, isChecked)
        }
        switchSimpleMode.setOnCheckedChangeListener { _, isChecked ->
            DisplayPrefs.setSimpleModeEnabled(this, isChecked)
        }
        switchUnitFormat.setOnCheckedChangeListener { _, isChecked ->
            DisplayPrefs.setUnitFormatEnabled(this, isChecked)
        }

        // ── Calibrate profile cards ───────────────────────────────────────────
        setupCalibrateCard(cardCalFloat,  DisplayPrefs.CalibrateProfile.FLOAT)
        setupCalibrateCard(cardCalNormal, DisplayPrefs.CalibrateProfile.NORMAL)
        setupCalibrateCard(cardCalMini,   DisplayPrefs.CalibrateProfile.MINI)
    }

    override fun onResume() {
        super.onResume()
        refreshWindowStats()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshWindowStats()
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads current window size from [resources.configuration] (same source as
     * MainActivity so the values are directly comparable), updates the live
     * stats banner, refreshes each profile card's saved-dimension label, and
     * highlights whichever profile matches the current window.
     *
     * Called from [onResume] and [onConfigurationChanged] so it stays live
     * while the user resizes the floating window.
     */
    private fun refreshWindowStats() {
        val wDp     = resources.configuration.screenWidthDp
        val hDp     = resources.configuration.screenHeightDp
        val multi   = isInMultiWindowMode
        val pip     = isInPictureInPictureMode
        val matched = DisplayPrefs.matchProfile(this, wDp, hDp)

        tvWindowLiveStats.text =
            "w = ${wDp}dp   h = ${hDp}dp   multiWin = $multi   pip = $pip" +
            if (matched != null) "   ▶ ${matched.name}" else ""

        for (p in DisplayPrefs.CalibrateProfile.values()) {
            val card   = cardFor(p)
            val dimsTV = dimsViewFor(p)
            val savedW = DisplayPrefs.getCalibrateW(this, p)
            val savedH = DisplayPrefs.getCalibrateH(this, p)
            val isSet  = savedW != DisplayPrefs.CALIBRATE_NOT_SET

            dimsTV.text = if (isSet) "${savedW} × ${savedH} dp" else "not set"

            // Background: active match → blue tint | saved no match → light gray | not set → white
            card.setCardBackgroundColor(when {
                matched == p -> Color.parseColor("#E3F2FD")
                isSet        -> Color.parseColor("#F5F5F5")
                else         -> Color.WHITE
            })
        }
    }

    /**
     * Attaches tap (record current size) and long-press (clear profile) to a
     * calibrate profile [CardView].
     */
    private fun setupCalibrateCard(
        card: CardView,
        profile: DisplayPrefs.CalibrateProfile
    ) {
        card.setOnClickListener {
            val wDp = resources.configuration.screenWidthDp
            val hDp = resources.configuration.screenHeightDp
            DisplayPrefs.setCalibrate(this, profile, wDp, hDp)
            Toast.makeText(
                this,
                "${profile.name}: recorded ${wDp} × ${hDp} dp",
                Toast.LENGTH_SHORT
            ).show()
            refreshWindowStats()
        }
        card.setOnLongClickListener {
            DisplayPrefs.clearCalibrate(this, profile)
            Toast.makeText(this, "${profile.name} profile cleared", Toast.LENGTH_SHORT).show()
            refreshWindowStats()
            true
        }
    }

    private fun cardFor(p: DisplayPrefs.CalibrateProfile) = when (p) {
        DisplayPrefs.CalibrateProfile.FLOAT  -> cardCalFloat
        DisplayPrefs.CalibrateProfile.NORMAL -> cardCalNormal
        DisplayPrefs.CalibrateProfile.MINI   -> cardCalMini
    }

    private fun dimsViewFor(p: DisplayPrefs.CalibrateProfile) = when (p) {
        DisplayPrefs.CalibrateProfile.FLOAT  -> tvCalFloatDims
        DisplayPrefs.CalibrateProfile.NORMAL -> tvCalNormalDims
        DisplayPrefs.CalibrateProfile.MINI   -> tvCalMiniDims
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

}
