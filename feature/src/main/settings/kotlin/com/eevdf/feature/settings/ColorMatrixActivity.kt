package com.eevdf.feature.settings

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.eevdf.app.R

/**
 * Color matrix screen, opened from Display -> render -> "color" card.
 *
 * 13 color-name rows x 7 lumen-code columns. Every swatch's real color
 * comes from its android:background in the layout, which references the
 * actual @color/app_<family>_<stop> resource — this class never hardcodes
 * a hex value. Tapping a swatch reads that same resource's real ARGB
 * value at runtime via [ContextCompat.getColor] and shows it, alongside
 * the family name and lumen code parsed from the view's tag, in the
 * detail strip above the grid.
 */
class ColorMatrixActivity : AppCompatActivity() {

    private lateinit var tvColorDetail: TextView

    /**
     * Token name -> R.color id, for every swatch this screen shows.
     * Deliberately explicit rather than resources.getIdentifier(token, ...) —
     * that reflection-style lookup is slower, and a typo in a layout tag
     * would silently resolve to 0 at runtime instead of being visible here
     * as a token with no map entry.
     */
    private val tokenToColorRes: Map<String, Int> = mapOf(
        "app_grey_cl" to R.color.app_grey_cl, "app_grey_xl" to R.color.app_grey_xl,
        "app_grey_lt" to R.color.app_grey_lt, "app_grey_nd" to R.color.app_grey_nd,
        "app_grey_dk" to R.color.app_grey_dk, "app_grey_xd" to R.color.app_grey_xd,
        "app_grey_cd" to R.color.app_grey_cd,

        "app_red_cl" to R.color.app_red_cl, "app_red_xl" to R.color.app_red_xl,
        "app_red_lt" to R.color.app_red_lt, "app_red_nd" to R.color.app_red_nd,
        "app_red_dk" to R.color.app_red_dk, "app_red_xd" to R.color.app_red_xd,
        "app_red_cd" to R.color.app_red_cd,

        "app_orange_cl" to R.color.app_orange_cl, "app_orange_xl" to R.color.app_orange_xl,
        "app_orange_lt" to R.color.app_orange_lt, "app_orange_nd" to R.color.app_orange_nd,
        "app_orange_dk" to R.color.app_orange_dk, "app_orange_xd" to R.color.app_orange_xd,
        "app_orange_cd" to R.color.app_orange_cd,

        "app_yellow_cl" to R.color.app_yellow_cl, "app_yellow_xl" to R.color.app_yellow_xl,
        "app_yellow_lt" to R.color.app_yellow_lt, "app_yellow_nd" to R.color.app_yellow_nd,
        "app_yellow_dk" to R.color.app_yellow_dk, "app_yellow_xd" to R.color.app_yellow_xd,
        "app_yellow_cd" to R.color.app_yellow_cd,

        "app_lime_cl" to R.color.app_lime_cl, "app_lime_xl" to R.color.app_lime_xl,
        "app_lime_lt" to R.color.app_lime_lt, "app_lime_nd" to R.color.app_lime_nd,
        "app_lime_dk" to R.color.app_lime_dk, "app_lime_xd" to R.color.app_lime_xd,
        "app_lime_cd" to R.color.app_lime_cd,

        "app_green_cl" to R.color.app_green_cl, "app_green_xl" to R.color.app_green_xl,
        "app_green_lt" to R.color.app_green_lt, "app_green_nd" to R.color.app_green_nd,
        "app_green_dk" to R.color.app_green_dk, "app_green_xd" to R.color.app_green_xd,
        "app_green_cd" to R.color.app_green_cd,

        "app_mint_cl" to R.color.app_mint_cl, "app_mint_xl" to R.color.app_mint_xl,
        "app_mint_lt" to R.color.app_mint_lt, "app_mint_nd" to R.color.app_mint_nd,
        "app_mint_dk" to R.color.app_mint_dk, "app_mint_xd" to R.color.app_mint_xd,
        "app_mint_cd" to R.color.app_mint_cd,

        "app_cyan_cl" to R.color.app_cyan_cl, "app_cyan_xl" to R.color.app_cyan_xl,
        "app_cyan_lt" to R.color.app_cyan_lt, "app_cyan_nd" to R.color.app_cyan_nd,
        "app_cyan_dk" to R.color.app_cyan_dk, "app_cyan_xd" to R.color.app_cyan_xd,
        "app_cyan_cd" to R.color.app_cyan_cd,

        "app_azure_cl" to R.color.app_azure_cl, "app_azure_xl" to R.color.app_azure_xl,
        "app_azure_lt" to R.color.app_azure_lt, "app_azure_nd" to R.color.app_azure_nd,
        "app_azure_dk" to R.color.app_azure_dk, "app_azure_xd" to R.color.app_azure_xd,
        "app_azure_cd" to R.color.app_azure_cd,

        "app_blue_cl" to R.color.app_blue_cl, "app_blue_xl" to R.color.app_blue_xl,
        "app_blue_lt" to R.color.app_blue_lt, "app_blue_nd" to R.color.app_blue_nd,
        "app_blue_dk" to R.color.app_blue_dk, "app_blue_xd" to R.color.app_blue_xd,
        "app_blue_cd" to R.color.app_blue_cd,

        "app_violet_cl" to R.color.app_violet_cl, "app_violet_xl" to R.color.app_violet_xl,
        "app_violet_lt" to R.color.app_violet_lt, "app_violet_nd" to R.color.app_violet_nd,
        "app_violet_dk" to R.color.app_violet_dk, "app_violet_xd" to R.color.app_violet_xd,
        "app_violet_cd" to R.color.app_violet_cd,

        "app_magenta_cl" to R.color.app_magenta_cl, "app_magenta_xl" to R.color.app_magenta_xl,
        "app_magenta_lt" to R.color.app_magenta_lt, "app_magenta_nd" to R.color.app_magenta_nd,
        "app_magenta_dk" to R.color.app_magenta_dk, "app_magenta_xd" to R.color.app_magenta_xd,
        "app_magenta_cd" to R.color.app_magenta_cd,

        "app_rose_cl" to R.color.app_rose_cl, "app_rose_xl" to R.color.app_rose_xl,
        "app_rose_lt" to R.color.app_rose_lt, "app_rose_nd" to R.color.app_rose_nd,
        "app_rose_dk" to R.color.app_rose_dk, "app_rose_xd" to R.color.app_rose_xd,
        "app_rose_cd" to R.color.app_rose_cd
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_color_matrix)

