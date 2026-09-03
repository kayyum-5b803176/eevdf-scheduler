package com.eevdf.feature.task.adapter

import androidx.recyclerview.widget.DiffUtil
import com.eevdf.data.task.TaskDisplayItem

/**
 * DiffUtil callback for [TaskAdapter]. Separated from the adapter so changes
 * to diff logic (e.g. adding new equality fields) don't touch the adapter file.
 */
class DiffCallback : DiffUtil.ItemCallback<TaskDisplayItem>() {
    // A real task can now appear more than once in the same flat list — once
    // at its real location, once per symlink pointing at it, once per
    // hardlink placement — all sharing the same task.id. Distinguishing by
    // symlinkId/membershipId too keeps ListAdapter from conflating two
    // genuinely different rows that happen to display the same underlying task.
    override fun areItemsTheSame(old: TaskDisplayItem, new: TaskDisplayItem) =
        old.task.id == new.task.id &&
        old.symlinkId == new.symlinkId &&
        old.membershipId == new.membershipId
    override fun areContentsTheSame(old: TaskDisplayItem, new: TaskDisplayItem) =
        old == new
}
