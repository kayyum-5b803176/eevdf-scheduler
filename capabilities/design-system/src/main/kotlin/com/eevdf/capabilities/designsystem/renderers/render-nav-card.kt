package com.eevdf.capabilities.designsystem.renderers

import android.content.Context
import com.eevdf.capabilities.designsystem.entities.NavCardEntity
import com.eevdf.capabilities.designsystem.output.NavCardView

/**
 * The ONLY place a [NavCardView] is ever constructed. Every other
 * capability builds a [NavCardEntity] and calls this instead of importing
 * `design-system.output.NavCardView` directly — see TEMPLATE_CATALOG.md
 * and the UI redesign prompt's rule 2/3.
 */
fun renderNavCard(context: Context, entity: NavCardEntity): NavCardView =
    NavCardView.create(
        context = context,
        title = entity.title,
        subtitle = entity.subtitle,
        navigable = entity.navigable,
        compact = entity.compact,
        onNavigate = entity.onNavigate,
    )