        val toolbar = findViewById<Toolbar>(R.id.colorMatrixToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Color"

        tvColorDetail = findViewById(R.id.tvColorDetail)

        wireSwatches()
    }

    /**
     * Every swatch View carries android:tag="<family>|<lumenCode>|<tokenName>",
     * set once in the layout next to that same swatch's android:background.
     * Finding swatches by walking tagged views (rather than maintaining a
     * 91-entry id list here) means the layout stays the single source of
     * truth for which swatches exist — this class only reacts to whatever
     * it finds tagged, so adding or removing a swatch in the layout alone
     * is enough; no parallel list to keep in sync here.
     */
    private fun wireSwatches() {
        val root = findViewById<View>(android.R.id.content)
        forEachTaggedSwatch(root) { swatch ->
            swatch.setOnClickListener {
                val parts = (swatch.tag as? String)?.split("|") ?: return@setOnClickListener
                if (parts.size != 3) return@setOnClickListener
                val (family, lumen, token) = parts

                val resId = tokenToColorRes[token] ?: return@setOnClickListener
                val argb = ContextCompat.getColor(this, resId)
                val hex = String.format("#%08X", argb)

                tvColorDetail.text = "$family · $lumen · $hex · $token"
            }
        }
    }

    private fun forEachTaggedSwatch(view: View, action: (View) -> Unit) {
        if (view.tag is String) action(view)
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                forEachTaggedSwatch(view.getChildAt(i), action)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}

