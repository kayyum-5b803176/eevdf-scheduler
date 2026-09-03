package com.eevdf.feature.links

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.data.task.Task
import com.eevdf.data.task.TaskLink
import com.eevdf.feature.R
import com.eevdf.feature.task.group.PickerDialog
import com.eevdf.feature.task.list.SortHelper
import com.eevdf.feature.task.list.TaskViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Dedicated screen for creating and managing symlinks and hardlinks.
 *
 * Opened by holding [com.eevdf.feature.task.list.MainActivity]'s Add Task FAB
 * (a plain tap still opens [com.eevdf.feature.task.addtask.AddTaskActivity]
 * unchanged) — this feature deliberately does NOT touch Edit Task or its
 * Task Type section at all.
 *
 * Symlink  — pure pointer. No config of its own; always shows the target's
 *            live data; tapping it navigates to the target's real location
 *            instead of running anything from here (see [TaskViewHolder]).
 * Hardlink — an extra REAL placement of the same task in another group. All
 *            config is genuinely shared (same [Task] row everywhere); only
 *            the scheduling/runtime state is placement-specific (see
 *            [com.eevdf.data.task.TaskMembership]).
 */
@AndroidEntryPoint
class LinksActivity : AppCompatActivity() {

    private val viewModel: TaskViewModel by viewModels()

    private lateinit var toggleLinkType: MaterialButtonToggleGroup
    private lateinit var tvLinkTypeDesc: TextView
    private lateinit var actvLinkTarget: AutoCompleteTextView
    private lateinit var actvLinkHost:   AutoCompleteTextView
    private lateinit var tvLinkError:    TextView
    private lateinit var btnCreateLink:  MaterialButton
    private lateinit var tvNoLinks:      TextView
    private lateinit var rvExistingLinks: RecyclerView

    /** Every non-completed task/group — the source list for both pickers. */
    private var allTasksSnapshot: List<Task> = emptyList()
    private var allMembershipsSnapshot: List<com.eevdf.data.task.TaskMembership> = emptyList()

    private var selectedTargetId: String? = null
    private var selectedHostId:   String? = null

    private val existingLinksAdapter = ExistingLinksAdapter { link -> viewModel.deleteSymlink(link.id) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_links)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Links"

        toggleLinkType   = findViewById(R.id.toggleLinkType)
        tvLinkTypeDesc   = findViewById(R.id.tvLinkTypeDesc)
        actvLinkTarget   = findViewById(R.id.actvLinkTarget)
        actvLinkHost     = findViewById(R.id.actvLinkHost)
        tvLinkError      = findViewById(R.id.tvLinkError)
        btnCreateLink    = findViewById(R.id.btnCreateLink)
        tvNoLinks        = findViewById(R.id.tvNoLinks)
        rvExistingLinks  = findViewById(R.id.rvExistingLinks)

        rvExistingLinks.layoutManager = LinearLayoutManager(this)
        rvExistingLinks.adapter = existingLinksAdapter

