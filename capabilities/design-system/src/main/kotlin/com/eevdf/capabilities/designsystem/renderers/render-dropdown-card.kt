package com.eevdf.capabilities.designsystem.renderers

import android.content.Context
import com.eevdf.capabilities.designsystem.entities.DropdownCardEntity
import com.eevdf.capabilities.designsystem.output.DropdownCardView

/**
 * The ONLY place a [DropdownCardView] is ever constructed. Every other
 * capability builds a [DropdownCardEntity] and calls this instead.
 */
fun renderDropdownCard(context: Context, entity: DropdownCardEntity): DropdownCardView =
    DropdownCardView.create(
        context = context,
        title = entity.title,
        options = entity.options,
        selectedOption = entity.selectedOption,
        onOptionSelected = entity.onOptionSelected,
        helperActionText = entity.helperActionText,
        onHelperAction = entity.onHelperAction,
        compact = entity.compact,
    )
