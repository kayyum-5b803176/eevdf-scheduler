package com.eevdf.capabilities.grouppicker.output

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.capabilities.grouppicker.R
import com.eevdf.capabilities.grouppicker.state.GroupTaskPrefs
import com.eevdf.capabilities.grouppicker.state.RecentGroupPrefs
import com.eevdf.capabilities.taskstorage.PickerDrill
import com.eevdf.capabilities.taskstorage.Task
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * A full-screen-ish DialogFragment that replaces the plain [Spinner] for
 * Parent Group selection.  Layout sections (top → bottom):
 *
 *   ┌─────────────────────────────┐
 *   │  [✕]  Select Parent Group   │  title row
 *   ├─────────────────────────────┤
 *   │  🔍  Search…               │  pattern-match field (same logic as category)
 *   ├─────────────────────────────┤
 *   │  Recent                     │  header — hidden when no recent history
 *   │  ○ Group Alpha              │  up to 8 most-recently chosen groups
 *   │  ─────────────────────────  │
 *   │  All Groups                 │  header
 *   │  ○ None (root level)        │
 *   │  ○ Group Alpha              │  full sorted list, filtered by search
 *   │  …                          │
 *   └─────────────────────────────┘
 *
 * Caller wires [onGroupSelected] before showing; receives the chosen [Task]
 * (null = "None / root level").
 *
 * Generalized for the Links feature (symlinks/hardlinks): [title] and
 * [showNoneOption] let the same dialog serve as either the original
 * groups-only parent picker, or a picker over EVERY task/group (a link's
 * target can be any task, not just a group). [allGroups] intentionally keeps
 * its original name for source compatibility with [GroupSection]'s existing
 * call site — despite the name, callers may now pass any mixed task/group list.
 */
class PickerDialog : DialogFragment() {

    // ── Public API ────────────────────────────────────────────────────────────

    /** Full sorted list of selectable items (excluding the task being edited). */
    var allGroups: List<Task> = emptyList()

    /**
     * Every task and group the caller knows about — used only to find the ANCESTORS of the items in [allGroups]
     * when several of them share a name (the drill-down, see [PickerDrill]). Empty = [allGroups] alone, which is
     * enough when the ancestors are in it too.
     */
    var pathContext: List<Task> = emptyList()

    /** Currently selected item id (null = root / none). Used to pre-tick the item. */
    var currentGroupId: String? = null

    /** Dialog title. Defaults to the original parent-group picker's title. */
    var title: String = "Select Parent Group"

    /** Whether a "None" entry is offered (root-level / no target). */
    var showNoneOption: Boolean = true

    /** Label for the "None" entry when [showNoneOption] is true. */
    var noneLabel: String = "None (root level)"

    /** Invoked on the main thread when the user taps an item. */
    var onGroupSelected: ((Task?) -> Unit)? = null

    // ── Views ─────────────────────────────────────────────────────────────────

    private lateinit var etSearch:      TextInputEditText
    private lateinit var rvItems:       RecyclerView
    private lateinit var adapter:       PickerAdapter
    private lateinit var tvTitle:       TextView

    // ── Drill-down state ──────────────────────────────────────────────────────

    /** One screen of the drill-down: "process1: which one?" and the rows under it. */
    private data class DrillStep(val title: String, val rows: List<PickerDrill.Row>, val depth: Int)

    /** Empty = the normal list. Each pick of a repeated name pushes one step; Back pops one. */
    private val drillStack = ArrayDeque<DrillStep>()

    /** Everything needed to walk up from a candidate to its ancestors. */
    private val byId: Map<String, Task> by lazy { (pathContext + allGroups).associateBy { it.id } }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext(), R.style.AppTheme_PickerDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_group_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvTitle = view.findViewById(R.id.tvPickerTitle)
        tvTitle.text = title
        view.findViewById<View>(R.id.btnPickerClose).setOnClickListener { dismiss() }

        etSearch = view.findViewById(R.id.etGroupSearch)
        rvItems  = view.findViewById(R.id.rvGroupItems)

