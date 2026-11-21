package com.eevdf.capabilities.grouppicker.output

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.capabilities.grouppicker.R
import com.eevdf.capabilities.grouppicker.state.GroupTaskPrefs
import com.eevdf.capabilities.grouppicker.state.RecentGroupPrefs
import com.eevdf.capabilities.taskstorage.Task
import com.google.android.material.textfield.TextInputEditText

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

    private companion object {
        /** A selected id no group can have: nothing is ticked while another axis is viewed. */
        const val NOT_HERE = "\u0000not-on-this-axis"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Full sorted list of selectable items (excluding the task being edited). */
    var allGroups: List<Task> = emptyList()

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

    // ── Axes (optional) ──────────────────────────────────────────────────────
    //
    // When [axisIds] is non-empty a row of chips under the search field chooses WHICH axis's groups are
    // listed, and a pick reports the axis with it ([onAxisGroupSelected]) — choosing a group on another
    // axis is how a row is moved there. Leave it empty and the dialog is exactly the plain picker.

    /** The axes offered, in order. Empty = no chips. */
    var axisIds: List<String> = emptyList()

    /** The selectable groups of each axis (the caller has already left out what may not be chosen). */
    var axisGroups: Map<String, List<Task>> = emptyMap()

    /** The axis the current choice lives on; [currentGroupId] is ticked only while this axis is viewed. */
    var currentAxisId: String? = null

    /** Other axes cannot be chosen (the row cannot move right now); [axisLockedHint] says why. */
    var axisLocked: Boolean = false
    var axisLockedHint: String = ""

    /** Invoked instead of [onGroupSelected] when axes are offered: the viewed axis and the chosen group (null = that axis's root). */
    var onAxisGroupSelected: ((axisId: String, group: Task?) -> Unit)? = null

    private var viewedAxisId: String? = null

    // ── Views ─────────────────────────────────────────────────────────────────

    private lateinit var etSearch:      TextInputEditText
    private lateinit var rvItems:       RecyclerView
    private lateinit var adapter:       PickerAdapter

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext(), R.style.AppTheme_PickerDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_group_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.tvPickerTitle).text = title
        view.findViewById<View>(R.id.btnPickerClose).setOnClickListener { dismiss() }

        etSearch = view.findViewById(R.id.etGroupSearch)
        rvItems  = view.findViewById(R.id.rvGroupItems)

        adapter = PickerAdapter(currentGroupId, noneLabel) { chosen ->
            RecentGroupPrefs.push(requireContext(), chosen?.id)
            val axis = viewedAxisId
            if (axisIds.isNotEmpty() && axis != null) onAxisGroupSelected?.invoke(axis, chosen)
            else onGroupSelected?.invoke(chosen)
            dismiss()
        }
        rvItems.layoutManager = LinearLayoutManager(requireContext())
        rvItems.adapter       = adapter
        setupAxisChips(view)

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

    // ── Axis chips ────────────────────────────────────────────────────────────

    private fun setupAxisChips(view: View) {
        if (axisIds.isEmpty()) return
        val group = view.findViewById<ChipGroup>(R.id.chipGroupPickerAxis)
        val hint  = view.findViewById<TextView>(R.id.tvPickerAxisHint)
        val start = currentAxisId?.takeIf { it in axisIds } ?: axisIds.first()
        viewedAxisId = start
        showAxis(start)
        group.visibility = View.VISIBLE
        axisIds.forEach { axis ->
            val chip = layoutInflater.inflate(R.layout.item_group_picker_axis_chip, group, false) as Chip
            chip.text = axis.uppercase()
            chip.id = View.generateViewId()
            chip.isChecked = axis == start
            // A row that cannot move can still be looked at on its own axis only.
            chip.isEnabled = !axisLocked || axis == currentAxisId
            chip.setOnCheckedChangeListener { _, checked -> if (checked) { showAxis(axis); etSearch.setText("") } }
            group.addView(chip)
        }
        if (axisLocked && axisLockedHint.isNotEmpty()) {
            hint.text = axisLockedHint
            hint.visibility = View.VISIBLE
        }
    }

    /** List [axis]'s groups; the tick and the "none" wording follow which axis is being looked at. */
    private fun showAxis(axis: String) {
        viewedAxisId = axis
        allGroups = axisGroups[axis].orEmpty()
        val onCurrent = axis == currentAxisId
        adapter.selectedId = if (onCurrent) currentGroupId else NOT_HERE
        adapter.noneLabel  = if (axis == axisIds.firstOrNull()) noneLabel else "None (root level of ${axis.uppercase()})"
        applyFilter(etSearch.text?.toString() ?: "")
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
            val recentIds = RecentGroupPrefs.getIds(requireContext())
            val recentGroups = recentIds.mapNotNull { id -> allGroups.find { it.id == id } }
            if (recentGroups.isNotEmpty()) {
                items += PickerItem.Header("Recent")
                recentGroups.forEach { items += PickerItem.Entry(it) }
                items += PickerItem.Divider
            }

            // All groups section
            items += PickerItem.Header(viewedAxisId?.let { "All Groups · ${it.uppercase()}" } ?: "All Groups")
            if (showNoneOption) items += PickerItem.Entry(null)
            allGroups.forEach { items += PickerItem.Entry(it) }
        } else {
            // Filtered — no section headers, just matches
            val matched = allGroups.filter { it.name.contains(q, ignoreCase = true) }
            if (showNoneOption) items += PickerItem.Entry(null)
            matched.forEach { items += PickerItem.Entry(it) }
        }

        adapter.submitList(items)
    }

    // ── Data model ────────────────────────────────────────────────────────────

    sealed class PickerItem {
        data class Header(val label: String) : PickerItem()
        data class Entry(val group: Task?)   : PickerItem()   // null = root
        object Divider                       : PickerItem()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class PickerAdapter(
        var selectedId: String?,
        var noneLabel: String,
        private val onPick: (Task?) -> Unit
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
                is PickerItem.Entry   -> (holder as EntryVH).bind(item.group)
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

            fun bind(group: Task?) {
                tvName.text   = group?.name ?: noneLabel
                val isSelected = group?.id == selectedId
                ivCheck.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
                itemView.setOnClickListener { onPick(group) }
            }
        }

        companion object {
            private const val VIEW_HEADER  = 0
            private const val VIEW_ENTRY   = 1
            private const val VIEW_DIVIDER = 2
        }
    }
}
