package com.eevdf.app.feature.task.adapter

import androidx.recyclerview.widget.DiffUtil
import com.eevdf.data.task.TaskDisplayItem

/**
 * DiffUtil callback for [TaskAdapter]. Separated from the adapter so changes
 * to diff logic (e.g. adding new equality fields) don't touch the adapter file.
 */
class DiffCallback : DiffUtil.ItemCallback<TaskDisplayItem>() {
    override fun areItemsTheSame(old: TaskDisplayItem, new: TaskDisplayItem) =
        old.task.id == new.task.id
    override fun areContentsTheSame(old: TaskDisplayItem, new: TaskDisplayItem) =
        old == new
}
