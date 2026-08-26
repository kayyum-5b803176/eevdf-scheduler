package com.eevdf.app.feature.settings

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.eevdf.app.R

/**
 * Layout demo catalog, opened from Display -> render -> "Layout" card.
 *
 * Every card here is built from the same production styles a real settings
 * screen uses — never a copy or a mock. A new template or piece variant is
 * checked here, against these same styles, before it is ever applied to a
 * real settings page. See TEMPLATE_CATALOG.md for the piece-sequence rules
 * each demo is required to conform to.
 *
 * Ships with the same blank/unwired TabLayout pattern DisplaySettingsActivity
 * originally had before its own tabs were wired up — present, not yet
 * connected to any tab content, an intentional extension point for this
 * screen to grow its own tabs later the same way.
 */
class LayoutDemoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_layout_demo)

        val toolbar = findViewById<Toolbar>(R.id.layoutDemoToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Layout"
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
