package com.eevdf.capabilities.settingsscreens

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.eevdf.capabilities.navigationroutes.AppRoutes
import com.eevdf.capabilities.settingsscreens.R
import com.eevdf.capabilities.designsystem.output.DesignTokens
import com.eevdf.capabilities.designsystem.entities.DropdownCardEntity
import com.eevdf.capabilities.designsystem.entities.NavCardEntity
import com.eevdf.capabilities.designsystem.entities.ToggleCardEntity
import com.eevdf.capabilities.designsystem.entities.ValueCardEntity
import com.eevdf.capabilities.designsystem.entities.SkeletonCardEntity
import com.eevdf.capabilities.designsystem.entities.SkeletonFullInput
import com.eevdf.capabilities.designsystem.entities.SkeletonIcon
import com.eevdf.capabilities.designsystem.entities.SkeletonSmallInput
import com.eevdf.capabilities.designsystem.output.SkeletonCardView
import com.eevdf.capabilities.designsystem.output.LayoutTokenPrefs
import com.eevdf.capabilities.designsystem.output.ModelDiagramView
import com.eevdf.capabilities.designsystem.output.ValueCardView
import com.eevdf.capabilities.designsystem.renderers.renderDropdownCard
import com.eevdf.capabilities.designsystem.renderers.renderNavCard
import com.eevdf.capabilities.designsystem.renderers.renderSkeletonCard
import com.eevdf.capabilities.designsystem.renderers.renderToggleCard
import com.eevdf.capabilities.designsystem.renderers.renderValueCard
import com.google.android.material.tabs.TabLayout

