package com.eevdf.capabilities.designsystem.entities

/**
 * Typed data for one DropdownCard — see TEMPLATE_CATALOG.md and
 * `design-system/renderers/render-dropdown-card.kt`.
 */
data class DropdownCardEntity(
    val title: String,
    val options: List<String>,
    val selectedOption: String? = null,
    val onOptionSelected: ((String) -> Unit)? = null,
    val helperActionText: String? = null,
    val onHelperAction: (() -> Unit)? = null,
    val compact: Boolean = false,
)