        adapter = PickerAdapter(
            currentGroupId, noneLabel,
            onPick = { chosen -> finish(chosen) },
            onRow  = { row -> onRowPicked(row) },
            onBack = { goBack() },
        )
        // The system Back key goes up one drill step before it closes the dialog.
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && drillStack.isNotEmpty()) {
                goBack(); true
            } else false
        }
        rvItems.layoutManager = LinearLayoutManager(requireContext())
        rvItems.adapter       = adapter

        // Initial render
        applyFilter("")

        // Live search — pattern match (contains, case-insensitive), same approach
        // as the category AutoCompleteTextView threshold=1 filter.
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilter(s?.toString() ?: "") }
        })
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    // ── Choosing and drilling down ────────────────────────────────────────────

    /** The final choice: remembered as Recent, handed to the caller, and the dialog closes. */
    private fun finish(chosen: Task?) {
        RecentGroupPrefs.push(requireContext(), chosen?.id)
        onGroupSelected?.invoke(chosen)
        dismiss()
    }

    /** A distinct-name row (or a drill row) was tapped: choose its task if it is unique, else show where they are. */
    private fun onRowPicked(row: PickerDrill.Row) {
        val depth = drillStack.lastOrNull()?.depth ?: 0
        when (val next = PickerDrill.next(row.candidates, byId, depth)) {
            is PickerDrill.Next.Chosen -> finish(next.task)
            is PickerDrill.Next.Ask    -> {
                drillStack.addLast(DrillStep(next.title, next.rows, next.depth))
                renderDrill()
            }
        }
    }

    /** One step up: the previous drill list, or the normal list again. */
    private fun goBack() {
        if (drillStack.isEmpty()) return
        drillStack.removeLast()
        if (drillStack.isEmpty()) {
            tvTitle.text = title
            setSearchVisible(true)
            applyFilter(etSearch.text?.toString() ?: "")
        } else renderDrill()
    }

    private fun renderDrill() {
        val step = drillStack.last()
        tvTitle.text = step.title
        setSearchVisible(false)                         // searching names makes no sense while choosing a position
        val items = mutableListOf<PickerItem>(PickerItem.Back)
        step.rows.forEach { items += PickerItem.Names(it) }
        adapter.submitList(items)
    }

    private fun setSearchVisible(visible: Boolean) {
        var p = etSearch.parent
        while (p != null && p !is TextInputLayout) p = p.parent
        (p as? View ?: etSearch).visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ── Filter ────────────────────────────────────────────────────────────────

    /**
     * Rebuilds the adapter item list for the given [query].
     *
     * • Empty query   → show Recent section (if any) + full All Groups list.
     * • Non-empty     → hide Recent, show only filtered All Groups list.
     *
     * Filtering uses [String.contains] (case-insensitive) — the same "pattern
     * match" strategy used for the category autocomplete adapter filter.
     */
    private fun applyFilter(query: String) {
        val q = query.trim()
        val items = mutableListOf<PickerItem>()

        if (q.isEmpty()) {
            // Recent section
            // Recent items are the EXACT item chosen, so one is picked directly (no drill-down); a repeated
            // name is labelled with its path so two of them can be told apart.
            val recentIds = RecentGroupPrefs.getIds(requireContext())
            val recentGroups = recentIds.mapNotNull { id -> allGroups.find { it.id == id } }
            if (recentGroups.isNotEmpty()) {
                items += PickerItem.Header("Recent")
                recentGroups.forEach { items += PickerItem.Entry(it, PickerDrill.label(it, allGroups, byId)) }
                items += PickerItem.Divider
            }

            // All groups section — one row per DISTINCT name; a name several share drills down when picked
            items += PickerItem.Header("All Groups")
            if (showNoneOption) items += PickerItem.Entry(null)
            PickerDrill.nameRows(allGroups).forEach { items += PickerItem.Names(it) }
        } else {
            // Filtered — no section headers, just matching names
            if (showNoneOption) items += PickerItem.Entry(null)
            PickerDrill.nameRows(allGroups)
                .filter { it.label.contains(q, ignoreCase = true) }
                .forEach { items += PickerItem.Names(it) }
        }

        adapter.submitList(items)
    }

    // ── Data model ────────────────────────────────────────────────────────────

    sealed class PickerItem {
        data class Header(val label: String) : PickerItem()
        data class Entry(val group: Task?, val label: String? = null) : PickerItem()   // null = root; label overrides the name
        /** A row standing for every task in [row] that shares its label (see [PickerDrill]). */
        data class Names(val row: PickerDrill.Row) : PickerItem()
        /** "‹ Back" — one step up the drill-down. */
        object Back                          : PickerItem()
        object Divider                       : PickerItem()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class PickerAdapter(
        private val selectedId: String?,
        private val noneLabel: String,
        private val onPick: (Task?) -> Unit,
        private val onRow: (PickerDrill.Row) -> Unit,
        private val onBack: () -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<PickerItem> = emptyList()

        fun submitList(newItems: List<PickerItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) = when (items[position]) {
            is PickerItem.Header  -> VIEW_HEADER
            is PickerItem.Divider -> VIEW_DIVIDER
            is PickerItem.Entry   -> VIEW_ENTRY
            is PickerItem.Names   -> VIEW_ENTRY
            is PickerItem.Back    -> VIEW_ENTRY
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                VIEW_HEADER -> HeaderVH(inf.inflate(R.layout.item_group_picker_header,  parent, false))
                VIEW_DIVIDER -> DividerVH(inf.inflate(R.layout.item_group_picker_divider, parent, false))
                else        -> EntryVH(inf.inflate(R.layout.item_group_picker_entry,   parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is PickerItem.Header  -> (holder as HeaderVH).bind(item.label)
                is PickerItem.Divider -> { /* static view */ }
                is PickerItem.Entry   -> (holder as EntryVH).bind(item.group, item.label)
                is PickerItem.Names   -> (holder as EntryVH).bindRow(item.row)
                is PickerItem.Back    -> (holder as EntryVH).bindBack()
            }
        }

        // ViewHolders ─────────────────────────────────────────────────────────

        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            private val tv = v.findViewById<TextView>(R.id.tvPickerHeader)
            fun bind(label: String) { tv.text = label }
        }

        inner class DividerVH(v: View) : RecyclerView.ViewHolder(v)

        inner class EntryVH(v: View) : RecyclerView.ViewHolder(v) {
            private val tvName   = v.findViewById<TextView>(R.id.tvPickerEntryName)
            private val ivCheck  = v.findViewById<View>(R.id.ivPickerCheck)

            fun bind(group: Task?, label: String? = null) {
                tvName.text   = label ?: group?.name ?: noneLabel
                val isSelected = group?.id == selectedId
                ivCheck.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
                itemView.setOnClickListener { onPick(group) }
            }

            /** A distinct-name / drill row: ticked when the current choice is one of its tasks. */
            fun bindRow(row: PickerDrill.Row) {
                tvName.text = row.label
                ivCheck.visibility = if (row.candidates.any { it.id == selectedId }) View.VISIBLE else View.INVISIBLE
                itemView.setOnClickListener { onRow(row) }
            }

            fun bindBack() {
                tvName.text = "‹  Back"
                ivCheck.visibility = View.INVISIBLE
                itemView.setOnClickListener { onBack() }
            }
        }

        companion object {
            private const val VIEW_HEADER  = 0
            private const val VIEW_ENTRY   = 1
            private const val VIEW_DIVIDER = 2
        }
    }
}
