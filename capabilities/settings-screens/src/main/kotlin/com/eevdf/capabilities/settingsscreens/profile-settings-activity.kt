package com.eevdf.capabilities.settingsscreens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.eevdf.capabilities.settingsscreens.R
import com.eevdf.capabilities.settingsstorage.state.SoundPrefs
import com.eevdf.capabilities.settingsstorage.state.VibrationPrefs
import com.eevdf.capabilities.designsystem.entities.DropdownCardEntity
import com.eevdf.capabilities.designsystem.entities.ValueCardEntity
import com.eevdf.capabilities.designsystem.output.DropdownCardView
import com.eevdf.capabilities.designsystem.output.ValueCardView
import com.eevdf.capabilities.designsystem.renderers.renderDropdownCard
import com.eevdf.capabilities.designsystem.renderers.renderValueCard
import com.eevdf.kernel.eventbus.EventBus
import com.eevdf.kernel.eventbus.Topics
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * RESOLVED (typed-entity + centralized-renderer redesign): the 5 slider
 * rows (Sound Timeout, Default Volume, Gradual Volume Increase, Vibration
 * Timeout, Action Volume) and the Vibration Pattern row used to be
 * hand-authored View trees duplicating ValueCardView's/DropdownCardView's
 * internal layouts by hand. Now real instances via the shared renderers —
 * see `activity_profile_settings.xml`'s comments for the matching XML-side
 * change. The 3 sound-picker rows (Timer/Execute/Wait Sound) stay
 * hand-built: title+subtitle+trailing-button with no dropdown and no
 * chevron doesn't match any of the 4 confirmed templates.
 */
@AndroidEntryPoint
class ProfileSettingsActivity : AppCompatActivity() {

    @Inject lateinit var bus: EventBus

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
    private lateinit var valueCardSoundTimeout: ValueCardView
    private lateinit var valueCardVolume:       ValueCardView
    private lateinit var valueCardFadeIn:       ValueCardView

    // ── Vibration widgets ─────────────────────────────────────────────────────
    private lateinit var dropdownVibPattern:   DropdownCardView
    private lateinit var valueCardVibTimeout:  ValueCardView

    // ── Action section (Notice only) ──────────────────────────────────────────
    private lateinit var layoutActionSection:  LinearLayout
    private lateinit var tvExecuteSoundName:   TextView
    private lateinit var btnPickExecuteSound:  MaterialButton
    private lateinit var tvWaitSoundName:      TextView
    private lateinit var btnPickWaitSound:     MaterialButton
    private lateinit var valueCardActionVolume: ValueCardView

    private val prefs by lazy { getSharedPreferences("eevdf_prefs", MODE_PRIVATE) }

