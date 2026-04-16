package com.eevdf.scheduler.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.eevdf.scheduler.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout

class ProfileSettingsActivity : AppCompatActivity() {

    private data class Profile(val label: String, val taskType: String)
    private val profiles = listOf(
        Profile("Default", "DEFAULT"),
        Profile("Notice",  "NOTIFICATION"),
        Profile("Alert",   "ALARM"),
        Profile("Custom",  "CUSTOM")
    )
    private var currentProfileIdx = 0

    // ── Sound widgets ─────────────────────────────────────────────────────────
    private lateinit var tabLayout:            TabLayout
    private lateinit var tvSoundName:          TextView
    private lateinit var btnPickSound:         MaterialButton
    private lateinit var sliderSoundTimeout:   Slider
    private lateinit var tvSoundTimeoutLabel:  TextView
    private lateinit var sliderVolume:         Slider
    private lateinit var tvVolumeLabel:        TextView
    private lateinit var sliderFadeIn:         Slider
    private lateinit var tvFadeInLabel:        TextView

    // ── Vibration widgets ─────────────────────────────────────────────────────
    private lateinit var spinnerVibPattern:    Spinner
    private lateinit var btnPreviewVib:        MaterialButton
    private lateinit var sliderVibTimeout:     Slider
    private lateinit var tvVibTimeoutLabel:    TextView

    // ── Action section (Notice only) ──────────────────────────────────────────
    private lateinit var layoutActionSection:  LinearLayout
    private lateinit var tvDelaySoundName:     TextView
    private lateinit var btnPickDelaySound:    MaterialButton
    private lateinit var tvRestSoundName:      TextView
    private lateinit var btnPickRestSound:     MaterialButton
    private lateinit var sliderActionVolume:   Slider
    private lateinit var tvActionVolumeLabel:  TextView

    private val prefs by lazy { getSharedPreferences("eevdf_prefs", Context.MODE_PRIVATE) }