        toggleLinkType.check(R.id.btnTypeSymlink)
        updateTypeDescription()
        toggleLinkType.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) updateTypeDescription()
        }

        actvLinkTarget.setOnClickListener {
            val dialog = PickerDialog().apply {
                title          = "Select link target"
                showNoneOption = false
                allGroups      = allTasksSnapshot.sortedWith(SortHelper.taskNameComparator)
                currentGroupId = selectedTargetId
                onGroupSelected = { chosen ->
                    selectedTargetId = chosen?.id
                    actvLinkTarget.setText(chosen?.name ?: "", false)
                    tvLinkError.visibility = View.GONE
                }
            }
            dialog.show(supportFragmentManager, "link_target_picker")
        }

        actvLinkHost.setOnClickListener {
            val dialog = PickerDialog().apply {
                title          = "Place inside"
                showNoneOption = false
                allGroups      = allTasksSnapshot.filter { it.isGroup }
                    .sortedWith(SortHelper.taskNameComparator)
                currentGroupId = selectedHostId
                onGroupSelected = { chosen ->
                    selectedHostId = chosen?.id
                    actvLinkHost.setText(chosen?.name ?: "", false)
                    tvLinkError.visibility = View.GONE
                }
            }
            dialog.show(supportFragmentManager, "link_host_picker")
        }

        btnCreateLink.setOnClickListener { createLink() }

        viewModel.activeTasks.observe(this) { tasks ->
            allTasksSnapshot = tasks
            existingLinksAdapter.setTasks(tasks)
        }
        viewModel.allTaskLinks.observe(this) { links ->
            tvNoLinks.visibility = if (links.isEmpty()) View.VISIBLE else View.GONE
            existingLinksAdapter.setLinks(links)
        }
        viewModel.allTaskMemberships.observe(this) { memberships ->
            allMembershipsSnapshot = memberships
        }
    }

    private fun updateTypeDescription() {
        tvLinkTypeDesc.text = if (toggleLinkType.checkedButtonId == R.id.btnTypeHardlink)
            "A real, extra placement of the target inside the host group. Fully independent " +
                "scheduling/runtime there; every other setting is shared with the original."
        else
            "A pointer only. Always shows the target's live data; tapping it jumps to the " +
                "target's real location instead of running anything from here."
    }

    private fun createLink() {
        val targetId = selectedTargetId
        val hostId   = selectedHostId
        if (targetId == null || hostId == null) {
            tvLinkError.text = "Pick both a link target and a group to place it inside"
            tvLinkError.visibility = View.VISIBLE
            return
        }
        if (targetId == hostId) {
            tvLinkError.text = "A link can't target the group it would live inside"
            tvLinkError.visibility = View.VISIBLE
            return
        }
        if (wouldCreateCycle(targetId, hostId)) {
            tvLinkError.text = "That would nest the group inside itself"
            tvLinkError.visibility = View.VISIBLE
            return
        }
        tvLinkError.visibility = View.GONE

        if (toggleLinkType.checkedButtonId == R.id.btnTypeHardlink) {
            viewModel.createHardlink(targetId, hostId)
        } else {
            viewModel.createSymlink(targetId, hostId)
        }
        selectedTargetId = null
        selectedHostId   = null
        actvLinkTarget.setText("", false)
        actvLinkHost.setText("", false)
    }

    /**
     * True when placing [targetId] inside [hostId] would nest a group inside
     * its own subtree — i.e. [targetId] is [hostId] itself or a real ancestor
     * of it. Catches the direct case via the real `parentId` chain; a cycle
     * built purely out of chained symlinks/hardlinks is not detected here —
     * acceptable v1 scope, called out in the PR notes.
     */
    /**
     * True when placing [targetId] inside [hostId] would create a cycle.
     *
     * A hardlink adds a REAL extra parent edge (targetId's task now also
     * lives under hostId) — on top of every task's existing real parentId
     * edges AND every hardlink membership edge already in the graph. This
     * creates a cycle exactly when [hostId] is already reachable by walking
     * DOWN from [targetId] through EITHER kind of edge: if it is, the new
     * edge would make [targetId] its own descendant. Checking only the real
     * parentId chain (as a tree-only implementation would) misses cycles
     * built through an existing hardlink — this walks both.
     *
     * [ListBuilderDelegate]'s tree recursion also carries an independent
     * depth-limit guard as defense in depth, in case a cycle ever gets into
     * the data some other way (e.g. a future bug) — this check is the
     * primary prevention, that one is the safety net.
     */
    private fun wouldCreateCycle(targetId: String, hostId: String): Boolean {
        if (targetId == hostId) return true
        val visited = mutableSetOf<String>()
        val stack = ArrayDeque<String>().apply { add(targetId) }
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (!visited.add(current)) continue
            if (current == hostId) return true
            allTasksSnapshot.filter { it.parentId == current }.forEach { stack.add(it.id) }
            allMembershipsSnapshot.filter { it.groupId == current }.forEach { stack.add(it.taskId) }
        }
        return false
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── Existing links list ─────────────────────────────────────────────────

    private class ExistingLinksAdapter(
        private val onDelete: (TaskLink) -> Unit,
    ) : RecyclerView.Adapter<ExistingLinksAdapter.VH>() {

        private var links: List<TaskLink> = emptyList()
        private var tasksById: Map<String, Task> = emptyMap()

        fun setLinks(newLinks: List<TaskLink>) { links = newLinks; notifyDataSetChanged() }
        fun setTasks(tasks: List<Task>) { tasksById = tasks.associateBy { it.id }; notifyDataSetChanged() }

        override fun getItemCount() = links.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_link_entry, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val link   = links[position]
            val target = tasksById[link.targetTaskId]
            val host   = tasksById[link.hostGroupId]
            holder.title.text = target?.name ?: "(deleted task)"
            holder.subtitle.text = "→ ${host?.name ?: "(deleted group)"}"
            holder.delete.setOnClickListener { onDelete(link) }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title:    TextView    = v.findViewById(R.id.tvLinkEntryTitle)
            val subtitle: TextView    = v.findViewById(R.id.tvLinkEntrySubtitle)
            val delete:   ImageButton = v.findViewById(R.id.btnLinkEntryDelete)
        }
    }
}
