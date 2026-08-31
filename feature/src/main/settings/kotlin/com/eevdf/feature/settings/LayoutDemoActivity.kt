package com.eevdf.feature.settings

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.eevdf.feature.R
import com.eevdf.feature.shared.prefs.DisplayPrefs
import com.eevdf.feature.ui.DropdownCardView
import com.eevdf.feature.ui.LayoutTokenPrefs
import com.eevdf.feature.ui.ModelDiagramView
import com.eevdf.feature.ui.NavCardView
import com.eevdf.feature.ui.ToggleCardView
import com.eevdf.feature.ui.ValueCardView
import com.google.android.material.tabs.TabLayout

/**
 * Layout demo catalog, opened from Display -> render -> "Layout" card.
 *
 * Two tabs: "template" (default) — four live scale sliders at the top,
 * followed by demo instances of every catalog template built through the
 * closed template construction API in com.eevdf.feature.ui, the same
 * classes any real settings screen uses to build its rows — and "model", a
 * single global box-model diagram read from the same live tokens. There is
 * no hand-authored demo card XML anywhere in this module; what renders in
 * "template" is not an approximation of production output, it is production
 * output. See TEMPLATE_CATALOG.md for the piece-sequence rules each class
 * enforces structurally.
 *
 * SANDBOXED, NOT PERMANENT (Phase 4 of the UI-unification work): the four
 * sliders write to the real [LayoutTokenPrefs]/[DisplayPrefs] — the same
 * preferences [com.eevdf.feature.ui.CardDensity] reads everywhere else — so
 * this page genuinely proves the round-trip works, not a fake preview.
 * [savedTokens] captures whatever was actually set before this Activity
 * touched anything, and [onDestroy] restores it, so leaving this page undoes
 * every change made while visiting. Real settings pages that expose these
 * controls for real (a later phase) will not have this restore step.
 *
 * This is the first screen in the catalog whose own previously-blank
 * TabLayout extension point gets wired up, rather than shipping unused.
 */
class LayoutDemoActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var demoContainer: LinearLayout
    private lateinit var modelContainer: LinearLayout
    private lateinit var previewContainer: LinearLayout
    private lateinit var modelDiagram: ModelDiagramView

    /** Captured in [onCreate], restored in [onDestroy] — see class doc. */
    private data class SavedTokens(
        val padding: Int, val margin: Int, val text: Int, val corner: Int
    )
    private lateinit var savedTokens: SavedTokens

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_layout_demo)

        savedTokens = SavedTokens(
            padding = DisplayPrefs.getCardHeightScale(this),
            margin = LayoutTokenPrefs.getMarginScale(this),
            text = LayoutTokenPrefs.getTextScale(this),
            corner = LayoutTokenPrefs.getCornerRadiusScale(this),
        )

        val toolbar = findViewById<Toolbar>(R.id.layoutDemoToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Layout"

        tabLayout = findViewById(R.id.tabLayout)
        demoContainer = findViewById(R.id.demoContainer)
        modelContainer = findViewById(R.id.modelContainer)

        tabLayout.addTab(tabLayout.newTab().setText("template"))
        tabLayout.addTab(tabLayout.newTab().setText("model"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val showTemplate = tab.position == 0
                demoContainer.visibility = if (showTemplate) View.VISIBLE else View.GONE
                modelContainer.visibility = if (showTemplate) View.GONE else View.VISIBLE
                // The model diagram is a single static instance, not rebuilt
                // like the template previews below — refresh it explicitly
                // so switching tabs after adjusting a slider never shows
                // stale numbers.
                if (!showTemplate) modelDiagram.refresh()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        modelDiagram = findViewById(R.id.modelDiagramView)

        buildControls()

        previewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        demoContainer.addView(previewContainer)
        buildPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Undo everything this screen changed — see class doc's "SANDBOXED,
        // NOT PERMANENT" note. Always runs, even if the Activity is being
        // destroyed by a configuration change, not just Back — acceptable
        // here since this is a debug/demo screen, not a real settings page.
        DisplayPrefs.setCardHeightScale(this, savedTokens.padding)
        LayoutTokenPrefs.setMarginScale(this, savedTokens.margin)
        LayoutTokenPrefs.setTextScale(this, savedTokens.text)
        LayoutTokenPrefs.setCornerRadiusScale(this, savedTokens.corner)
    }

    /**
     * The four live scale controls. Built with [ValueCardView] itself —
     * the template system testing itself — each backed by a real preference
     * setter, each triggering a full preview rebuild on change so the effect
     * is immediately visible below.
     */
    private fun buildControls() {
        addLabel("Live scale controls — changes here are temporary, undone on leaving this page")

        demoContainer.addView(scaleSlider(
            label = "Padding scale",
            initial = savedTokens.padding,
            onChange = { DisplayPrefs.setCardHeightScale(this, it) },
        ))
        demoContainer.addView(scaleSlider(
            label = "Margin scale",
            initial = savedTokens.margin,
            onChange = { LayoutTokenPrefs.setMarginScale(this, it) },
        ))
        demoContainer.addView(scaleSlider(
            label = "Text scale",
            initial = savedTokens.text,
            onChange = { LayoutTokenPrefs.setTextScale(this, it) },
        ))
        demoContainer.addView(scaleSlider(
            label = "Corner radius scale",
            initial = savedTokens.corner,
            onChange = { LayoutTokenPrefs.setCornerRadiusScale(this, it) },
        ))

        addDivider()
    }

    private fun scaleSlider(label: String, initial: Int, onChange: (Int) -> Unit): ValueCardView =
        ValueCardView.create(
            this,
            label = label,
            value = initial.toString(),
            slider = ValueCardView.SliderConfig(
                valueFrom = 1f, valueTo = 5f, stepSize = 1f, value = initial.toFloat(),
                captionStart = "1 (smallest)", captionEnd = "5 (largest)",
            ),
        ).apply {
            slider = slider?.copy(
                onValueChange = { newValue ->
                    val scale = newValue.toInt()
                    onChange(scale)
                    this.value = scale.toString()
                    buildPreview()
                    if (modelContainer.visibility == View.VISIBLE) modelDiagram.refresh()
                }
            )
        }

    /**
     * Every demo template instance, rebuilt from scratch on every slider
     * change above. Not the most efficient approach, but this is a debug
     * catalog page showing five small views, not a real production list —
     * correctness and simplicity win over avoiding a rebuild here.
     *
     * compact = false on every instance (changed from true — see
     * ARCHITECTURE.md Phase 4): these cards now show whatever the four
     * sliders above are currently set to, which is the entire point of this
     * page's redesign. The old compact=true behavior showed a fixed
     * smallest-possible form regardless of any setting; that demonstrated
     * the templates existed, not that the token system worked.
     */
    private fun buildPreview() {
        previewContainer.removeAllViews()

        addPreviewIntro(
            "Demo instances of each catalog template, built from the same closed " +
            "construction API real settings pages use, rendered at the scale the " +
            "four sliders above are currently set to."
        )

        addPreviewLabel("NavCard — (0)")
        previewContainer.addView(NavCardView.create(this, title = "demo title"))
        addPreviewDivider()

        addPreviewLabel("NavCard — (0,1)")
        previewContainer.addView(
            NavCardView.create(this, title = "demo title", subtitle = "demo subtitle text")
        )
        addPreviewDivider()

        addPreviewLabel("ToggleCard — (0,1)")
        previewContainer.addView(
            ToggleCardView.create(this, title = "demo toggle", description = "demo description text")
        )
        addPreviewDivider()

        addPreviewLabel("ValueCard — (0,1,2,3)")
        previewContainer.addView(
            ValueCardView.create(
                this,
                label = "demo label",
                value = "42",
                description = "demo description text",
                slider = ValueCardView.SliderConfig(
                    valueFrom = 0f, valueTo = 100f, stepSize = 1f, value = 42f,
                    captionStart = "0", captionEnd = "100"
                ),
            )
        )
        addPreviewDivider()

        addPreviewLabel("DropdownCard — (0,1)")
        previewContainer.addView(
            DropdownCardView.create(this, title = "demo label", options = listOf("demo option"))
        )
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

    private fun addDivider() {
        val v = View(this, null, 0, R.style.App_Divider_Section)
        v.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        demoContainer.addView(v)
    }

    private fun addPreviewIntro(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.app_text_size_sm))
        tv.setTextColor(ContextCompat.getColor(this, R.color.app_text_body))
        tv.setPadding(
            resources.getDimensionPixelSize(R.dimen.app_spacing_md), 0,
            resources.getDimensionPixelSize(R.dimen.app_spacing_md),
            resources.getDimensionPixelSize(R.dimen.app_spacing_md)
        )
        previewContainer.addView(tv)
    }

    private fun addPreviewLabel(text: String) {
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
        previewContainer.addView(tv)
    }

    private fun addPreviewDivider() {
        val v = View(this, null, 0, R.style.App_Divider_Section)
        v.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        previewContainer.addView(v)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