    // ── Sound picker launchers ─────────────────────────────────────────────────
    private val profileSoundLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            prefs.edit().putString(soundUriKeyFor(currentProfileIdx), uri?.toString()).apply()
            updateSoundName()
        }
    }
    private val delaySoundLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            prefs.edit().putString(SoundManager.KEY_DELAY_SOUND_URI, uri?.toString()).apply()
            updateDelaySoundName()
        }
    }
    private val restSoundLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            prefs.edit().putString(SoundManager.KEY_REST_SOUND_URI, uri?.toString()).apply()
            updateRestSoundName()
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_settings)

        setSupportActionBar(findViewById<Toolbar>(R.id.profileSettingsToolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Task Type Profiles"

        tabLayout           = findViewById(R.id.profileTabLayout)
        tvSoundName         = findViewById(R.id.tvProfileSoundName)
        btnPickSound        = findViewById(R.id.btnProfilePickSound)
        sliderSoundTimeout  = findViewById(R.id.sliderProfileSoundTimeout)
        tvSoundTimeoutLabel = findViewById(R.id.tvProfileSoundTimeoutLabel)
        sliderVolume        = findViewById(R.id.sliderProfileVolume)
        tvVolumeLabel       = findViewById(R.id.tvProfileVolumeLabel)
        sliderFadeIn        = findViewById(R.id.sliderProfileFadeIn)
        tvFadeInLabel       = findViewById(R.id.tvProfileFadeInLabel)
        spinnerVibPattern   = findViewById(R.id.spinnerProfileVibPattern)
        btnPreviewVib       = findViewById(R.id.btnProfilePreviewVib)
        sliderVibTimeout    = findViewById(R.id.sliderProfileVibTimeout)
        tvVibTimeoutLabel   = findViewById(R.id.tvProfileVibTimeoutLabel)
        layoutActionSection = findViewById(R.id.layoutActionSection)
        tvDelaySoundName    = findViewById(R.id.tvDelaySoundName)
        btnPickDelaySound   = findViewById(R.id.btnPickDelaySound)
        tvRestSoundName     = findViewById(R.id.tvRestSoundName)
        btnPickRestSound    = findViewById(R.id.btnPickRestSound)
        sliderActionVolume  = findViewById(R.id.sliderActionVolume)
        tvActionVolumeLabel = findViewById(R.id.tvActionVolumeLabel)

        // Build tabs
        profiles.forEach { tabLayout.addTab(tabLayout.newTab().setText(it.label)) }
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentProfileIdx = tab.position
                val isNotice = profiles[tab.position].taskType == "NOTIFICATION"
                layoutActionSection.visibility = if (isNotice) View.VISIBLE else View.GONE
                loadProfile(currentProfileIdx)
            }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })

        setupVibSpinner()
        setupListeners()
        loadProfile(0)
    }

    // ── Setup ──────────────────────────────────────────────────────────────────
    private fun setupVibSpinner() {
        val labels = VibrationManager.PATTERNS.map { it.name }
        spinnerVibPattern.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun setupListeners() {
        btnPickSound.setOnClickListener {
            launchRingtonePicker(profileSoundLauncher,
                prefs.getString(soundUriKeyFor(currentProfileIdx), null),
                RingtoneManager.TYPE_ALARM, "Select Timer Sound")
        }
        sliderSoundTimeout.addOnChangeListener { _, v, _ ->
            prefs.edit().putInt(soundTimeoutKeyFor(currentProfileIdx), v.toInt()).apply()
            tvSoundTimeoutLabel.text = formatTimeout(v.toInt())
        }
        sliderVolume.addOnChangeListener { _, v, _ ->
            prefs.edit().putInt(soundVolumeKeyFor(currentProfileIdx), v.toInt()).apply()
            tvVolumeLabel.text = "${v.toInt()}%"
        }
        sliderFadeIn.addOnChangeListener { _, v, _ ->
            prefs.edit().putInt(soundFadeInKeyFor(currentProfileIdx), v.toInt()).apply()
            tvFadeInLabel.text = if (v.toInt() == 0) "Off" else formatTimeout(v.toInt())
        }
        spinnerVibPattern.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                prefs.edit().putInt(vibPatternKeyFor(currentProfileIdx), pos).apply()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>) {}
        }
        btnPreviewVib.setOnClickListener {
            VibrationManager.preview(this, prefs.getInt(vibPatternKeyFor(currentProfileIdx), VibrationManager.DEFAULT_PATTERN))
        }
        sliderVibTimeout.addOnChangeListener { _, v, _ ->
            prefs.edit().putInt(vibTimeoutKeyFor(currentProfileIdx), v.toInt()).apply()
            tvVibTimeoutLabel.text = formatTimeout(v.toInt())
        }
        // Action section
        btnPickDelaySound.setOnClickListener {
            launchRingtonePicker(delaySoundLauncher,
                prefs.getString(SoundManager.KEY_DELAY_SOUND_URI, null),
                RingtoneManager.TYPE_NOTIFICATION, "Select Delay Sound")
        }
        btnPickRestSound.setOnClickListener {
            launchRingtonePicker(restSoundLauncher,
                prefs.getString(SoundManager.KEY_REST_SOUND_URI, null),
                RingtoneManager.TYPE_NOTIFICATION, "Select Rest Sound")
        }
        sliderActionVolume.addOnChangeListener { _, v, _ ->
            prefs.edit().putInt(SoundManager.KEY_ACTION_VOLUME, v.toInt()).apply()
            tvActionVolumeLabel.text = "${v.toInt()}%"
        }
    }

    // ── Load profile ───────────────────────────────────────────────────────────
    private fun loadProfile(idx: Int) {
        updateSoundName()
        sliderSoundTimeout.value = prefs.getInt(soundTimeoutKeyFor(idx), SoundManager.DEFAULT_SOUND_TIMEOUT).toFloat().coerceIn(0f, 900f)
        tvSoundTimeoutLabel.text = formatTimeout(sliderSoundTimeout.value.toInt())
        sliderVolume.value       = prefs.getInt(soundVolumeKeyFor(idx), SoundManager.DEFAULT_SOUND_VOLUME).toFloat().coerceIn(0f, 100f)
        tvVolumeLabel.text       = "${sliderVolume.value.toInt()}%"
        val fade = prefs.getInt(soundFadeInKeyFor(idx), SoundManager.DEFAULT_FADE_IN)
        sliderFadeIn.value       = fade.toFloat().coerceIn(0f, 300f)
        tvFadeInLabel.text       = if (fade == 0) "Off" else formatTimeout(fade)

        val patId = prefs.getInt(vibPatternKeyFor(idx), VibrationManager.DEFAULT_PATTERN)
        spinnerVibPattern.setSelection(patId.coerceIn(0, VibrationManager.PATTERNS.size - 1))
        sliderVibTimeout.value   = prefs.getInt(vibTimeoutKeyFor(idx), VibrationManager.DEFAULT_TIMEOUT_SEC).toFloat().coerceIn(0f, 900f)
        tvVibTimeoutLabel.text   = formatTimeout(sliderVibTimeout.value.toInt())

        if (profiles[idx].taskType == "NOTIFICATION") {
            updateDelaySoundName(); updateRestSoundName()
            val av = prefs.getInt(SoundManager.KEY_ACTION_VOLUME, SoundManager.DEFAULT_ACTION_VOLUME)
            sliderActionVolume.value  = av.toFloat().coerceIn(0f, 100f)
            tvActionVolumeLabel.text  = "$av%"
        }
    }

    // ── Name updaters ─────────────────────────────────────────────────────────
    private fun updateSoundName() {
        tvSoundName.text = resolveRingtoneName(prefs.getString(soundUriKeyFor(currentProfileIdx), null), "System alarm tone")
    }
    private fun updateDelaySoundName() {
        tvDelaySoundName.text = resolveRingtoneName(prefs.getString(SoundManager.KEY_DELAY_SOUND_URI, null), "System notification tone")
    }
    private fun updateRestSoundName() {
        tvRestSoundName.text = resolveRingtoneName(prefs.getString(SoundManager.KEY_REST_SOUND_URI, null), "System notification tone")
    }
    private fun resolveRingtoneName(uriStr: String?, fallback: String): String {
        if (uriStr.isNullOrBlank()) return fallback
        return try {
            RingtoneManager.getRingtone(this, android.net.Uri.parse(uriStr))?.getTitle(this) ?: "Custom sound"
        } catch (_: Exception) { "Custom sound" }
    }

    // ── Key helpers ────────────────────────────────────────────────────────────
    private fun prefixFor(idx: Int) = SoundManager.prefixFor(profiles[idx].taskType)
    private fun soundUriKeyFor(idx: Int)     = SoundManager.soundUriKey(prefixFor(idx))
    private fun soundTimeoutKeyFor(idx: Int) = SoundManager.soundTimeoutKey(prefixFor(idx))
    private fun soundVolumeKeyFor(idx: Int)  = SoundManager.soundVolumeKey(prefixFor(idx))
    private fun soundFadeInKeyFor(idx: Int)  = SoundManager.soundFadeInKey(prefixFor(idx))
    private fun vibPatternKeyFor(idx: Int)   = VibrationManager.vibPatternKey(prefixFor(idx))
    private fun vibTimeoutKeyFor(idx: Int)   = VibrationManager.vibTimeoutKey(prefixFor(idx))

    // ── Helpers ────────────────────────────────────────────────────────────────
    private fun launchRingtonePicker(
        launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
        currentUriStr: String?,
        type: Int,
        title: String
    ) {
        val current = currentUriStr?.let { android.net.Uri.parse(it) }
        val intent  = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, title)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            if (current != null) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        }
        launcher.launch(intent)
    }

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
