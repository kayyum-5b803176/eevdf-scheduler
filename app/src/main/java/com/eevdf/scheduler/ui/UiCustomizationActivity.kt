package com.eevdf.scheduler.ui

import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.eevdf.scheduler.R
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

class UiCustomizationActivity : AppCompatActivity() {

    private lateinit var sliderCardHeight:   Slider
    private lateinit var tvCardHeightValue:  TextView
    private lateinit var switchAutoAdjust:   SwitchMaterial
    private lateinit var switchSimpleMode:   SwitchMaterial

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

        // ── Load saved prefs ──────────────────────────────────────────────────
        val savedScale = UiCustomizationPrefs.getCardHeightScale(this)
        sliderCardHeight.value = savedScale.toFloat()
        updateScaleLabel(savedScale)

        switchAutoAdjust.isChecked = UiCustomizationPrefs.isAutoAdjustEnabled(this)
        switchSimpleMode.isChecked = UiCustomizationPrefs.isSimpleModeEnabled(this)

        // ── Card height slider ────────────────────────────────────────────────
        sliderCardHeight.addOnChangeListener { _, value, _ ->
            val scale = value.toInt()
            updateScaleLabel(scale)
            UiCustomizationPrefs.setCardHeightScale(this, scale)
        }

        // ── Auto Adjust toggle ────────────────────────────────────────────────
        switchAutoAdjust.setOnCheckedChangeListener { _, isChecked ->
            UiCustomizationPrefs.setAutoAdjustEnabled(this, isChecked)
        }

        // ── Simple Mode toggle ────────────────────────────────────────────────
        switchSimpleMode.setOnCheckedChangeListener { _, isChecked ->
            UiCustomizationPrefs.setSimpleModeEnabled(this, isChecked)
        }
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
