package com.eevdf.scheduler.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Bundle
import android.view.MenuItem
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.eevdf.scheduler.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout

/**
 * Profile Settings — lets the user configure independent Sound + Vibration settings
 * for each task type: Default, Notification, Alarm, Custom.
 *
 * Each profile stores its settings under a prefixed SharedPrefs key (see SoundManager /
 * VibrationManager).  The Default profile uses no prefix, so it is fully backward-compatible
 * with the global settings that SettingsActivity manages.
 */
class ProfileSettingsActivity : AppCompatActivity() {

    // ── Profile definitions ────────────────────────────────────────────────────

    private data class Profile(val label: String, val taskType: String)

    private val profiles = listOf(
        Profile("Default",      "DEFAULT"),
        Profile("Notification", "NOTIFICATION"),
        Profile("Alarm",        "ALARM"),
        Profile("Custom",       "CUSTOM")
    )

    /** Currently selected profile index (matches tab position). */
    private var currentProfileIdx = 0

    // ── UI references ──────────────────────────────────────────────────────────

    private lateinit var tabLayout:            TabLayout
    private lateinit var tvSoundName:          TextView
    private lateinit var btnPickSound:         MaterialButton
    private lateinit var sliderSoundTimeout:   Slider
    private lateinit var tvSoundTimeoutLabel:  TextView
    private lateinit var sliderVolume:         Slider
    private lateinit var tvVolumeLabel:        TextView
    private lateinit var sliderFadeIn:         Slider
    private lateinit var tvFadeInLabel:        TextView
    private lateinit var rgVibPattern:         RadioGroup
    private lateinit var btnPreviewVib:        MaterialButton
    private lateinit var sliderVibTimeout:     Slider
    private lateinit var tvVibTimeoutLabel:    TextView
    private lateinit var switchHaptic:         SwitchMaterial
    // Visibility containers
    private lateinit var layoutAction1Section: android.widget.LinearLayout
    private lateinit var layoutVibrationSection: android.widget.LinearLayout
    // Action 1 widgets
    private lateinit var tvAction1SoundName:   TextView
    private lateinit var btnAction1PickSound:  MaterialButton
    private lateinit var sliderAction1Volume:  Slider
    private lateinit var tvAction1VolumeLabel: TextView

    private val prefs by lazy { getSharedPreferences("eevdf_prefs", Context.MODE_PRIVATE) }

    // ── Sound picker launchers ─────────────────────────────────────────────────

