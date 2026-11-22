package com.eevdf.capabilities.addtaskscreen

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.capabilities.taskstorage.AclChoice
import com.eevdf.capabilities.taskstorage.AclResolution
import com.eevdf.capabilities.taskstorage.MembershipAcl
import com.eevdf.capabilities.taskstorage.MembershipAclKeys
import com.eevdf.capabilities.taskstorage.MembershipAclRules
import com.eevdf.capabilities.taskstorage.Task
import com.eevdf.capabilities.taskstorage.TaskLink
import com.eevdf.capabilities.taskstorage.TaskMembership
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch

/**
 * ACL tab for [AddTaskActivity] — hardlink placements only.
 *
 * Shown only when the screen was opened from a hardlink row (intent extra
 * `membership_id`) AND that placement's task is a group. Two tabs, "Config"
 * (the existing form, untouched) and "ACL"; switching only toggles visibility
 * of the form's scroll view and the ACL container, never moves the form's views.
 *
 * MULTI-LEVEL, ONE LEVEL AT A TIME. The page lists the items at one level of the
 * hardlinked group: real tasks/groups, plus hardlink placements and symlinks
 * hosted there. Each row has three circles — Inherit / Hide / Show — and tapping
 * a GROUP's name opens it (breadcrumb or the back arrow goes up). Rules inherit
 * downward, so a rule on a group covers everything inside it until a deeper rule
 * overrides it; see [MembershipAcl].
 *
 *  - Inherit = no rule (follow the parent); Hide / Show write a rule. Every tap
 *    saves immediately through [com.eevdf.capabilities.taskstorage.TaskRepository];
 *    there is no Save button, and the form's Save is not involved.
 *  - Under a hidden group every circle is disabled: nothing inside a hidden group
 *    can be shown, so make the group visible first.
 *  - A hosted hardlink placement is not opened: its inside is governed by ITS own
 *    rules (it is a door of its own), so nothing here would apply to it.
 */
internal fun AddTaskActivity.setupAclSection() {
    val membershipId = intent.getStringExtra("membership_id") ?: return   // no door: tab bar stays hidden, screen unchanged
    aclTab = AclTab(this, membershipId).also { it.start() }
}

