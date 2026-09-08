package com.eevdf.capabilities.designsystem.renderers

import android.content.Context
import com.eevdf.capabilities.designsystem.entities.ValueCardEntity
import com.eevdf.capabilities.designsystem.output.ValueCardView

/**
 * The ONLY place a [ValueCardView] is ever constructed. Every other
 * capability builds a [ValueCardEntity] and calls this instead.
 *
 * This is also the one place [ValueCardEntity.SliderSpec] (entities/, no
 * View types) gets translated into [ValueCardView.SliderConfig] (output/,
 * the real View-facing shape) — the boundary the "full clean" split exists
 * to draw.
 */
fun renderValueCard(context: Context, entity: ValueCardEntity): ValueCardView =
    ValueCardView.create(
        context = context,
        label = entity.label,
        value = entity.value,
        description = entity.description,
        slider = entity.slider?.let {
            ValueCardView.SliderConfig(
                valueFrom = it.valueFrom,
                valueTo = it.valueTo,
                stepSize = it.stepSize,
                value = it.value,
                captionStart = it.captionStart,
                captionEnd = it.captionEnd,
                onValueChange = it.onValueChange,
            )
        },
        compact = entity.compact,
    )
