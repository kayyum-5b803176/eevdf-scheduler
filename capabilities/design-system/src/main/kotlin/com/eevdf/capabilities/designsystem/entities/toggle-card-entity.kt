package com.eevdf.capabilities.designsystem.entities

/**
 * Typed data for one ToggleCard — see TEMPLATE_CATALOG.md and
 * `design-system/renderers/render-toggle-card.kt`.
 */
data class ToggleCardEntity(
    val title: String,
    val description: String? = null,
    val checked: Boolean = false,
    val compact: Boolean = false,
    val onCheckedChange: ((Boolean) -> Unit)? = null,
)
