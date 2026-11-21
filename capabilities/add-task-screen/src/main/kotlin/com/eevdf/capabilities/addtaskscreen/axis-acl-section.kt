package com.eevdf.capabilities.addtaskscreen

import android.view.LayoutInflater
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.capabilities.taskstorage.AclChoice
import com.eevdf.capabilities.taskstorage.AclResolution
import com.eevdf.capabilities.taskstorage.AxisAclRules
import com.eevdf.capabilities.taskstorage.Axes
import com.eevdf.capabilities.taskstorage.Task
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

/**
 * ACL tab for [AddTaskActivity] — what a row includes from the NEXT axis.
 *
 * Opened with the `axis_door` extras, which [com.eevdf.capabilities.taskstorage.TaskRepository.axisEditTarget]
 * decides from the database. The three places it appears:
 *  - editing a first-axis leaf (Queue·X, Schedule): its own list of Y entries;
 *  - editing a later-axis entry in its own Queue tab (Queue·Y): the list of Z entries for EVERY copy of it;
 *  - editing a copy (a row under an X leaf): that copy's own list, layered over the entry's — a row set
 *    here wins, Inherit follows the entry's ACL, so one copy can differ from the others.
 * Shown only when that next axis has any entries.
 *
 * Exactly the hardlink ACL tab ([AclTab]) — same layouts, same row adapter, same behaviour:
 * one level at a time, three circles per row (Inherit / Hide / Show), tap a group to open it,
 * breadcrumb or back to go up, a "hide new" switch for the door's default, every tap saves at
 * once, and everything under a hidden group is locked. Each change also brings the affected
 * real copies in step (copies are created or removed in the background).
 *
 * The two tabs never coexist: a hardlink placement's ACL owns the tab bar when `membership_id`
 * is present.
 */
internal fun AddTaskActivity.setupAxisAclSection() {
    val door = intent.getStringExtra("axis_door") ?: return
    val axis = intent.getStringExtra("axis_door_axis") ?: return
    if (intent.getStringExtra("membership_id") != null) return
    axisAclTab = AxisAclTab(this, door, axis, intent.getStringExtra("axis_door_layer")).also { it.start() }
}

