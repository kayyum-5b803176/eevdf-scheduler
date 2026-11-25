package com.eevdf.capabilities.forkscreen

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.capabilities.grouppicker.output.PickerDialog
import com.eevdf.capabilities.taskstorage.ForkRepository
import com.eevdf.capabilities.taskstorage.ForkRules
import com.eevdf.capabilities.taskstorage.IdentityPoolFullException
import com.eevdf.capabilities.taskstorage.IdentityRules
import com.eevdf.capabilities.taskstorage.Task
import com.eevdf.capabilities.taskstorage.TaskFork
import com.eevdf.capabilities.taskstorage.TaskRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * FORK: copy one task or group into a new sibling, the way Linux `fork()` copies a process.
 *
 * Opened from the add button's speed dial (MainActivity), and built like [com.eevdf.capabilities.linksscreen.LinksActivity]:
 * its own page, a field that opens the same list picker, an error line, a Create button, and a list of what has
 * been made.
 *
 * The person picks ONE thing — any task or group, the one to copy. The copy always lands in the SAME group as
 * the original (a forked process starts in its parent's cgroup), so there is deliberately no "place inside"
 * picker. What is copied, reset and dropped is [ForkRules]' job; numbering, placement at the back of the group,
 * queueing behind a full semaphore and the "forked from" record are [ForkRepository]'s.
 */
@AndroidEntryPoint
class ForkActivity : AppCompatActivity() {

    @Inject lateinit var repository: TaskRepository
    @Inject lateinit var forks: ForkRepository

    private lateinit var actvForkSource: AutoCompleteTextView
    private lateinit var etForkName:     TextInputEditText
    private lateinit var tvForkPreview:  TextView
    private lateinit var tvForkError:    TextView
    private lateinit var btnCreateFork:  MaterialButton
    private lateinit var tvNoForks:      TextView
    private lateinit var rvForks:        RecyclerView

    /** Every task and group, completed ones included — how the recent-forks list names things. */
    private var everyTask: List<Task> = emptyList()

    private var selectedSourceId: String? = null

    /** The name the box was last filled with for a source: a name the person typed is never overwritten. */
    private var lastSuggestedName = ""

    private val entriesAdapter = ForkEntriesAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fork)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Fork"

        actvForkSource = findViewById(R.id.actvForkSource)
        etForkName     = findViewById(R.id.etForkName)
        tvForkPreview  = findViewById(R.id.tvForkPreview)
        tvForkError    = findViewById(R.id.tvForkError)
        btnCreateFork  = findViewById(R.id.btnCreateFork)
        tvNoForks      = findViewById(R.id.tvNoForks)
        rvForks        = findViewById(R.id.rvForks)

        rvForks.layoutManager = LinearLayoutManager(this)
        rvForks.adapter = entriesAdapter

        actvForkSource.setOnClickListener { showSourcePicker() }
        btnCreateFork.setOnClickListener { createFork() }

        repository.allTasks.observe(this) { tasks ->
            everyTask = tasks
            entriesAdapter.setTasks(tasks)
            tvNoForks.visibility = if (entriesAdapter.itemCount == 0) View.VISIBLE else View.GONE
            refreshPreview()
        }
        forks.allForks.observe(this) { list ->
            entriesAdapter.setForks(list)
            tvNoForks.visibility = if (entriesAdapter.itemCount == 0) View.VISIBLE else View.GONE
        }
        lifecycleScope.launch { forks.cleanUp() }      // forget the records of forks that were deleted
    }

    private fun showSourcePicker() {
        val dialog = PickerDialog().apply {
            title          = "Select what to fork"
            showNoneOption = false
            allGroups      = ForkRules.forkable(everyTask)   // any task or group that is not finished
            currentGroupId = selectedSourceId
            onGroupSelected = { chosen -> onSourcePicked(chosen) }
        }
        dialog.show(supportFragmentManager, "fork_source_picker")
    }

    private fun onSourcePicked(chosen: Task?) {
        selectedSourceId = chosen?.id
        actvForkSource.setText(chosen?.name ?: "", false)
        tvForkError.visibility = View.GONE
        if (chosen != null) {
            val typed = etForkName.text?.toString().orEmpty()
            // Suggest "name (fork)" — but never over a name the person has typed themselves.
            if (typed.isBlank() || typed == lastSuggestedName) {
                lastSuggestedName = ForkRules.defaultName(chosen.name)
                etForkName.setText(lastSuggestedName)
            }
        }
        refreshPreview()
    }

    /** "Will get process:205, forked from process:142 — placed in alice": what Create fork would do right now. */
    private fun refreshPreview() {
        val sourceId = selectedSourceId
        val source   = sourceId?.let { id -> everyTask.firstOrNull { it.id == id } }
        if (source == null) { tvForkPreview.visibility = View.GONE; return }

        val kind  = ForkRules.childKind(source.identity)
        val group = source.parentId?.let { pid -> everyTask.firstOrNull { it.id == pid }?.name } ?: "the top level"
        lifecycleScope.launch {
            val next = repository.nextIdentityId(kind, null)
            if (selectedSourceId != sourceId) return@launch     // another source was picked while this ran
            val will = if (next != null) IdentityRules.format(kind, next) else "no free ID left for ${kind.label}"
            tvForkPreview.text = "Will get  $will\n" +
                "Forked from  ${IdentityRules.display(source.identity)}\n" +
                "Placed in  $group  (the same group as the original)"
            tvForkPreview.visibility = View.VISIBLE
        }
    }

    private fun showError(message: String) {
        tvForkError.text = message
        tvForkError.visibility = View.VISIBLE
    }

    private fun createFork() {
        val sourceId = selectedSourceId
        if (sourceId == null) { showError("Pick the task or group to fork"); return }
        val name = etForkName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) { showError("Give the fork a name"); return }
        tvForkError.visibility = View.GONE

        btnCreateFork.isEnabled = false
        lifecycleScope.launch {
            try {
                val child = forks.fork(sourceId, name)
                if (child == null) {
                    showError("That task no longer exists")
                } else {
                    Toast.makeText(this@ForkActivity,
                        "Forked as ${IdentityRules.display(child.identity)}", Toast.LENGTH_SHORT).show()
                    selectedSourceId = null
                    lastSuggestedName = ""
                    actvForkSource.setText("", false)
                    etForkName.setText("")
                    tvForkPreview.visibility = View.GONE
                }
            } catch (e: IdentityPoolFullException) {
                showError("No free ID left for ${e.kind.label}")
            } finally {
                btnCreateFork.isEnabled = true
            }
        }
    }

    /**
     * The "Recent forks" list: each fork, newest first, with its identity, the identity of what it was forked
     * from and the group it sits in. Only forks whose task still exists are listed; a parent that is gone
     * reads "removed". Read-only: a fork is an ordinary task afterwards.
     */
    private class ForkEntriesAdapter : RecyclerView.Adapter<ForkEntriesAdapter.VH>() {

        private var forks: List<TaskFork> = emptyList()
        private var tasksById: Map<String, Task> = emptyMap()
        private var visible: List<TaskFork> = emptyList()

        fun setForks(newForks: List<TaskFork>)  { forks = newForks; rebuild() }
        fun setTasks(tasks: List<Task>)         { tasksById = tasks.associateBy { it.id }; rebuild() }

        private fun rebuild() {
            visible = forks.filter { it.taskId in tasksById }.take(MAX_ROWS)
            notifyDataSetChanged()
        }

        override fun getItemCount() = visible.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_fork_entry, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val fork   = visible[position]
            val child  = tasksById.getValue(fork.taskId)
            val parent = tasksById[fork.parentTaskId]
            val from   = parent?.let { IdentityRules.display(it.identity) } ?: "removed"
            val group  = child.parentId?.let { tasksById[it]?.name } ?: "top level"
            holder.title.text    = "${child.name}  ·  ${IdentityRules.display(child.identity)}"
            holder.subtitle.text = "forked from $from  ·  in $group"
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title:    TextView = v.findViewById(R.id.tvForkEntryTitle)
            val subtitle: TextView = v.findViewById(R.id.tvForkEntrySubtitle)
        }

        private companion object { const val MAX_ROWS = 50 }
    }
}
