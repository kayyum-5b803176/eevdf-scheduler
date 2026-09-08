package com.eevdf.capabilities.designsystem.renderers

import android.content.Context
import com.eevdf.capabilities.designsystem.entities.ToggleCardEntity
import com.eevdf.capabilities.designsystem.output.ToggleCardView

/**
 * The ONLY place a [ToggleCardView] is ever constructed. Every other
 * capability builds a [ToggleCardEntity] and calls this instead.
 */
fun renderToggleCard(context: Context, entity: ToggleCardEntity): ToggleCardView =
    ToggleCardView.create(
        context = context,
        title = entity.title,
        description = entity.description,
        checked = entity.checked,
        compact = entity.compact,
        onCheckedChange = entity.onCheckedChange,
    )
