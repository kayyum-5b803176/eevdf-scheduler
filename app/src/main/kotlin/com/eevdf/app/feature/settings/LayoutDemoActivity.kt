package com.eevdf.app.feature.settings

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.eevdf.app.R
import com.eevdf.app.core.template.DropdownCardView
import com.eevdf.app.core.template.NavCardView
import com.eevdf.app.core.template.ToggleCardView
import com.eevdf.app.core.template.ValueCardView

/**
 * Layout demo catalog, opened from Display -> render -> "Layout" card.
 *
 * Every demo card below is built through the closed template construction
 * API in com.eevdf.app.core.template — the same classes any real settings
 * screen uses to build its rows. There is no hand-authored demo card XML
 * anywhere in this module; what renders here is not an approximation of
 * production output, it is production output. See TEMPLATE_CATALOG.md for
 * the piece-sequence rules each class enforces structurally.
 *
 * Ships with the same blank/unwired TabLayout pattern every screen in this
 * catalog carries, per convention.
 */
class LayoutDemoActivity : AppCompatActivity() {

    private lateinit var demoContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_layout_demo)

        val toolbar = findViewById<Toolbar>(R.id.layoutDemoToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Layout"

        demoContainer = findViewById(R.id.demoContainer)
        buildDemos()
    }

    private fun buildDemos() {
        addIntro("Demo instances of each catalog template, built from the same closed construction API real settings pages use. Rendered in compact mode: minimum padding on this page only, scale-rung alignment intentionally dropped in favor of native content height. Real settings screens are unaffected.")

        addLabel("NavCard — (0)")
        demoContainer.addView(
            NavCardView.create(this, title = "demo title", compact = true)
        )
        addMetric("compact: card margin 10dp -> 4dp. Row height stays 48dp (minHeight floor, unaffected by padding).")
        addDivider()

        addLabel("NavCard — (0,1)")
        demoContainer.addView(
            NavCardView.create(this, title = "demo title", subtitle = "demo subtitle text", compact = true)
        )
        addMetric("compact: piece 0 (48dp) + piece 1 (18dp) = 66dp, native height, spacer dropped (was +6dp to app_row_boundary_rung_72)")
        addDivider()

        addLabel("ToggleCard — (0,1)")
        demoContainer.addView(
            ToggleCardView.create(this, title = "demo toggle", description = "demo description text", compact = true)
        )
        addMetric("compact: piece 0 (~32dp) + piece 1 (~20dp) + card padding 16dp (was 40dp) = ~68dp, native height, spacer dropped")
        addDivider()

        addLabel("ValueCard — (0,1,2,3)")
        demoContainer.addView(
            ValueCardView.create(
                this,
                label = "demo label",
                value = "42",
                description = "demo description text",
                slider = ValueCardView.SliderConfig(
                    valueFrom = 0f, valueTo = 100f, stepSize = 1f, value = 42f,
                    captionStart = "0", captionEnd = "100"
                ),
                compact = true
            )
        )
        addMetric("compact: pieces 0+1+2+3 (~104dp) + card padding 16dp (was 40dp) = ~120dp, native height, spacer dropped")
        addDivider()

        addLabel("DropdownCard — (0,1)")
        demoContainer.addView(
            DropdownCardView.create(this, title = "demo label", options = listOf("demo option"), compact = true)
        )
        addMetric("compact: piece 0 (~20dp) + piece 1 (~56dp) + card padding 16dp (was 40dp) = ~92dp, native height, spacer dropped")
    }

    private fun addIntro(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.app_text_size_sm))
        tv.setTextColor(ContextCompat.getColor(this, R.color.app_text_body))
        tv.setPadding(
            resources.getDimensionPixelSize(R.dimen.app_spacing_md), 0,
            resources.getDimensionPixelSize(R.dimen.app_spacing_md),
            resources.getDimensionPixelSize(R.dimen.app_spacing_md)
        )
        demoContainer.addView(tv)
    }

    private fun addLabel(text: String) {
        val tv = TextView(this, null, 0, R.style.App_Text_Label)
        tv.text = text
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val gap = resources.getDimensionPixelSize(R.dimen.app_spacing_md)
        val small = resources.getDimensionPixelSize(R.dimen.app_spacing_sm)
        params.marginStart = gap
        params.marginEnd = gap
        params.bottomMargin = small
        tv.layoutParams = params
        demoContainer.addView(tv)
    }

    private fun addMetric(text: String) {
        val tv = TextView(this, null, 0, R.style.App_Render_Metric)
        tv.text = text
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val gap = resources.getDimensionPixelSize(R.dimen.app_spacing_md)
        val small = resources.getDimensionPixelSize(R.dimen.app_spacing_sm)
        params.marginStart = gap
        params.marginEnd = gap
        params.bottomMargin = small
        tv.layoutParams = params
        demoContainer.addView(tv)
    }

    private fun addDivider() {
        val v = View(this, null, 0, R.style.App_Divider_Section)
        v.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        demoContainer.addView(v)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
