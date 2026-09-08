package com.eevdf.capabilities.designsystem.entities

/**
 * Typed data for one NavCard — see TEMPLATE_CATALOG.md and
 * `design-system/renderers/render-nav-card.kt`. No Android View types in
 * this file, on purpose: any capability that wants a NavCard on screen
 * builds one of these and hands it to the renderer — it never constructs
 * `NavCardView` (or anything else in `design-system/output/`) itself.
 */
data class NavCardEntity(
    val title: String,
    val subtitle: String? = null,
    val navigable: Boolean = true,
    val compact: Boolean = false,
    val onNavigate: (() -> Unit)? = null,
)