    private val soundPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data
                ?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            val key = soundUriKeyFor(currentProfileIdx)
            prefs.edit().putString(key, uri?.toString()).apply()
            updateSoundName()
        }
    }

    private val action1SoundPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data
                ?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            prefs.edit().putString(SoundManager.KEY_ACTION1_SOUND_URI, uri?.toString()).apply()
            updateAction1SoundName()
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_settings)

        val toolbar = findViewById<Toolbar>(R.id.profileSettingsToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Task Type Profiles"

        tabLayout          = findViewById(R.id.profileTabLayout)
        tvSoundName        = findViewById(R.id.tvProfileSoundName)
        btnPickSound       = findViewById(R.id.btnProfilePickSound)
        sliderSoundTimeout = findViewById(R.id.sliderProfileSoundTimeout)
        tvSoundTimeoutLabel= findViewById(R.id.tvProfileSoundTimeoutLabel)
        sliderVolume       = findViewById(R.id.sliderProfileVolume)
        tvVolumeLabel      = findViewById(R.id.tvProfileVolumeLabel)
        sliderFadeIn       = findViewById(R.id.sliderProfileFadeIn)
        tvFadeInLabel      = findViewById(R.id.tvProfileFadeInLabel)
        rgVibPattern       = findViewById(R.id.rgProfileVibPattern)
        btnPreviewVib      = findViewById(R.id.btnProfilePreviewVib)
        sliderVibTimeout   = findViewById(R.id.sliderProfileVibTimeout)
        tvVibTimeoutLabel  = findViewById(R.id.tvProfileVibTimeoutLabel)
        switchHaptic       = findViewById(R.id.switchProfileHaptic)
        layoutAction1Section   = findViewById(R.id.layoutAction1Section)
        layoutVibrationSection = findViewById(R.id.layoutVibrationSection)
        tvAction1SoundName     = findViewById(R.id.tvAction1SoundName)
        btnAction1PickSound    = findViewById(R.id.btnAction1PickSound)
        sliderAction1Volume    = findViewById(R.id.sliderAction1Volume)
        tvAction1VolumeLabel   = findViewById(R.id.tvAction1VolumeLabel)

        // Build tabs
        profiles.forEach { tabLayout.addTab(tabLayout.newTab().setText(it.label)) }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentProfileIdx = tab.position
                val isNotification = profiles[tab.position].taskType == "NOTIFICATION"
                layoutAction1Section.visibility   = if (isNotification) android.view.View.VISIBLE else android.view.View.GONE
                layoutVibrationSection.visibility = if (isNotification) android.view.View.GONE   else android.view.View.VISIBLE
                loadProfile(currentProfileIdx)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        setupVibPatternRadios()
        setupListeners()
        loadProfile(0)
    }

    // ── Setup ──────────────────────────────────────────────────────────────────

    private fun setupVibPatternRadios() {
        VibrationManager.PATTERNS.forEach { pat ->
            val rb = RadioButton(this).apply {
                id   = pat.id
                text = pat.name
                textSize = 14f
                setPadding(8, 8, 8, 8)
            }
            rgVibPattern.addView(rb)
        }
    }

    private fun setupListeners() {
        btnAction1PickSound.setOnClickListener {
            val current = prefs.getString(SoundManager.KEY_ACTION1_SOUND_URI, null)
                ?.let { android.net.Uri.parse(it) }
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Cue Sound")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                if (current != null) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
            }
            action1SoundPickerLauncher.launch(intent)
        }

        sliderAction1Volume.addOnChangeListener { _, value, _ ->
            val pct = value.toInt()
            prefs.edit().putInt(SoundManager.KEY_ACTION1_VOLUME, pct).apply()
            tvAction1VolumeLabel.text = "$pct%"
        }

        btnPickSound.setOnClickListener {
            val current = prefs.getString(soundUriKeyFor(currentProfileIdx), null)
                ?.let { android.net.Uri.parse(it) }
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Profile Sound")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                if (current != null) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
            }
            soundPickerLauncher.launch(intent)
        }

        sliderSoundTimeout.addOnChangeListener { _, value, _ ->
            val sec = value.toInt()
            prefs.edit().putInt(soundTimeoutKeyFor(currentProfileIdx), sec).apply()
            tvSoundTimeoutLabel.text = formatTimeout(sec)
        }

        sliderVolume.addOnChangeListener { _, value, _ ->
            val pct = value.toInt()
            prefs.edit().putInt(soundVolumeKeyFor(currentProfileIdx), pct).apply()
            tvVolumeLabel.text = "$pct%"
        }

        sliderFadeIn.addOnChangeListener { _, value, _ ->
            val sec = value.toInt()
            prefs.edit().putInt(soundFadeInKeyFor(currentProfileIdx), sec).apply()
            tvFadeInLabel.text = if (sec == 0) "Off" else formatTimeout(sec)
        }

        rgVibPattern.setOnCheckedChangeListener { _, id ->
            prefs.edit().putInt(vibPatternKeyFor(currentProfileIdx), id).apply()
        }

        btnPreviewVib.setOnClickListener {
            val id = prefs.getInt(vibPatternKeyFor(currentProfileIdx), VibrationManager.DEFAULT_PATTERN)
            VibrationManager.preview(this, id)
        }

        sliderVibTimeout.addOnChangeListener { _, value, _ ->
            val sec = value.toInt()
            prefs.edit().putInt(vibTimeoutKeyFor(currentProfileIdx), sec).apply()
            tvVibTimeoutLabel.text = formatTimeout(sec)
        }

        switchHaptic.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(vibHapticKeyFor(currentProfileIdx), checked).apply()
        }
    }

    // ── Load a profile into the UI ─────────────────────────────────────────────

    /**
     * Reads saved prefs for the given profile and refreshes all widgets.
     * For the DEFAULT profile (idx 0), the keys are identical to SettingsActivity's —
     * so changes here are reflected there and vice-versa.
     */
    private fun loadProfile(idx: Int) {
        // Action 1 (Notification profile only)
        if (profiles[idx].taskType == "NOTIFICATION") {
            updateAction1SoundName()
            val a1vol = prefs.getInt(SoundManager.KEY_ACTION1_VOLUME, SoundManager.DEFAULT_ACTION1_VOLUME)
            sliderAction1Volume.value = a1vol.toFloat().coerceIn(0f, 100f)
            tvAction1VolumeLabel.text = "$a1vol%"
        }

        // Sound
        updateSoundName()

        val soundTimeout = prefs.getInt(soundTimeoutKeyFor(idx), SoundManager.DEFAULT_SOUND_TIMEOUT)
        sliderSoundTimeout.value = soundTimeout.toFloat().coerceIn(0f, 900f)
        tvSoundTimeoutLabel.text = formatTimeout(soundTimeout)

        val vol = prefs.getInt(soundVolumeKeyFor(idx), SoundManager.DEFAULT_SOUND_VOLUME)
        sliderVolume.value = vol.toFloat().coerceIn(0f, 100f)
        tvVolumeLabel.text = "$vol%"

        val fade = prefs.getInt(soundFadeInKeyFor(idx), SoundManager.DEFAULT_FADE_IN)
        sliderFadeIn.value = fade.toFloat().coerceIn(0f, 300f)
        tvFadeInLabel.text = if (fade == 0) "Off" else formatTimeout(fade)

        // Vibration
        val patId = prefs.getInt(vibPatternKeyFor(idx), VibrationManager.DEFAULT_PATTERN)
        rgVibPattern.check(patId)

        val vibTimeout = prefs.getInt(vibTimeoutKeyFor(idx), VibrationManager.DEFAULT_TIMEOUT_SEC)
        sliderVibTimeout.value = vibTimeout.toFloat().coerceIn(0f, 900f)
        tvVibTimeoutLabel.text = formatTimeout(vibTimeout)

        val haptic = prefs.getBoolean(vibHapticKeyFor(idx), VibrationManager.DEFAULT_HAPTIC)
        switchHaptic.isChecked = haptic
    }

    private fun updateSoundName() {
        val uriStr = prefs.getString(soundUriKeyFor(currentProfileIdx), null)
        tvSoundName.text = if (uriStr.isNullOrBlank()) {
            "System alarm tone"
        } else {
            try {
                RingtoneManager.getRingtone(this, android.net.Uri.parse(uriStr))
                    ?.getTitle(this) ?: "Custom sound"
            } catch (_: Exception) { "Custom sound" }
        }
    }

    private fun updateAction1SoundName() {
        val uriStr = prefs.getString(SoundManager.KEY_ACTION1_SOUND_URI, null)
        tvAction1SoundName.text = if (uriStr.isNullOrBlank()) {
            "System notification tone"
        } else {
            try {
                RingtoneManager.getRingtone(this, android.net.Uri.parse(uriStr))
                    ?.getTitle(this) ?: "Custom sound"
            } catch (_: Exception) { "Custom sound" }
        }
    }

    // ── Key helpers ────────────────────────────────────────────────────────────

    private fun prefixFor(idx: Int) = SoundManager.prefixFor(profiles[idx].taskType)

    private fun soundUriKeyFor(idx: Int)     = SoundManager.soundUriKey(prefixFor(idx))
    private fun soundTimeoutKeyFor(idx: Int) = SoundManager.soundTimeoutKey(prefixFor(idx))
    private fun soundVolumeKeyFor(idx: Int)  = SoundManager.soundVolumeKey(prefixFor(idx))
    private fun soundFadeInKeyFor(idx: Int)  = SoundManager.soundFadeInKey(prefixFor(idx))

    private fun vibPatternKeyFor(idx: Int)   = VibrationManager.vibPatternKey(prefixFor(idx))
    private fun vibTimeoutKeyFor(idx: Int)   = VibrationManager.vibTimeoutKey(prefixFor(idx))
    private fun vibHapticKeyFor(idx: Int)    = VibrationManager.vibHapticKey(prefixFor(idx))

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun formatTimeout(seconds: Int): String = when {
        seconds == 0      -> "No timeout"
        seconds < 60      -> "${seconds}s"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else              -> "${seconds / 60}m ${seconds % 60}s"
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
