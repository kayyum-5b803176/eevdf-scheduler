package com.eevdf.capabilities.addtaskscreen

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.capabilities.taskstorage.MembershipAcl
import com.eevdf.capabilities.taskstorage.MembershipAclKeys
import com.eevdf.capabilities.taskstorage.MembershipAclRules
import com.eevdf.capabilities.taskstorage.Task
import com.eevdf.capabilities.taskstorage.TaskLink
import com.eevdf.capabilities.taskstorage.TaskMembership
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

/**
 * ACL tab for [AddTaskActivity] — hardlink placements only.
 *
 * Shown only when the screen was opened from a hardlink row (intent extra
 * `membership_id`) AND that placement's task is a group. Two tabs, "Config"
 * (the existing form, untouched) and "ACL"; switching only toggles visibility
 * of the form's scroll view and the ACL container, never moves the form's views.
 *
 * The ACL page lists the group's DIRECT children — real tasks/groups, plus
 * hardlink placements and symlinks hosted inside it — each with a "visible"
 * switch. Every switch saves immediately through [com.eevdf.capabilities.taskstorage.TaskRepository];
 * there is no Save button, and the form's Save is not involved.
 *
 * Opening the page gives every listed child an explicit row holding its current
 * state ([com.eevdf.capabilities.taskstorage.TaskRepository.ensureMembershipAclRows]),
 * so flipping "Hide new children by default" later only affects children added
 * afterwards.
 */
internal fun AddTaskActivity.setupAclSection() {
    val membershipId = intent.getStringExtra("membership_id") ?: return   // no door: tab bar stays hidden, screen unchanged
    aclTab = AclTab(this, membershipId).also { it.start() }
}

