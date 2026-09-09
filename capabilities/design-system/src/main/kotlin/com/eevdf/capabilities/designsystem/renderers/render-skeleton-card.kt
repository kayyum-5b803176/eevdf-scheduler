package com.eevdf.capabilities.designsystem.renderers

import android.content.Context
import com.eevdf.capabilities.designsystem.entities.SkeletonCardEntity
import com.eevdf.capabilities.designsystem.output.SkeletonCardView

/**
 * EXPERIMENTAL — the only place a [SkeletonCardView] is ever constructed.
 * Demo-only right now; see [SkeletonCardView]'s KDoc.
 */
fun renderSkeletonCard(context: Context, entity: SkeletonCardEntity): SkeletonCardView =
    SkeletonCardView.create(
        context = context,
        title = entity.title,
        subtitle = entity.subtitle,
        metric = entity.metric,
        fullInput = entity.fullInput,
        smallInputs = entity.smallInputs,
    )
