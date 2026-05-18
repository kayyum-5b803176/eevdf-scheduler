package com.eevdf.scheduler.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import com.eevdf.scheduler.R
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

class UiCustomizationActivity : AppCompatActivity() {

    private lateinit var sliderCardHeight:   Slider
    private lateinit var tvCardHeightValue:  TextView
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
        setContentView(R.layout.activity_ui_customization)

        val toolbar = findViewById<Toolbar>(R.id.uiCustomizationToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "UI Customization"

        sliderCardHeight  = findViewById(R.id.sliderCardHeight)
        tvCardHeightValue = findViewById(R.id.tvCardHeightValue)
        switchAutoAdjust  = findViewById(R.id.switchAutoAdjust)
        switchSimpleMode  = findViewById(R.id.switchSimpleMode)
        switchUnitFormat  = findViewById(R.id.switchUnitFormat)

        tvWindowLiveStats = findViewById(R.id.tvWindowLiveStats)
        cardCalFloat      = findViewById(R.id.cardCalFloat)
        cardCalNormal     = findViewById(R.id.cardCalNormal)
        cardCalMini       = findViewById(R.id.cardCalMini)
        tvCalFloatDims    = findViewById(R.id.tvCalFloatDims)
        tvCalNormalDims   = findViewById(R.id.tvCalNormalDims)
        tvCalMiniDims     = findViewById(R.id.tvCalMiniDims)

        // ── Load saved prefs ──────────────────────────────────────────────────
        val savedScale = UiCustomizationPrefs.getCardHeightScale(this)
        sliderCardHeight.value  = savedScale.toFloat()
        updateScaleLabel(savedScale)
        switchAutoAdjust.isChecked = UiCustomizationPrefs.isAutoAdjustEnabled(this)
        switchSimpleMode.isChecked = UiCustomizationPrefs.isSimpleModeEnabled(this)
        switchUnitFormat.isChecked = UiCustomizationPrefs.isUnitFormatEnabled(this)

        // ── Card height slider ────────────────────────────────────────────────
        sliderCardHeight.addOnChangeListener { _, value, _ ->
            val scale = value.toInt()
            updateScaleLabel(scale)
            UiCustomizationPrefs.setCardHeightScale(this, scale)
        }

        // ── Switches ─────────────────────────────────────────────────────────
        switchAutoAdjust.setOnCheckedChangeListener { _, isChecked ->
            UiCustomizationPrefs.setAutoAdjustEnabled(this, isChecked)
        }
        switchSimpleMode.setOnCheckedChangeListener { _, isChecked ->
            UiCustomizationPrefs.setSimpleModeEnabled(this, isChecked)
        }
        switchUnitFormat.setOnCheckedChangeListener { _, isChecked ->
            UiCustomizationPrefs.setUnitFormatEnabled(this, isChecked)
        }

        // ── Calibrate profile cards ───────────────────────────────────────────
        setupCalibrateCard(cardCalFloat,  UiCustomizationPrefs.CalibrateProfile.FLOAT)
        setupCalibrateCard(cardCalNormal, UiCustomizationPrefs.CalibrateProfile.NORMAL)
        setupCalibrateCard(cardCalMini,   UiCustomizationPrefs.CalibrateProfile.MINI)
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
        val matched = UiCustomizationPrefs.matchProfile(this, wDp, hDp)

        tvWindowLiveStats.text =
            "w = ${wDp}dp   h = ${hDp}dp   multiWin = $multi   pip = $pip" +
            if (matched != null) "   ▶ ${matched.name}" else ""

        for (p in UiCustomizationPrefs.CalibrateProfile.values()) {
            val card   = cardFor(p)
            val dimsTV = dimsViewFor(p)
            val savedW = UiCustomizationPrefs.getCalibrateW(this, p)
            val savedH = UiCustomizationPrefs.getCalibrateH(this, p)
            val isSet  = savedW != UiCustomizationPrefs.CALIBRATE_NOT_SET

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
        profile: UiCustomizationPrefs.CalibrateProfile
    ) {
        card.setOnClickListener {
            val wDp = resources.configuration.screenWidthDp
            val hDp = resources.configuration.screenHeightDp
            UiCustomizationPrefs.setCalibrate(this, profile, wDp, hDp)
            Toast.makeText(
                this,
                "${profile.name}: recorded ${wDp} × ${hDp} dp",
                Toast.LENGTH_SHORT
            ).show()
            refreshWindowStats()
        }
        card.setOnLongClickListener {
            UiCustomizationPrefs.clearCalibrate(this, profile)
            Toast.makeText(this, "${profile.name} profile cleared", Toast.LENGTH_SHORT).show()
            refreshWindowStats()
            true
        }
    }

    private fun cardFor(p: UiCustomizationPrefs.CalibrateProfile) = when (p) {
        UiCustomizationPrefs.CalibrateProfile.FLOAT  -> cardCalFloat
        UiCustomizationPrefs.CalibrateProfile.NORMAL -> cardCalNormal
        UiCustomizationPrefs.CalibrateProfile.MINI   -> cardCalMini
    }

    private fun dimsViewFor(p: UiCustomizationPrefs.CalibrateProfile) = when (p) {
        UiCustomizationPrefs.CalibrateProfile.FLOAT  -> tvCalFloatDims
        UiCustomizationPrefs.CalibrateProfile.NORMAL -> tvCalNormalDims
        UiCustomizationPrefs.CalibrateProfile.MINI   -> tvCalMiniDims
    }

    private fun updateScaleLabel(scale: Int) {
        tvCardHeightValue.text =
            if (scale == UiCustomizationPrefs.DEFAULT_CARD_HEIGHT_SCALE) "$scale (Default)"
            else "$scale"
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