/**
 * Layout demo catalog, opened from Display -> render -> "Layout" card.
 *
 * Three tabs: "template" (default) — demo instances of every catalog
 * template, built as typed entities handed to design-system's renderers
 * (`renderNavCard`/`renderToggleCard`/`renderValueCard`/`renderDropdownCard`
 * — the same functions any real settings screen calls to build its rows,
 * per the typed-entity + centralized-renderer redesign); "scale" — the
 * four live token-scale sliders, moved to their own tab so template
 * previews and scale controls don't share one scrolling section; and
 * "model", a single global box-model diagram read from the same live
 * tokens. There is no hand-authored demo card XML anywhere in this
 * module; what renders in "template" is not an approximation of
 * production output, it is production output. See TEMPLATE_CATALOG.md
 * for the piece-sequence rules each renderer's underlying View class
 * enforces structurally.
 *
 * PERSISTENT, NOT SANDBOXED: the four sliders on the "scale" tab write to the
 * real [LayoutTokenPrefs] — the same preferences [com.eevdf.capabilities.designsystem.output.CardDensity]
 * and the main task list read everywhere else — and those changes stick
 * after leaving this page, the same as any other real settings control.
 * Earlier versions of this page reverted every change on exit ("sandboxed");
 * that behavior is gone — a value the user sets here is a value they set,
 * remembered the same way any other preference in this app is remembered.
 * [initialTokens] exists only to seed the four sliders' starting positions
 * when the page opens, not to restore anything afterward.
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

    /** Seeds the six sliders' starting positions on open — not restored on exit, see class doc. */
    private data class InitialTokens(
        val padding: Int, val margin: Int, val text: Int, val corner: Int,
        val rowGap: Int, val columnGap: Int,
    )
    private lateinit var initialTokens: InitialTokens

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_layout_demo)

        initialTokens = InitialTokens(
            padding = LayoutTokenPrefs.getPaddingScale(this),
            margin = LayoutTokenPrefs.getMarginScale(this),
            text = LayoutTokenPrefs.getTextScale(this),
            corner = LayoutTokenPrefs.getCornerRadiusScale(this),
            rowGap = LayoutTokenPrefs.getRowGapScale(this),
            columnGap = LayoutTokenPrefs.getColumnGapScale(this),
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

    /**
     * The four live scale controls, on their own tab. Built with
     * [ValueCardView] itself — the template system testing itself — each
     * backed by a real preference setter, each triggering a full template-
     * demo rebuild on change so the effect is immediately visible on the
     * "template" tab.
     */
    /** The four slider cards, kept alive rather than rebuilt — see
     * [ValueCardView.refreshDensity]'s doc comment for why they need
     * explicit refreshing that the demo cards below don't. */
    private val scaleSliderViews = mutableListOf<ValueCardView>()

    private fun buildScaleControls() {
        addIntro(
            scaleContainer,
            "These write to the same preferences every real screen using " +
            "these components reads — changes here are real and persist, " +
            "the same as any other setting."
        )

        scaleSliderViews += scaleSlider(
            label = "Padding scale",
            initial = initialTokens.padding,
            onChange = { LayoutTokenPrefs.setPaddingScale(this, it) },
        )
        scaleSliderViews += scaleSlider(
            label = "Margin scale",
            initial = initialTokens.margin,
            onChange = { LayoutTokenPrefs.setMarginScale(this, it) },
        )
        scaleSliderViews += scaleSlider(
            label = "Corner radius scale",
            initial = initialTokens.corner,
            onChange = { LayoutTokenPrefs.setCornerRadiusScale(this, it) },
        )
        scaleSliderViews += scaleSlider(
            label = "Text scale",
            initial = initialTokens.text,
            onChange = { LayoutTokenPrefs.setTextScale(this, it) },
        )
        scaleSliderViews += scaleSlider(
            label = "Row gap scale",
            initial = initialTokens.rowGap,
            onChange = { LayoutTokenPrefs.setRowGapScale(this, it) },
        )
        scaleSliderViews += scaleSlider(
            label = "Column gap scale",
            initial = initialTokens.columnGap,
            onChange = { LayoutTokenPrefs.setColumnGapScale(this, it) },
        )
        scaleSliderViews.forEach { scaleContainer.addView(it) }
    }

    /**
     * Re-applies live tokens to the four slider cards themselves. Found
     * missing while checking the "does everything refresh live" requirement
     * end to end: `buildTemplateDemo()` below fully reconstructs its cards
     * on every change, so they naturally pick up fresh tokens at
     * construction — these four are built once in [buildScaleControls] and
     * kept alive for the whole time this page is open, so nothing was ever
     * telling them to re-read the tokens they'd just changed. Each card's
     * own [ValueCardView.refreshDensity] only touches its padding/margin/
     * corner radius, never the `Slider` widget inside it, so this is safe
     * to call even for the card whose own slider is mid-drag.
     */
    private fun refreshScaleSliderDensity() {
        scaleSliderViews.forEach { it.refreshDensity() }
    }

    private fun scaleSlider(label: String, initial: Int, onChange: (Int) -> Unit): ValueCardView =
        renderValueCard(
            this,
            ValueCardEntity(
                label = label,
                value = initial.toString(),
                slider = ValueCardEntity.SliderSpec(
                    valueFrom = 1f, valueTo = DesignTokens.SCALE_POINTS.toFloat(), stepSize = 1f, value = initial.toFloat(),
                    captionStart = "1 (smallest)", captionEnd = "${DesignTokens.SCALE_POINTS} (largest)",
                ),
            ),
        ).apply {
            slider = slider?.copy(
                onValueChange = { newValue ->
                    val scale = newValue.toInt()
                    onChange(scale)
                    this.value = scale.toString()
                    applyPageEdgeMargins()
                    refreshScaleSliderDensity()
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

        addLabel(demoContainer, "Jump to a real screen")
        demoContainer.addView(renderNavCard(this, NavCardEntity(
            title = "Main task list",
            onNavigate = { startActivity(AppRoutes.main(this)) },
        )))
        demoContainer.addView(renderNavCard(this, NavCardEntity(
            title = "Add / edit task",
            onNavigate = { startActivity(AppRoutes.addTask(this)) },
        )))
        demoContainer.addView(renderNavCard(this, NavCardEntity(
            title = "Statistics",
            onNavigate = { startActivity(AppRoutes.stats(this)) },
        )))
        addDivider(demoContainer)

        addLabel(demoContainer, "NavCard — (0)")
        demoContainer.addView(renderNavCard(this, NavCardEntity(title = "demo title")))
        addDivider(demoContainer)

        addLabel(demoContainer, "NavCard — (0,1)")
        demoContainer.addView(
            renderNavCard(this, NavCardEntity(title = "demo title", subtitle = "demo subtitle text"))
        )
        addDivider(demoContainer)

        addLabel(demoContainer, "ToggleCard — (0,1)")
        demoContainer.addView(
            renderToggleCard(this, ToggleCardEntity(title = "demo toggle", description = "demo description text"))
        )
        addDivider(demoContainer)

        addLabel(demoContainer, "ValueCard — (0,1,2,3)")
        demoContainer.addView(
            renderValueCard(
                this,
                ValueCardEntity(
                    label = "demo label",
                    value = "42",
                    description = "demo description text",
                    slider = ValueCardEntity.SliderSpec(
                        valueFrom = 0f, valueTo = 100f, stepSize = 1f, value = 42f,
                        captionStart = "0", captionEnd = "100"
                    ),
                ),
            )
        )
        addDivider(demoContainer)

        addLabel(demoContainer, "DropdownCard — (0,1)")
        demoContainer.addView(
            renderDropdownCard(this, DropdownCardEntity(title = "demo label", options = listOf("demo option")))
        )
        addDivider(demoContainer)

        // ── EXPERIMENTAL: proposed unified 5-slot skeleton ─────────────────────
        // Title, subtitle, action, metric, buttons — tested here, in the same
        // tab and at the same live scale as the 4 real templates above, so
        // they can be compared directly. Deliberately does NOT include a
        // task-card comparison: the real task card is staying exactly as it
        // is, not a candidate for this shape.
        addIntro(
            demoContainer,
            "Below: the proposed unified skeleton (title, subtitle, action, " +
            "metric, buttons), same scale as everything above it. Each mirrors " +
            "a real row already migrated elsewhere in the app."
        )

        addLabel(demoContainer, "Skeleton — toggle-shaped (cf. Lock Screen Overlay)")
        lateinit var toggleCard: SkeletonCardView
        fun toggleInput(checked: Boolean): SkeletonSmallInput.Toggle = SkeletonSmallInput.Toggle(
            checked = checked,
            onChange = { newChecked ->
                toggleCard.metric = if (newChecked) "Enabled" else "Disabled"
                // Rebuilds itself with the flipped state, same callback —
                // smallInputs' setter fully re-renders the row, so the new
                // checked value is what the real switch actually shows.
                toggleCard.smallInputs = listOf(toggleInput(newChecked))
            },
        )
        toggleCard = renderSkeletonCard(this, SkeletonCardEntity(
            title = "Lock Screen Overlay",
            subtitle = "When on, a timer expiry shows the full-screen alarm overlay while the device is locked.",
            metric = "Enabled",
            smallInputs = listOf(toggleInput(true)),
        ))
        demoContainer.addView(toggleCard)
        addDivider(demoContainer)

        addLabel(demoContainer, "Skeleton — nav-shaped (cf. Exclude App)")
        val excludeAppOptions = listOf("Chrome", "Messages", "Camera", "Maps")
        val excludeAppSelected = mutableSetOf<String>()
        lateinit var excludeAppCard: SkeletonCardView
        excludeAppCard = renderSkeletonCard(this, SkeletonCardEntity(
            title = "Exclude App",
            subtitle = "Tap to choose which apps suppress the banner.",
            metric = "No apps selected",
            // HAMBURGER, not NAV_ARROW: this opens a dialog IN PLACE, it
            // never navigates to a different page — the arrow icon is
            // reserved specifically for cards that do.
            smallInputs = listOf(SkeletonSmallInput.IconButton(icon = SkeletonIcon.HAMBURGER, onClick = {
                val checks = excludeAppOptions.map { it in excludeAppSelected }.toBooleanArray()
                AlertDialog.Builder(this)
                    .setTitle("Hide banner on these apps")
                    .setMultiChoiceItems(excludeAppOptions.toTypedArray(), checks) { _, which, checked ->
                        if (checked) excludeAppSelected.add(excludeAppOptions[which])
                        else excludeAppSelected.remove(excludeAppOptions[which])
                    }
                    .setPositiveButton("Save") { _, _ ->
                        excludeAppCard.metric = if (excludeAppSelected.isEmpty()) "No apps selected"
                            else excludeAppSelected.joinToString(", ")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })),
        ))
        demoContainer.addView(excludeAppCard)
        addDivider(demoContainer)

        addLabel(demoContainer, "Skeleton — value-shaped (cf. Default Volume)")
        lateinit var valueCard: SkeletonCardView
        valueCard = renderSkeletonCard(this, SkeletonCardEntity(
            title = "Default Volume",
            metric = "80%",
            fullInput = SkeletonFullInput.Slider(
                valueFrom = 0f, valueTo = 100f, stepSize = 5f, value = 80f,
                onValueChange = { v -> valueCard.metric = "${v.toInt()}%" },
            ),
        ))
        demoContainer.addView(valueCard)
        addDivider(demoContainer)

        addLabel(demoContainer, "Skeleton — dropdown-shaped (cf. Vibration Pattern)")
        val vibPatternOptions = listOf("Single Pulse", "Double Tap", "Triple Tap")
        var currentVibPattern = "Single Pulse"
        val dropdownCard = renderSkeletonCard(this, SkeletonCardEntity(
            title = "Vibration Pattern",
            // No metric row — the dropdown itself already shows the
            // selected value, a separate readout of the same string is
            // redundant.
            fullInput = SkeletonFullInput.Dropdown(
                options = vibPatternOptions, selected = currentVibPattern,
                onSelect = { selected -> currentVibPattern = selected },
            ),
            smallInputs = listOf(SkeletonSmallInput.IconButton(icon = SkeletonIcon.PREVIEW, onClick = {
                Toast.makeText(this, "Preview: $currentVibPattern", Toast.LENGTH_SHORT).show()
            })),
        ))
        demoContainer.addView(dropdownCard)
        addDivider(demoContainer)

        addLabel(demoContainer, "Skeleton — button-shaped (cf. Export Database)")
        demoContainer.addView(
            renderSkeletonCard(this, SkeletonCardEntity(
                title = "Export Database",
                subtitle = "Save a complete copy of the database to a .db file.",
                smallInputs = listOf(SkeletonSmallInput.IconButton(icon = SkeletonIcon.EXPORT, onClick = {
                    Toast.makeText(this, "Export Database tapped", Toast.LENGTH_SHORT).show()
                })),
            ))
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
