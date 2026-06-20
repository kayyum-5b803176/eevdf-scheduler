package com.eevdf.app.feature.settings

import android.os.Bundle
import android.view.MenuItem
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.eevdf.app.R

/**
 * Per-key action chooser opened from [HardwareKeyActionActivity].
 *
 * Shows the three selectable actions (None / Stop / Stop and Start) as a
 * radio group.  An action that is already bound to a DIFFERENT key is shown
 * disabled with a hint, enforcing requirement #3 (one action can't be selected
 * by more than one key).  Selecting an action persists immediately via
 * [HardwareKeyPrefs.setAction], which also clears that action from any other
 * key as a safety net.
 */
class HardwareKeyOptionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KEY = "hw_key_id"
    }

    private lateinit var keyId: String
    private lateinit var radioGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hardware_key_option)

        keyId = intent.getStringExtra(EXTRA_KEY) ?: HardwareKeyPrefs.KEY_VOLUME_UP

        val toolbar = findViewById<Toolbar>(R.id.hwKeyOptionToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = HardwareKeyPrefs.keyLabel(keyId)

        radioGroup = findViewById(R.id.radioGroupHwKeyOption)

        buildOptions()
    }

    private fun buildOptions() {
        radioGroup.removeAllViews()
        radioGroup.setOnCheckedChangeListener(null)

        val current = HardwareKeyPrefs.getAction(this, keyId)

        HardwareKeyPrefs.SELECTABLE_ACTIONS.forEachIndexed { index, action ->
            val rb = RadioButton(this).apply {
                id = index + 1                       // 1-based, never View.NO_ID
                text = HardwareKeyPrefs.actionLabel(action)
                textSize = 16f
                setPadding(8, 32, 8, 32)
                tag = action
                isChecked = action == current
            }

            // Exclusivity: disable an action owned by another key (NONE is always free).
            val ownerKey = HardwareKeyPrefs.keyBoundTo(this, action, exceptKey = keyId)
            if (ownerKey != null) {
                rb.isEnabled = false
                rb.text = "${HardwareKeyPrefs.actionLabel(action)}  " +
                    "(used by ${HardwareKeyPrefs.keyLabel(ownerKey)})"
            }

            radioGroup.addView(rb)
        }

        radioGroup.setOnCheckedChangeListener { group, checkedId ->
            val rb = group.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            val action = rb.tag as? String ?: return@setOnCheckedChangeListener
            HardwareKeyPrefs.setAction(this, keyId, action)
            // Rebuild so disabled hints stay accurate after an exclusivity steal.
            buildOptions()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