internal class AxisAclTab(
    private val activity: AddTaskActivity,
    private val door: String,
    /** The axis this door lists. */
    private val listAxis: String,
    /** For a copy: the entry's door its own rules are layered over; null otherwise. */
    private val layerDoor: String?,
) {
    private val tabs: TabLayout        = activity.findViewById(R.id.tabAddTask)
    private val configScroll: View     = activity.findViewById(R.id.configScroll)
    private val aclContainer: View     = activity.findViewById(R.id.aclContainer)
    private val switchHideNew: SwitchMaterial = activity.findViewById(R.id.switchAclHideNew)
    private val breadcrumb: LinearLayout = activity.findViewById(R.id.aclBreadcrumb)
    private val tvLocked: TextView     = activity.findViewById(R.id.tvAclLocked)
    private val tvEmpty: TextView      = activity.findViewById(R.id.tvAclEmpty)
    private val rv: RecyclerView       = activity.findViewById(R.id.rvAclChildren)

    private val repository = activity.repository
    private val adapter = AclTab.Adapter(
        onChoice = { row, choice -> activity.lifecycleScope.launch { repository.setAxisChoice(door, row.key, choice) } },
        onOpen   = { row -> open(row) },
    )

    private var defs: List<Task>   = emptyList()
    /** This door's own rules, the entry's rules it is layered over, and the two "hide new" defaults (null = not set). */
    private var ownRules: Map<String, Boolean>   = emptyMap()
    private var entryRules: Map<String, Boolean> = emptyMap()
    /** Entries carrying the single '−' (placed on a path): hidden at every door that has no rule of its own for them. */
    private var hiddenEverywhere: Set<String> = emptySet()
    private var ownHideNew: Boolean?   = null
    private var entryHideNew: Boolean? = null
    private val rules: Map<String, Boolean> get() = AxisAclRules.merge(entryRules, ownRules)
    private val hideNew: Boolean get() = AxisAclRules.effectiveHideNew(ownHideNew, entryHideNew)
    private var tabsShown = false
    private var aclShown  = false

    private data class Level(val id: String, val name: String)
    /** The groups opened below the top of the list, outermost first. Empty = the axis's own top level. */
    private val stack = ArrayList<Level>()

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { goUp() }
    }

    private val onHideNewChanged = CompoundButton.OnCheckedChangeListener { _, checked ->
        activity.lifecycleScope.launch { repository.setAxisHideNew(door, checked) }
    }

    fun start() {
        rv.layoutManager = LinearLayoutManager(activity)
        rv.adapter = adapter
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)

        tabs.addTab(tabs.newTab().setText("Config"), true)
        tabs.addTab(tabs.newTab().setText("ACL"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showAcl(tab.position == 1)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        switchHideNew.setOnCheckedChangeListener(onHideNewChanged)

        activity.viewModel.axes.tasksOf(listAxis).observe(activity) { defs = it; refresh() }
        repository.allAxisAcl.observe(activity) { all ->
            ownRules   = all.asSequence().filter { it.scopeKey == door }.associate { it.childKey to it.hidden }
            entryRules = layerDoor?.let { l -> all.asSequence().filter { it.scopeKey == l }.associate { it.childKey to it.hidden } }.orEmpty()
            refresh()
        }
        repository.allAxisEntryHidden.observe(activity) { all -> hiddenEverywhere = all.mapTo(HashSet()) { it.definitionId }; refresh() }
        repository.allAxisDoors.observe(activity) { all ->
            ownHideNew   = all.firstOrNull { it.scopeKey == door }?.newChildrenHidden
            entryHideNew = layerDoor?.let { l -> all.firstOrNull { it.scopeKey == l }?.newChildrenHidden }
            refresh()
        }
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

    private fun open(row: AclTab.Row) {
        if (!row.drillable) return
        stack.add(Level(row.realId, row.name))
        refresh()
    }

    private fun refresh() {
        // Nothing defined on that axis yet: there is nothing to include — the screen stays as it was.
        if (defs.isEmpty()) {
            tabs.visibility = View.GONE
            tabsShown = false
            stack.clear()
            showAcl(false)
            return
        }
        if (!tabsShown) { tabs.visibility = View.VISIBLE; tabsShown = true }

        val byId = defs.associateBy { it.id }
        fun topParent(t: Task): String? = t.parentId?.takeIf { it in byId }

        // A level that no longer exists (deleted / moved) cuts the path there.
        var parentId: String? = null
        val valid = ArrayList<Level>()
        for (level in stack) {
            val t = byId[level.id]
            if (t == null || !t.isGroup || topParent(t) != parentId) break
            valid.add(level)
            parentId = t.id
        }
        if (valid.size != stack.size) { stack.clear(); stack.addAll(valid) }
        backCallback.isEnabled = aclShown && stack.isNotEmpty()

        // No listener feedback while syncing the switch to stored state.
        if (switchHideNew.isChecked != hideNew) {
            switchHideNew.setOnCheckedChangeListener(null)
            switchHideNew.isChecked = hideNew
            switchHideNew.setOnCheckedChangeListener(onHideNewChanged)
        }

        renderBreadcrumb(listAxis)
        val ancestorKeys = stack.map { it.id }

        // Is the level being viewed itself hidden? Then nothing in it can be changed.
        val merged = rules
        val here = stack.lastOrNull()?.let { AxisAclRules.resolve(ancestorKeys.dropLast(1), it.id, merged, hideNew, hiddenEverywhere) }
        if (here != null && here.hidden) {
            tvLocked.text = "\"${stack.last().name}\" is hidden, so everything inside it is hidden too. " +
                "Make it visible to change these."
            tvLocked.visibility = View.VISIBLE
        } else {
            tvLocked.visibility = View.GONE
        }

        val rows = buildRows(byId, stack.lastOrNull()?.id, ancestorKeys)
        adapter.submit(rows)
        tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderBreadcrumb(axis: String) {
        breadcrumb.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        val scopeNote = when {
            layerDoor != null                              -> " · this copy"
            door.startsWith(Axes.DEFINITION_DOOR)          -> " · every copy"
            else                                           -> ""
        }
        val names = listOf(axis.uppercase() + scopeNote) + stack.map { it.name }
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

    /** The entries at one level of the definition tree. */
    private fun buildRows(byId: Map<String, Task>, nodeId: String?, ancestorKeys: List<String>): List<AclTab.Row> {
        val nameOfKey = stack.associate { it.id to it.name }

        fun caption(res: AclResolution, key: String): String {
            val lockedBy = res.lockedByKey   // local copy: a public property of another module can't be smart-cast
            return when {
                lockedBy != null              -> "Hidden by ${nameOfKey[lockedBy] ?: "a parent"}"
                res.choice == AclChoice.HIDE  -> "Hidden · set here"
                res.choice == AclChoice.SHOW  -> "Shown · set here"
                res.hidden && key in hiddenEverywhere -> "Hidden · all other places (tap + to add it here)"
                res.hidden                    -> "Hidden (inherited)"
                else                          -> "Shown (inherited)"
            }
        }

        val merged = rules
        val effHide = hideNew
        return defs
            .filter { (it.parentId?.takeIf { p -> p in byId }) == nodeId }
            .sortedBy { it.name.lowercase() }
            .map { t ->
                val res    = AxisAclRules.resolve(ancestorKeys, t.id, merged, effHide, hiddenEverywhere)
                // On a copy the circles show what is set HERE; "Inherit" means follow the entry's ACL.
                val own    = if (layerDoor == null) res.choice else when (ownRules[t.id]) {
                    null -> AclChoice.INHERIT; true -> AclChoice.HIDE; false -> AclChoice.SHOW
                }
                val fromEntry = layerDoor != null && ownRules[t.id] == null && entryRules[t.id] != null && res.lockedByKey == null
                val inside = if (t.isGroup) rulesInside(t.id, byId) else 0
                val base   = if (fromEntry) (if (res.hidden) "Hidden" else "Shown") + " · from the entry's ACL" else caption(res, t.id)
                val text   = base + if (inside > 0) " · $inside rule${if (inside == 1) "" else "s"} inside" else ""
                AclTab.Row(t.id, t.id, t.name, if (t.isGroup) "cgroup" else "task", t.isGroup, own,
                    locked = res.lockedByKey != null, caption = text)
            }
    }

    /** How many rules of this door sit on entries inside the group [groupId] (any depth, not the group itself). */
    private fun rulesInside(groupId: String, byId: Map<String, Task>): Int {
        if (rules.isEmpty()) return 0
        val keys = HashSet<String>()
        val pending = ArrayDeque<String>().apply { add(groupId) }
        val seen = HashSet<String>()
        while (pending.isNotEmpty()) {
            val id = pending.removeLast()
            if (!seen.add(id)) continue
            for (t in byId.values) if (t.parentId == id) { keys.add(t.id); if (t.isGroup) pending.add(t.id) }
        }
        return rules.keys.count { it in keys }
    }
}
