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
import com.eevdf.feature.ui.DesignTokens
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
 * Three tabs: "template" (default) — demo instances of every catalog
 * template, built through the closed template construction API in
 * com.eevdf.feature.ui, the same classes any real settings screen uses to
 * build its rows; "scale" — the four live token-scale sliders, moved to
 * their own tab so template previews and scale controls don't share one
 * scrolling section; and "model", a single global box-model diagram read
 * from the same live tokens. There is no hand-authored demo card XML
 * anywhere in this module; what renders in "template" is not an
 * approximation of production output, it is production output. See
 * TEMPLATE_CATALOG.md for the piece-sequence rules each class enforces
 * structurally.
 *
 * SANDBOXED, NOT PERMANENT (Phase 4 of the UI-unification work): the four
 * sliders on the "scale" tab write to the real [LayoutTokenPrefs]/
 * [DisplayPrefs] — the same preferences [com.eevdf.feature.ui.CardDensity]
 * reads everywhere else — so this page genuinely proves the round-trip
 * works, not a fake preview. [savedTokens] captures whatever was actually
 * set before this Activity touched anything, and [onDestroy] restores it,
 * so leaving this page undoes every change made while visiting. Real
 * settings pages that expose these controls for real (a later phase) will
 * not have this restore step.
 *
 * This is the first screen in the catalog whose own previously-blank
 * TabLayout extension point gets wired up, rather than shipping unused.
 */
class LayoutDemoActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var demoContainer: LinearLayout
    private lateinit var scaleContainer: LinearLayout
    private lateinit var modelContainer: LinearLayout
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
            padding = LayoutTokenPrefs.getPaddingScale(this),
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
        scaleContainer = findViewById(R.id.scaleContainer)
        modelContainer = findViewById(R.id.modelContainer)
        modelDiagram = findViewById(R.id.modelDiagramView)

        tabLayout.addTab(tabLayout.newTab().setText("template"))
        tabLayout.addTab(tabLayout.newTab().setText("scale"))
        tabLayout.addTab(tabLayout.newTab().setText("model"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                demoContainer.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                scaleContainer.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
                modelContainer.visibility = if (tab.position == 2) View.VISIBLE else View.GONE
                // The model diagram is a single static instance, not rebuilt
                // like the template previews — refresh it explicitly so
                // switching to this tab after adjusting a slider on the
                // "scale" tab never shows stale numbers.
                if (tab.position == 2) modelDiagram.refresh()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        buildScaleControls()
        buildTemplateDemo()
        applyPageEdgeMargins()
    }

    /**
     * This page's own screen-edge padding — set programmatically here,
     * NOT via the shared `App.PageContent` XML style every other page also
     * uses, so this stays scoped to the Layout demo page only. Half of
     * [DesignTokens.outerMarginDp], on all three tab containers, matching
     * exactly what [CardDensity.applyOuterGap] now applies to each card's
     * own margin — one half plus one half sums to the full target gap,
     * uniformly, at every edge and between every card. See
     * ARCHITECTURE.md for the full reasoning.
     *
     * Re-applied on every scale-slider change (see [scaleSlider]), not just
     * once in onCreate — the margin scale is one of the four live sliders,
     * so this needs to track it the same way the template previews do.
     */
    private fun applyPageEdgeMargins() {
        val halfMarginPx = (
            LayoutTokenPrefs.current(this).outerMarginDp / 2f *
                resources.displayMetrics.density + 0.5f
        ).toInt()
        for (container in listOf(demoContainer, scaleContainer, modelContainer)) {
            container.setPadding(halfMarginPx, halfMarginPx, halfMarginPx, halfMarginPx)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Undo everything this screen changed — see class doc's "SANDBOXED,
        // NOT PERMANENT" note. Always runs, even if the Activity is being
        // destroyed by a configuration change, not just Back — acceptable
        // here since this is a debug/demo screen, not a real settings page.
        LayoutTokenPrefs.setPaddingScale(this, savedTokens.padding)
        LayoutTokenPrefs.setMarginScale(this, savedTokens.margin)
        LayoutTokenPrefs.setTextScale(this, savedTokens.text)
        LayoutTokenPrefs.setCornerRadiusScale(this, savedTokens.corner)
    }

    /**
     * The four live scale controls, on their own tab. Built with
     * [ValueCardView] itself — the template system testing itself — each
     * backed by a real preference setter, each triggering a full template-
     * demo rebuild on change so the effect is immediately visible on the
     * "template" tab.
     */
    private fun buildScaleControls() {
        addIntro(
            scaleContainer,
            "Changes here are temporary — they write to the same preferences " +
            "every real screen using these components would read, but are " +
            "undone automatically when you leave this page."
        )

        scaleContainer.addView(scaleSlider(
            label = "Padding scale",
            initial = savedTokens.padding,
            onChange = { LayoutTokenPrefs.setPaddingScale(this, it) },
        ))
        scaleContainer.addView(scaleSlider(
            label = "Margin scale",
            initial = savedTokens.margin,
            onChange = { LayoutTokenPrefs.setMarginScale(this, it) },
        ))
        scaleContainer.addView(scaleSlider(
            label = "Corner radius scale",
            initial = savedTokens.corner,
            onChange = { LayoutTokenPrefs.setCornerRadiusScale(this, it) },
        ))
        scaleContainer.addView(scaleSlider(
            label = "Text scale",
            initial = savedTokens.text,
            onChange = { LayoutTokenPrefs.setTextScale(this, it) },
        ))
    }

    private fun scaleSlider(label: String, initial: Int, onChange: (Int) -> Unit): ValueCardView =
        ValueCardView.create(
            this,
            label = label,
            value = initial.toString(),
            slider = ValueCardView.SliderConfig(
                valueFrom = 1f, valueTo = DesignTokens.SCALE_POINTS.toFloat(), stepSize = 1f, value = initial.toFloat(),
                captionStart = "1 (smallest)", captionEnd = "${DesignTokens.SCALE_POINTS} (largest)",
            ),
        ).apply {
            slider = slider?.copy(
                onValueChange = { newValue ->
                    val scale = newValue.toInt()
                    onChange(scale)
                    this.value = scale.toString()
                    applyPageEdgeMargins()
                    buildTemplateDemo()
                    if (modelContainer.visibility == View.VISIBLE) modelDiagram.refresh()
                }
            )
        }

    /**
     * Every demo template instance, rebuilt from scratch on every slider
     * change on the "scale" tab. Not the most efficient approach, but this
     * is a debug catalog page showing five small views, not a real
     * production list — correctness and simplicity win over avoiding a
     * rebuild here.
     *
     * compact = false on every instance (changed from true — see
     * ARCHITECTURE.md Phase 4): these cards show whatever the four sliders
     * on the "scale" tab are currently set to, which is the entire point of
     * this page's redesign. The old compact=true behavior showed a fixed
     * smallest-possible form regardless of any setting; that demonstrated
     * the templates existed, not that the token system worked.
     */
    private fun buildTemplateDemo() {
        demoContainer.removeAllViews()

        addIntro(
            demoContainer,
            "Demo instances of each catalog template, built from the same closed " +
            "construction API real settings pages use, rendered at whatever scale " +
            "the \"scale\" tab is currently set to."
        )

        addLabel(demoContainer, "NavCard — (0)")
        demoContainer.addView(NavCardView.create(this, title = "demo title"))
        addDivider(demoContainer)

        addLabel(demoContainer, "NavCard — (0,1)")
        demoContainer.addView(
            NavCardView.create(this, title = "demo title", subtitle = "demo subtitle text")
        )
        addDivider(demoContainer)

        addLabel(demoContainer, "ToggleCard — (0,1)")
        demoContainer.addView(
            ToggleCardView.create(this, title = "demo toggle", description = "demo description text")
        )
        addDivider(demoContainer)

        addLabel(demoContainer, "ValueCard — (0,1,2,3)")
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
            )
        )
        addDivider(demoContainer)

        addLabel(demoContainer, "DropdownCard — (0,1)")
        demoContainer.addView(
            DropdownCardView.create(this, title = "demo label", options = listOf("demo option"))
        )
    }

    private fun addIntro(container: LinearLayout, text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.app_text_size_sm))
        tv.setTextColor(ContextCompat.getColor(this, R.color.app_text_body))
        tv.setPadding(
            resources.getDimensionPixelSize(R.dimen.app_spacing_md), 0,
            resources.getDimensionPixelSize(R.dimen.app_spacing_md),
            resources.getDimensionPixelSize(R.dimen.app_spacing_md)
        )
        container.addView(tv)
    }

    private fun addLabel(container: LinearLayout, text: String) {
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
        container.addView(tv)
    }

    private fun addDivider(container: LinearLayout) {
        val v = View(this, null, 0, R.style.App_Divider_Section)
        v.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        container.addView(v)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