internal class AclTab(
    private val activity: AddTaskActivity,
    private val membershipId: String,
) {
    private val configScroll: View     = activity.findViewById(R.id.configScroll)
    private val aclContainer: View     = activity.findViewById(R.id.aclContainer)
    private val switchHideNew: SwitchMaterial = activity.findViewById(R.id.switchAclHideNew)
    private val breadcrumb: LinearLayout = activity.findViewById(R.id.aclBreadcrumb)
    private val tvLocked: TextView     = activity.findViewById(R.id.tvAclLocked)
    private val tvEmpty: TextView      = activity.findViewById(R.id.tvAclEmpty)
    private val rv: RecyclerView       = activity.findViewById(R.id.rvAclChildren)

    private val repository = activity.repository
    private val adapter = Adapter(
        onChoice = { row, choice ->
            activity.lifecycleScope.launch { repository.setMembershipChildChoice(membershipId, row.key, choice) }
        },
        onOpen = { row -> open(row) },
    )

    private var tasks: List<Task>                 = emptyList()
    private var links: List<TaskLink>             = emptyList()
    private var memberships: List<TaskMembership> = emptyList()
    private var aclRows: List<MembershipAcl>      = emptyList()
    private var aclShown  = false

    /** The groups opened below the hardlinked group, outermost first. Empty = the group's own level. */
    private val stack = ArrayList<Level>()

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { goUp() }
    }

    private val onHideNewChanged = CompoundButton.OnCheckedChangeListener { _, checked ->
        activity.lifecycleScope.launch { repository.setMembershipNewChildrenHidden(membershipId, checked) }
    }

    fun start() {
        rv.layoutManager = LinearLayoutManager(activity)
        rv.adapter = adapter
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)

        // Opened from a hardlink placement, so it has an ACL: the tab is there for as long as the screen is, decided by that
        // fact and not by whether the placement's data has loaded yet.
        activity.editTabs.onPage = { acl -> showAcl(acl) }
        activity.editTabs.setAclAvailable(true)
        switchHideNew.setOnCheckedChangeListener(onHideNewChanged)

        repository.activeTasks.observe(activity)        { tasks = it;       refresh() }
        repository.allTaskLinks.observe(activity)       { links = it;       refresh() }
        repository.allTaskMemberships.observe(activity) { memberships = it; refresh() }
        repository.allMembershipAcl.observe(activity)   { aclRows = it;     refresh() }
    }

    /** Toolbar back arrow / system back while on the ACL tab: one level up. False when already at the top. */
    fun goUp(): Boolean {
        if (!aclShown || stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        refresh()
        return true
    }

    private fun showAcl(acl: Boolean) {
        aclShown = acl
        configScroll.visibility = if (acl) View.GONE else View.VISIBLE
        aclContainer.visibility = if (acl) View.VISIBLE else View.GONE
        backCallback.isEnabled = acl && stack.isNotEmpty()
    }

    private fun open(row: Row) {
        if (!row.drillable) return
        stack.add(Level(row.key, row.realId, row.name))
        refresh()
    }

    private fun membership(): TaskMembership? = memberships.firstOrNull { it.id == membershipId }

    private fun group(): Task? = membership()?.let { m -> tasks.firstOrNull { it.id == m.taskId } }

    private fun refresh() {
        val m = membership()
        val g = group()
        // Still loading, not a group, or the placement is gone: the tab stays (it belongs to the link) and the page says so.
        if (m == null || g == null || !g.isGroup) {
            stack.clear()
            adapter.submit(emptyList())
            breadcrumb.removeAllViews()
            tvLocked.visibility = View.GONE
            tvEmpty.text = "Nothing to control here yet."
            tvEmpty.visibility = View.VISIBLE
            backCallback.isEnabled = false
            return
        }
        tvEmpty.text = "This group has no children yet."

        // A level that no longer exists (deleted / moved / asleep) cuts the path there.
        val byId = tasks.associateBy { it.id }
        var parentId = g.id
        val valid = ArrayList<Level>()
        for (level in stack) {
            val t = byId[level.realId]
            if (t == null || !t.isGroup || t.parentId != parentId) break
            valid.add(level)
            parentId = t.id
        }
        if (valid.size != stack.size) { stack.clear(); stack.addAll(valid) }
        backCallback.isEnabled = aclShown && stack.isNotEmpty()

        // No listener feedback while syncing the switch to stored state.
        if (switchHideNew.isChecked != m.newChildrenHidden) {
            switchHideNew.setOnCheckedChangeListener(null)
            switchHideNew.isChecked = m.newChildrenHidden
            switchHideNew.setOnCheckedChangeListener(onHideNewChanged)
        }

        renderBreadcrumb(g)
        val nodeId = stack.lastOrNull()?.realId ?: g.id
        val ancestorKeys = stack.map { it.key }

        // Is the level being viewed itself hidden? Then nothing in it can be changed.
        val here = stack.lastOrNull()?.let { MembershipAclRules.resolve(m, ancestorKeys.dropLast(1), it.key, aclRows) }
        if (here != null && here.hidden) {
            tvLocked.text = "\"${stack.last().name}\" is hidden, so everything inside it is hidden too. " +
                "Make it visible to change these."
            tvLocked.visibility = View.VISIBLE
        } else {
            tvLocked.visibility = View.GONE
        }

        val rows = buildRows(m, nodeId, ancestorKeys)
        adapter.submit(rows)
        tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderBreadcrumb(g: Task) {
        breadcrumb.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        val names = listOf(g.name) + stack.map { it.name }
        names.forEachIndexed { index, name ->
            if (index > 0) breadcrumb.addView((inflater.inflate(R.layout.item_acl_crumb, breadcrumb, false) as TextView).apply {
                text = "›"; alpha = 0.5f
            })
            val last = index == names.lastIndex
            breadcrumb.addView((inflater.inflate(R.layout.item_acl_crumb, breadcrumb, false) as TextView).apply {
                text = name
                alpha = if (last) 1f else 0.7f
                if (!last) setOnClickListener {          // jump up to this level
                    while (stack.size > index) stack.removeAt(stack.lastIndex)
                    refresh()
                }
            })
        }
    }

    /** The items at one level: real children first, then hosted hardlinks, then symlinks. */
    private fun buildRows(m: TaskMembership, nodeId: String, ancestorKeys: List<String>): List<Row> {
        val byId = tasks.associateBy { it.id }
        val nameOfKey = stack.associate { it.key to it.name }
        val doorRules = aclRows.filter { it.membershipId == m.id }

        fun caption(res: AclResolution): String {
            val lockedBy = res.lockedByKey   // local copy: a public property of another module can't be smart-cast
            return when {
                lockedBy != null              -> "Hidden by ${nameOfKey[lockedBy] ?: "a parent"}"
                res.choice == AclChoice.HIDE  -> "Hidden · set here"
                res.choice == AclChoice.SHOW  -> "Shown · set here"
                res.hidden                    -> "Hidden (inherited)"
                else                          -> "Shown (inherited)"
            }
        }

        fun row(key: String, realId: String, name: String, kind: String, drillable: Boolean): Row {
            val res = MembershipAclRules.resolve(m, ancestorKeys, key, aclRows)
            val inside = if (drillable) rulesInside(realId, doorRules) else 0
            val text = caption(res) + if (inside > 0) " · $inside rule${if (inside == 1) "" else "s"} inside" else ""
            return Row(key, realId, name, kind, drillable, res.choice, locked = res.lockedByKey != null, caption = text)
        }

        val real = tasks.filter { it.parentId == nodeId && !it.isCompleted }
            .sortedBy { it.name.lowercase() }
            .map { row(MembershipAclKeys.real(it.id), it.id, it.name, if (it.isGroup) "cgroup" else "task", drillable = it.isGroup) }
        val hard = memberships.filter { it.groupId == nodeId }.mapNotNull { h ->
            val t = byId[h.taskId] ?: return@mapNotNull null
            row(MembershipAclKeys.membership(h.id), h.taskId, t.name, "hardlink · has its own ACL", drillable = false)
        }.sortedBy { it.name.lowercase() }
        val sym = links.filter { it.hostGroupId == nodeId }.map { l ->
            row(MembershipAclKeys.symlink(l.id), l.targetTaskId, byId[l.targetTaskId]?.name ?: "(broken link)", "symlink", drillable = false)
        }.sortedBy { it.name.lowercase() }
        return real + hard + sym
    }

    /** How many rules of this door sit on items inside the real group [groupId] (any depth, not the group itself). */
    private fun rulesInside(groupId: String, doorRules: List<MembershipAcl>): Int {
        if (doorRules.isEmpty()) return 0
        val keys = HashSet<String>()
        val stackIds = ArrayDeque<String>().apply { add(groupId) }
        val seen = HashSet<String>()
        while (stackIds.isNotEmpty()) {
            val id = stackIds.removeLast()
            if (!seen.add(id)) continue
            for (t in tasks) if (t.parentId == id) { keys.add(MembershipAclKeys.real(t.id)); if (t.isGroup) stackIds.add(t.id) }
            for (h in memberships) if (h.groupId == id) keys.add(MembershipAclKeys.membership(h.id))
            for (l in links) if (l.hostGroupId == id) keys.add(MembershipAclKeys.symlink(l.id))
        }
        return doorRules.count { it.childKey in keys }
    }

    private data class Level(val key: String, val realId: String, val name: String)

    internal data class Row(
        val key: String, val realId: String, val name: String, val kind: String,
        val drillable: Boolean, val choice: AclChoice, val locked: Boolean, val caption: String,
    )

    internal class Adapter(
        private val onChoice: (Row, AclChoice) -> Unit,
        private val onOpen: (Row) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        private var rows: List<Row> = emptyList()

        fun submit(newRows: List<Row>) { rows = newRows; notifyDataSetChanged() }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_acl_child, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.name.text = row.name
            holder.open.visibility = if (row.drillable) View.VISIBLE else View.GONE
            holder.kind.text = row.kind
            holder.caption.text = row.caption
            // Tapping the name area opens a group; other kinds have nothing to open.
            holder.text.setOnClickListener(if (row.drillable) View.OnClickListener { onOpen(row) } else null)
            holder.text.isClickable = row.drillable

            holder.radios.forEach { (choice, pair) ->
                val (frame, radio) = pair
                radio.isChecked = choice == row.choice
                radio.isEnabled = !row.locked
                frame.isEnabled = !row.locked
                frame.setOnClickListener { if (!row.locked && choice != row.choice) onChoice(row, choice) }
            }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val text: View        = v.findViewById(R.id.layoutAclText)
            val name: TextView    = v.findViewById(R.id.tvAclChildName)
            val open: TextView    = v.findViewById(R.id.tvAclChildOpen)
            val kind: TextView    = v.findViewById(R.id.tvAclChildKind)
            val caption: TextView = v.findViewById(R.id.tvAclChildCaption)
            val radios: Map<AclChoice, Pair<View, MaterialRadioButton>> = mapOf(
                AclChoice.INHERIT to (v.findViewById<View>(R.id.frameAclInherit) to v.findViewById<MaterialRadioButton>(R.id.radioAclInherit)),
                AclChoice.HIDE    to (v.findViewById<View>(R.id.frameAclHide)    to v.findViewById<MaterialRadioButton>(R.id.radioAclHide)),
                AclChoice.SHOW    to (v.findViewById<View>(R.id.frameAclShow)    to v.findViewById<MaterialRadioButton>(R.id.radioAclShow)),
            )
        }
    }
}