internal class AclTab(
    private val activity: AddTaskActivity,
    private val membershipId: String,
) {
    private val tabs: TabLayout        = activity.findViewById(R.id.tabAddTask)
    private val configScroll: View     = activity.findViewById(R.id.configScroll)
    private val aclContainer: View     = activity.findViewById(R.id.aclContainer)
    private val switchHideNew: SwitchMaterial = activity.findViewById(R.id.switchAclHideNew)
    private val tvTitle: TextView      = activity.findViewById(R.id.tvAclGroupTitle)
    private val tvEmpty: TextView      = activity.findViewById(R.id.tvAclEmpty)
    private val rv: RecyclerView       = activity.findViewById(R.id.rvAclChildren)

    private val repository = activity.repository
    private val adapter    = Adapter { row, visible ->
        activity.lifecycleScope.launch { repository.setMembershipChildHidden(membershipId, row.key, !visible) }
    }

    private var tasks: List<Task>               = emptyList()
    private var links: List<TaskLink>           = emptyList()
    private var memberships: List<TaskMembership> = emptyList()
    private var aclRows: List<MembershipAcl>    = emptyList()
    private var tabsShown = false

    private val onHideNewChanged = CompoundButton.OnCheckedChangeListener { _, checked ->
        val keys = currentRows().map { it.key }
        activity.lifecycleScope.launch {
            repository.ensureMembershipAclRows(membershipId, keys)
            repository.setMembershipNewChildrenHidden(membershipId, checked)
        }
    }

    fun start() {
        rv.layoutManager = LinearLayoutManager(activity)
        rv.adapter = adapter

        tabs.addTab(tabs.newTab().setText("Config"), true)
        tabs.addTab(tabs.newTab().setText("ACL"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showAcl(tab.position == 1)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // The default applies to children with no explicit row. Freeze the listed
        // ones at their CURRENT state first so only later arrivals follow the switch.
        switchHideNew.setOnCheckedChangeListener(onHideNewChanged)

        repository.activeTasks.observe(activity)        { tasks = it;       refresh() }
        repository.allTaskLinks.observe(activity)       { links = it;       refresh() }
        repository.allTaskMemberships.observe(activity) { memberships = it; refresh() }
        repository.allMembershipAcl.observe(activity)   { aclRows = it;     refresh() }
    }

    private fun showAcl(acl: Boolean) {
        configScroll.visibility  = if (acl) View.GONE else View.VISIBLE
        aclContainer.visibility  = if (acl) View.VISIBLE else View.GONE
    }

    private fun membership(): TaskMembership? = memberships.firstOrNull { it.id == membershipId }

    private fun group(): Task? = membership()?.let { m -> tasks.firstOrNull { it.id == m.taskId } }

    /** The group's direct children as ACL rows: real children first, then hosted hardlinks, then symlinks. */
    private fun currentRows(): List<Row> {
        val m = membership() ?: return emptyList()
        val g = group() ?: return emptyList()
        val byId = tasks.associateBy { it.id }
        val real = tasks.filter { it.parentId == g.id && !it.isCompleted }
            .sortedBy { it.name.lowercase() }
            .map { Row(MembershipAclKeys.real(it.id), it.name, if (it.isGroup) "cgroup" else "task", visible(m, MembershipAclKeys.real(it.id))) }
        val hard = memberships.filter { it.groupId == g.id }.mapNotNull { h ->
            val t = byId[h.taskId] ?: return@mapNotNull null
            val key = MembershipAclKeys.membership(h.id)
            Row(key, t.name, "hardlink", visible(m, key))
        }.sortedBy { it.name.lowercase() }
        val sym = links.filter { it.hostGroupId == g.id }.map { l ->
            val key = MembershipAclKeys.symlink(l.id)
            Row(key, byId[l.targetTaskId]?.name ?: "(broken link)", "symlink", visible(m, key))
        }.sortedBy { it.name.lowercase() }
        return real + hard + sym
    }

    private fun visible(m: TaskMembership, key: String) = !MembershipAclRules.isHidden(m, key, aclRows)

    private fun refresh() {
        val m = membership()
        val g = group()
        // Not a group (or the placement is gone): there is nothing to hide — keep
        // the screen exactly as it is without a tab bar.
        if (m == null || g == null || !g.isGroup) {
            tabs.visibility = View.GONE
            tabsShown = false
            showAcl(false)
            return
        }
        if (!tabsShown) { tabs.visibility = View.VISIBLE; tabsShown = true }
        tvTitle.text = "Direct children of \"${g.name}\""

        // No listener feedback while syncing the switch to stored state.
        if (switchHideNew.isChecked != m.newChildrenHidden) {
            switchHideNew.setOnCheckedChangeListener(null)
            switchHideNew.isChecked = m.newChildrenHidden
            switchHideNew.setOnCheckedChangeListener(onHideNewChanged)
        }

        val rows = currentRows()
        adapter.submit(rows)
        tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        // Give every listed child an explicit row (current state) — see class KDoc.
        val explicit = aclRows.filter { it.membershipId == membershipId }.map { it.childKey }.toSet()
        val missing = rows.map { it.key }.filter { it !in explicit }
        if (missing.isNotEmpty()) {
            activity.lifecycleScope.launch { repository.ensureMembershipAclRows(membershipId, missing) }
        }
    }

    internal data class Row(val key: String, val name: String, val kind: String, val visible: Boolean)

    private class Adapter(
        private val onToggle: (Row, Boolean) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        private var rows: List<Row> = emptyList()

        fun submit(newRows: List<Row>) { rows = newRows; notifyDataSetChanged() }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_acl_child, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.name.text = row.name
            holder.kind.text = row.kind
            // Bind state without firing the listener, then attach it.
            holder.visible.setOnCheckedChangeListener(null)
            holder.visible.isChecked = row.visible
            holder.visible.setOnCheckedChangeListener { _, checked -> onToggle(row, checked) }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView         = v.findViewById(R.id.tvAclChildName)
            val kind: TextView         = v.findViewById(R.id.tvAclChildKind)
            val visible: SwitchMaterial = v.findViewById(R.id.switchAclChildVisible)
        }
    }
}