    // ── Sound picker launchers ─────────────────────────────────────────────────
    private val profileSoundLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri: android.net.Uri? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, android.net.Uri::class.java)
            else
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            prefs.edit().putString(soundUriKeyFor(currentProfileIdx), uri?.toString()).apply()
            updateSoundName()
        }
    }
    private val executeSoundLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri: android.net.Uri? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, android.net.Uri::class.java)
            else
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            prefs.edit().putString(SoundPrefs.KEY_EXECUTE_SOUND_URI, uri?.toString()).apply()
            updateExecuteSoundName()
        }
    }
    private val waitSoundLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri: android.net.Uri? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, android.net.Uri::class.java)
            else
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            prefs.edit().putString(SoundPrefs.KEY_WAIT_SOUND_URI, uri?.toString()).apply()
            updateWaitSoundName()
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
        layoutActionSection = findViewById(R.id.layoutActionSection)
        tvExecuteSoundName  = findViewById(R.id.tvExecuteSoundName)
        btnPickExecuteSound = findViewById(R.id.btnPickExecuteSound)
        tvWaitSoundName     = findViewById(R.id.tvWaitSoundName)
        btnPickWaitSound    = findViewById(R.id.btnPickWaitSound)

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

        buildCards()
        setupListeners()
        loadProfile(0)
    }

    // ── Setup ──────────────────────────────────────────────────────────────────

    /**
     * Every ValueCard/DropdownCard on this screen — built once here, kept
     * alive as fields so [loadProfile] can update their displayed value on
     * every tab switch without rebuilding them (same pattern
     * `layout-demo-activity.kt`'s `scaleSlider` self-referencing callback
     * uses: `.slider = slider?.copy(...)` updates the slider's position
     * without disturbing the callback attached to it).
     */
    private fun buildCards() {
        valueCardSoundTimeout = renderValueCard(this, ValueCardEntity(
            label = "Sound Timeout",
            value = "1 min",
            description = "How long sound plays after timer expires (0 = no timeout)",
            slider = ValueCardEntity.SliderSpec(
                valueFrom = 0f, valueTo = 900f, stepSize = 15f, value = 60f,
                captionStart = "No timeout", captionEnd = "15 min",
                onValueChange = { v ->
                    prefs.edit().putInt(soundTimeoutKeyFor(currentProfileIdx), v.toInt()).apply()
                    valueCardSoundTimeout.value = formatTimeout(v.toInt())
                },
            ),
        ))
        findViewById<FrameLayout>(R.id.soundTimeoutContainer).addView(valueCardSoundTimeout)

        valueCardVolume = renderValueCard(this, ValueCardEntity(
            label = "Default Volume",
            value = "80%",
            slider = ValueCardEntity.SliderSpec(
                valueFrom = 0f, valueTo = 100f, stepSize = 5f, value = 80f,
                captionStart = "0%", captionEnd = "100%",
                onValueChange = { v ->
                    prefs.edit().putInt(soundVolumeKeyFor(currentProfileIdx), v.toInt()).apply()
                    valueCardVolume.value = "${v.toInt()}%"
                },
            ),
        ))
        findViewById<FrameLayout>(R.id.volumeContainer).addView(valueCardVolume)

        valueCardFadeIn = renderValueCard(this, ValueCardEntity(
            label = "Gradual Volume Increase",
            value = "Off",
            slider = ValueCardEntity.SliderSpec(
                valueFrom = 0f, valueTo = 300f, stepSize = 15f, value = 0f,
                captionStart = "Off", captionEnd = "5 min",
                onValueChange = { v ->
                    prefs.edit().putInt(soundFadeInKeyFor(currentProfileIdx), v.toInt()).apply()
                    valueCardFadeIn.value = if (v.toInt() == 0) "Off" else formatTimeout(v.toInt())
                },
            ),
        ))
        findViewById<FrameLayout>(R.id.fadeInContainer).addView(valueCardFadeIn)

        dropdownVibPattern = renderDropdownCard(this, DropdownCardEntity(
            title = "Vibration Pattern",
            options = VibrationPrefs.PATTERNS.map { it.name },
            helperActionText = "Preview",
            onOptionSelected = { name ->
                val pos = VibrationPrefs.PATTERNS.indexOfFirst { it.name == name }.coerceAtLeast(0)
                prefs.edit().putInt(vibPatternKeyFor(currentProfileIdx), pos).apply()
            },
            onHelperAction = {
                // vibration.preview-requested (rule 3): replaces the old direct
                // VibrationManager.preview() call — this screen has zero
                // dependency on the `vibration` capability now.
                val patternId = prefs.getInt(vibPatternKeyFor(currentProfileIdx), VibrationPrefs.DEFAULT_PATTERN)
                lifecycleScope.launch { bus.publish(Topics.VIBRATION_PREVIEW_REQUESTED, patternId, "settings-screens") }
            },
        ))
        findViewById<FrameLayout>(R.id.vibPatternContainer).addView(dropdownVibPattern)

        valueCardVibTimeout = renderValueCard(this, ValueCardEntity(
            label = "Vibration Timeout",
            value = "1 min",
            slider = ValueCardEntity.SliderSpec(
                valueFrom = 0f, valueTo = 900f, stepSize = 15f, value = 60f,
                captionStart = "No timeout", captionEnd = "15 min",
                onValueChange = { v ->
                    prefs.edit().putInt(vibTimeoutKeyFor(currentProfileIdx), v.toInt()).apply()
                    valueCardVibTimeout.value = formatTimeout(v.toInt())
                },
            ),
        ))
        findViewById<FrameLayout>(R.id.vibTimeoutContainer).addView(valueCardVibTimeout)

        valueCardActionVolume = renderValueCard(this, ValueCardEntity(
            label = "Action Volume",
            value = "80%",
            description = "System notification volume for execute & wait sounds. Restored after playback.",
            slider = ValueCardEntity.SliderSpec(
                valueFrom = 0f, valueTo = 100f, stepSize = 5f, value = 80f,
                captionStart = "0%", captionEnd = "100%",
                onValueChange = { v ->
                    prefs.edit().putInt(SoundPrefs.KEY_ACTION_VOLUME, v.toInt()).apply()
                    valueCardActionVolume.value = "${v.toInt()}%"
                },
            ),
        ))
        findViewById<FrameLayout>(R.id.actionVolumeContainer).addView(valueCardActionVolume)
    }

    private fun setupListeners() {
        btnPickSound.setOnClickListener {
            launchRingtonePicker(profileSoundLauncher,
                prefs.getString(soundUriKeyFor(currentProfileIdx), null),
                RingtoneManager.TYPE_ALARM, "Select Timer Sound")
        }
        // Action section
        btnPickExecuteSound.setOnClickListener {
            launchRingtonePicker(executeSoundLauncher,
                prefs.getString(SoundPrefs.KEY_EXECUTE_SOUND_URI, null),
                RingtoneManager.TYPE_NOTIFICATION, "Select Execute Sound")
        }
        btnPickWaitSound.setOnClickListener {
            launchRingtonePicker(waitSoundLauncher,
                prefs.getString(SoundPrefs.KEY_WAIT_SOUND_URI, null),
                RingtoneManager.TYPE_NOTIFICATION, "Select Wait Sound")
        }
    }

    // ── Load profile ───────────────────────────────────────────────────────────
    private fun loadProfile(idx: Int) {
        updateSoundName()

        val soundTimeout = prefs.getInt(soundTimeoutKeyFor(idx), SoundPrefs.DEFAULT_SOUND_TIMEOUT).coerceIn(0, 900)
        valueCardSoundTimeout.value  = formatTimeout(soundTimeout)
        valueCardSoundTimeout.slider = valueCardSoundTimeout.slider?.copy(value = soundTimeout.toFloat())

        val volume = prefs.getInt(soundVolumeKeyFor(idx), SoundPrefs.DEFAULT_SOUND_VOLUME).coerceIn(0, 100)
        valueCardVolume.value  = "$volume%"
        valueCardVolume.slider = valueCardVolume.slider?.copy(value = volume.toFloat())

        val fade = prefs.getInt(soundFadeInKeyFor(idx), SoundPrefs.DEFAULT_FADE_IN).coerceIn(0, 300)
        valueCardFadeIn.value  = if (fade == 0) "Off" else formatTimeout(fade)
        valueCardFadeIn.slider = valueCardFadeIn.slider?.copy(value = fade.toFloat())

        val patId = prefs.getInt(vibPatternKeyFor(idx), VibrationPrefs.DEFAULT_PATTERN)
        dropdownVibPattern.selectedOption = VibrationPrefs.PATTERNS[patId.coerceIn(0, VibrationPrefs.PATTERNS.size - 1)].name

        val vibTimeout = prefs.getInt(vibTimeoutKeyFor(idx), VibrationPrefs.DEFAULT_TIMEOUT_SEC).coerceIn(0, 900)
        valueCardVibTimeout.value  = formatTimeout(vibTimeout)
        valueCardVibTimeout.slider = valueCardVibTimeout.slider?.copy(value = vibTimeout.toFloat())

        if (profiles[idx].taskType == "NOTIFICATION") {
            updateExecuteSoundName(); updateWaitSoundName()
            val av = prefs.getInt(SoundPrefs.KEY_ACTION_VOLUME, SoundPrefs.DEFAULT_ACTION_VOLUME).coerceIn(0, 100)
            valueCardActionVolume.value  = "$av%"
            valueCardActionVolume.slider = valueCardActionVolume.slider?.copy(value = av.toFloat())
        }
    }

    // ── Name updaters ─────────────────────────────────────────────────────────
    private fun updateSoundName() {
        tvSoundName.text = resolveRingtoneName(prefs.getString(soundUriKeyFor(currentProfileIdx), null), "System alarm tone")
    }
    private fun updateExecuteSoundName() {
        tvExecuteSoundName.text = resolveRingtoneName(prefs.getString(SoundPrefs.KEY_EXECUTE_SOUND_URI, null), "System notification tone")
    }
    private fun updateWaitSoundName() {
        tvWaitSoundName.text = resolveRingtoneName(prefs.getString(SoundPrefs.KEY_WAIT_SOUND_URI, null), "System notification tone")
    }
    private fun resolveRingtoneName(uriStr: String?, fallback: String): String {
        if (uriStr.isNullOrBlank()) return fallback
        return try {
            RingtoneManager.getRingtone(this, android.net.Uri.parse(uriStr))?.getTitle(this) ?: "Custom sound"
        } catch (_: Exception) { "Custom sound" }
    }

    // ── Key helpers ────────────────────────────────────────────────────────────
    private fun prefixFor(idx: Int) = SoundPrefs.prefixFor(profiles[idx].taskType)
    private fun soundUriKeyFor(idx: Int)     = SoundPrefs.soundUriKey(prefixFor(idx))
    private fun soundTimeoutKeyFor(idx: Int) = SoundPrefs.soundTimeoutKey(prefixFor(idx))
    private fun soundVolumeKeyFor(idx: Int)  = SoundPrefs.soundVolumeKey(prefixFor(idx))
    private fun soundFadeInKeyFor(idx: Int)  = SoundPrefs.soundFadeInKey(prefixFor(idx))
    private fun vibPatternKeyFor(idx: Int)   = VibrationPrefs.vibPatternKey(prefixFor(idx))
    private fun vibTimeoutKeyFor(idx: Int)   = VibrationPrefs.vibTimeoutKey(prefixFor(idx))

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
