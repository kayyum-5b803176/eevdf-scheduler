package com.eevdf.capabilities.designsystem.entities

/**
 * Typed data for one ValueCard — see TEMPLATE_CATALOG.md and
 * `design-system/renderers/render-value-card.kt`.
 *
 * [SliderSpec] is deliberately its own type here, not a reuse of
 * `ValueCardView.SliderConfig` from `output/` — full separation means
 * nothing in `entities/` ever references a class from `output/`, so a
 * capability building this entity has zero import of, or exposure to,
 * anything View-shaped. The renderer is what translates one into the
 * other. Same bundling rule as the View class's own doc: a caption
 * without a slider is not expressible, because both live inside one
 * nullable field.
 */
data class ValueCardEntity(
    val label: String,
    val value: String,
    val description: String? = null,
    val slider: SliderSpec? = null,
    val compact: Boolean = false,
) {
    data class SliderSpec(
        val valueFrom: Float,
        val valueTo: Float,
        val stepSize: Float,
        val value: Float,
        val captionStart: String,
        val captionEnd: String,
        val onValueChange: ((Float) -> Unit)? = null,
    )
}
