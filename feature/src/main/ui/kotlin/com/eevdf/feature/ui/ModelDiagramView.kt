package com.eevdf.feature.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.eevdf.app.R

/**
 * Global box-model debug diagram. Reached from Layout demo -> "model" tab.
 *
 * Draws the box-model metrics shared across every closed template class —
 * screen-to-card gap, the REAL rendered card-to-card gap (two stacked
 * margins, not one), corner radius, and the inner content padding shared
 * by ToggleCard/ValueCard/DropdownCard's App.SettingsCard.Body. Every
 * value is read from the real dimen resources at draw time via
 * [Context.resources] — this diagram cannot silently go stale relative
 * to dimens.xml, because it never hardcodes a number, only a resource id.
 *
 * NavCard's own row padding (app_row_padding_horizontal/vertical, 16dp/
 * 14dp) differs from the 20dp shown here and is deliberately NOT
 * represented — this diagram is scoped to the metrics genuinely shared
 * across all four templates, per an explicit decision that showing both
 * real numbers would contradict the "one global diagram" premise.
 */
class ModelDiagramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Visual-only scale factor: 10dp/12dp/20dp render too small to read
    // legibly at 1:1. This multiplies pixel drawing size only — every
    // label still states the real, unscaled dp value read from the
    // resource.
    private val visualScale = 5f

    private val screenGapDp: Float
    private val cardGapDp: Float // single margin value, real per-card
    private val cornerRadiusDp: Float
    private val innerPaddingDp: Float

    private val density = context.resources.displayMetrics.density

    private val boxPaintScreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val boxPaintCard = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val boxPaintPadding = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val fillContent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    init {
        val res = context.resources
        screenGapDp = pxToDp(res.getDimension(R.dimen.app_card_gap))
        cardGapDp = pxToDp(res.getDimension(R.dimen.app_card_gap))
        cornerRadiusDp = pxToDp(res.getDimension(R.dimen.app_card_corner_radius))
        innerPaddingDp = pxToDp(res.getDimension(R.dimen.app_card_padding_lg))

        boxPaintScreen.color = ContextCompat.getColor(context, R.color.app_text_hint)
        boxPaintCard.color = ContextCompat.getColor(context, R.color.app_text_label)
        boxPaintPadding.color = ContextCompat.getColor(context, R.color.app_grey_dk)
        fillContent.color = ContextCompat.getColor(context, R.color.app_azure_lt)
        labelPaint.color = ContextCompat.getColor(context, R.color.app_text_body)
        valuePaint.color = ContextCompat.getColor(context, R.color.app_text_title)

        val labelSizePx = res.getDimension(R.dimen.app_text_size_xs)
        labelPaint.textSize = labelSizePx
        valuePaint.textSize = labelSizePx
    }

    private fun pxToDp(px: Float): Float = px / density
    private fun dpToVisualPx(dp: Float): Float = dp * visualScale * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Height budget mirrors onDraw's actual layout: the screen box
        // occupies the top 62% of the view, plus room below it for the
        // card-to-card gap illustration (label + two stacked boxes +
        // the real gap between them). Deriving this from the same
        // density-scaled dp values onDraw uses (rather than an
        // independently-guessed constant) keeps the two in sync.
        val gapIllustrationHeight = (30 + 12 + 30 + 30).toFloat() * density + dpToVisualPx(cardGapDp)
        // screenRect.bottom = height * 0.62, so height = screenRect.bottom / 0.62;
        // screenRect.bottom must be tall enough to contain the card+padding+content
        // boxes, which together need at least screenGapDp*2 + innerPaddingDp*2
        // visual-scaled dp of vertical room, plus label line heights.
        val screenBoxContentHeight = dpToVisualPx(screenGapDp * 2 + innerPaddingDp * 2) + 80f * density
        val screenBoxTotal = screenBoxContentHeight / 0.62f
        val desiredHeight = (screenBoxTotal + gapIllustrationHeight).toInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, desiredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val screenGapPx = dpToVisualPx(screenGapDp)
        val innerPaddingPx = dpToVisualPx(innerPaddingDp)
        val cornerPx = dpToVisualPx(cornerRadiusDp)

        // All fixed spacing/label offsets below are expressed in real
        // device pixels via `density`, matching the box sizes above
        // (which scale the same way through dpToVisualPx) — mixing
        // raw hardcoded pixel offsets with density-scaled box sizes
        // would misalign the diagram on non-baseline-density devices.
        val edgeInset = 4f * density
        val labelGap1 = 24f * density
        val labelGap2 = 44f * density
        val cardTopExtra = 40f * density
        val paddingLabelGap = 24f * density
        val paddingLabelOffset = 6f * density
        val contentLabelOffset = 30f * density
        val gapSectionTop = 30f * density
        val gapIllustrationTop = 12f * density
        val illustrationBoxHeight = 30f * density

        // Outer "screen" box (dashed)
        val screenRect = RectF(edgeInset, edgeInset, width - edgeInset, height * 0.62f)
        canvas.drawRoundRect(screenRect, edgeInset, edgeInset, boxPaintScreen)
        canvas.drawText("screen", screenRect.left + 12f * density, screenRect.top + labelGap1, labelPaint)
        canvas.drawText(
            "app_card_gap = ${screenGapDp.toInt()}dp",
            screenRect.left + 12f * density, screenRect.top + labelGap2, valuePaint
        )

        // Card box, inset by screenGapPx, drawn with the real corner radius
        val cardRect = RectF(
            screenRect.left + screenGapPx, screenRect.top + screenGapPx + cardTopExtra,
            screenRect.right - screenGapPx, screenRect.bottom - screenGapPx
        )
        canvas.drawRoundRect(cardRect, cornerPx, cornerPx, boxPaintCard)
        canvas.drawText(
            "card · corner radius ${cornerRadiusDp.toInt()}dp (app_card_corner_radius)",
            cardRect.left + 12f * density, cardRect.top + labelGap1, labelPaint
        )

        // Inner padding box, inset by innerPaddingPx from the card
        val paddingRect = RectF(
            cardRect.left + innerPaddingPx, cardRect.top + innerPaddingPx + paddingLabelGap,
            cardRect.right - innerPaddingPx, cardRect.bottom - innerPaddingPx
        )
        canvas.drawRoundRect(paddingRect, edgeInset, edgeInset, boxPaintPadding)
        canvas.drawText(
            "inner padding ${innerPaddingDp.toInt()}dp (app_card_padding_lg — shared by ToggleCard/ValueCard/DropdownCard)",
            paddingRect.left, paddingRect.top - paddingLabelOffset, labelPaint
        )

        // Content fill
        canvas.drawRoundRect(paddingRect, edgeInset, edgeInset, fillContent)
        canvas.drawRoundRect(paddingRect, edgeInset, edgeInset, boxPaintPadding)
        canvas.drawText("content", paddingRect.centerX() - contentLabelOffset, paddingRect.centerY(), valuePaint)

        // Card-to-card gap illustration: two stacked margins, real value.
        val gapY = screenRect.bottom + gapSectionTop
        val gapPx = dpToVisualPx(cardGapDp)
        canvas.drawText(
            "card -> card gap: ${cardGapDp.toInt()}dp + ${cardGapDp.toInt()}dp = ${(cardGapDp * 2).toInt()}dp real " +
                "(two stacked app_card_gap margins — Android does not collapse margins)",
            8f * density, gapY, labelPaint
        )
        // small two-box illustration beneath the label
        val boxTop = gapY + gapIllustrationTop
        val boxW = width * 0.4f
        val box1 = RectF(8f * density, boxTop, 8f * density + boxW, boxTop + illustrationBoxHeight)
        val box2 = RectF(8f * density, box1.bottom + gapPx, 8f * density + boxW, box1.bottom + gapPx + illustrationBoxHeight)
        canvas.drawRoundRect(box1, cornerPx, cornerPx, boxPaintCard)
        canvas.drawRoundRect(box2, cornerPx, cornerPx, boxPaintCard)
    }
}
