package com.eevdf.app.feature.settings

import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import com.eevdf.app.R
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

class UiCustomizationActivity : AppCompatActivity() {

    private lateinit var sliderCardHeight:   Slider
    private lateinit var tvCardHeightValue:  TextView
    private lateinit var switchAutoAdjust:   SwitchMaterial
    private lateinit var switchSimpleMode:   SwitchMaterial
    private lateinit var switchUnitFormat:   SwitchMaterial

    // ── Overlay Intent ────────────────────────────────────────────────────────
    private lateinit var switchOverlayIntent: SwitchMaterial
    private lateinit var switchOverlayIntentLockOnly: SwitchMaterial
    private lateinit var rowOverlayIntentApps: android.widget.LinearLayout
    private lateinit var tvOverlayIntentApps:  TextView

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

        switchOverlayIntent  = findViewById(R.id.switchOverlayIntent)
        switchOverlayIntentLockOnly = findViewById(R.id.switchOverlayIntentLockOnly)
        rowOverlayIntentApps = findViewById(R.id.rowOverlayIntentApps)
        tvOverlayIntentApps  = findViewById(R.id.tvOverlayIntentApps)

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

        switchOverlayIntent.isChecked = UiCustomizationPrefs.isOverlayIntentEnabled(this)
        switchOverlayIntentLockOnly.isChecked = UiCustomizationPrefs.isOverlayIntentLockOnly(this)
        refreshOverlayIntentAppsSummary()
        updateOverlayIntentRowState(switchOverlayIntent.isChecked)

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

        switchOverlayIntent.setOnCheckedChangeListener { _, isChecked ->
            UiCustomizationPrefs.setOverlayIntentEnabled(this, isChecked)
            updateOverlayIntentRowState(isChecked)
            // App-list mode needs Usage Access to read the foreground app.
            // Lock-only mode does not (it uses the keyguard state), so don't
            // prompt when lock-only is the active mode.
            val needsUsage = isChecked &&
                !switchOverlayIntentLockOnly.isChecked &&
                !hasUsageStatsPermission()
            if (needsUsage) showUsageAccessDialog()
        }
        rowOverlayIntentApps.setOnClickListener { showOverlayIntentAppPicker() }

        switchOverlayIntentLockOnly.setOnCheckedChangeListener { _, isChecked ->
            UiCustomizationPrefs.setOverlayIntentLockOnly(this, isChecked)
            updateOverlayIntentRowState(switchOverlayIntent.isChecked)
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

    // ── Overlay Intent: app picker (same picker UI as Auto Switch / bubble) ────

    private data class AppInfo(val packageName: String, val label: String)

    private fun getInstalledUserApps(): List<AppInfo> =
        packageManager
            .getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            .filter { ai ->
                ai.packageName != packageName &&
                packageManager.getLaunchIntentForPackage(ai.packageName) != null
            }
            .map { ai -> AppInfo(ai.packageName, ai.loadLabel(packageManager).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }

    private fun showOverlayIntentAppPicker() {
        val apps         = getInstalledUserApps()
        val currentSet   = UiCustomizationPrefs.getOverlayIntentAppList(this)
        val mutableCheck = apps.map { it.packageName in currentSet }.toBooleanArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Hide overlay on these apps")
            .setMultiChoiceItems(
                apps.map { it.label }.toTypedArray(), mutableCheck
            ) { _, which, isChecked -> mutableCheck[which] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                val selected = apps.filterIndexed { i, _ -> mutableCheck[i] }
                    .map { it.packageName }.toSet()
                UiCustomizationPrefs.setOverlayIntentAppList(this, selected)
                refreshOverlayIntentAppsSummary()
            }
            .setNeutralButton("Clear all") { _, _ ->
                UiCustomizationPrefs.setOverlayIntentAppList(this, emptySet())
                refreshOverlayIntentAppsSummary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshOverlayIntentAppsSummary() {
        val list = UiCustomizationPrefs.getOverlayIntentAppList(this)
        tvOverlayIntentApps.text = if (list.isEmpty()) "No apps selected"
        else list.joinToString(", ") { pkg ->
            try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(pkg, 0)
                ).toString()
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) { pkg }
        }
    }

    /**
     * Reflects sub-control availability:
     *   • Lock-only switch is usable only when the feature is enabled.
     *   • The app list is irrelevant when lock-only is on (it overrides the list)
     *     or when the feature is off, so dim it in those cases.
     */
    private fun updateOverlayIntentRowState(enabled: Boolean) {
        val lockOnly = switchOverlayIntentLockOnly.isChecked
        switchOverlayIntentLockOnly.isEnabled = enabled
        switchOverlayIntentLockOnly.alpha = if (enabled) 1.0f else 0.5f

        val appsUsable = enabled && !lockOnly
        rowOverlayIntentApps.alpha = if (appsUsable) 1.0f else 0.5f
        rowOverlayIntentApps.isEnabled = appsUsable
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName)
        else
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun showUsageAccessDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Usage access needed")
            .setMessage(
                "Overlay Intent needs Usage Access to detect which app is in the " +
                "foreground when a timer expires. Without it, the overlay will " +
                "always show."
            )
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, "Couldn't open Usage Access settings",
                        Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }
}
