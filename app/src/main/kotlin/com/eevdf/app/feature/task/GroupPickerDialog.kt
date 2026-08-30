package com.eevdf.app.feature.task

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.app.R
import com.eevdf.data.task.Task
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
 */
class GroupPickerDialog : DialogFragment() {

    // ── Public API ────────────────────────────────────────────────────────────

    /** Full sorted list of available groups (excluding the task being edited). */
    var allGroups: List<Task> = emptyList()

    /** Currently selected group id (null = root). Used to pre-tick the item. */
    var currentGroupId: String? = null

    /** Invoked on the main thread when the user taps an item. */
    var onGroupSelected: ((Task?) -> Unit)? = null

    // ── Views ─────────────────────────────────────────────────────────────────

    private lateinit var etSearch:      TextInputEditText
    private lateinit var rvItems:       RecyclerView
    private lateinit var adapter:       PickerAdapter

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext(), R.style.AppTheme_GroupPickerDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_group_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.tvPickerTitle).text = "Select Parent Group"
        view.findViewById<View>(R.id.btnPickerClose).setOnClickListener { dismiss() }

        etSearch = view.findViewById(R.id.etGroupSearch)
        rvItems  = view.findViewById(R.id.rvGroupItems)

        adapter = PickerAdapter(currentGroupId) { chosen ->
            RecentGroupPrefs.push(requireContext(), chosen?.id)
            onGroupSelected?.invoke(chosen)
            dismiss()
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
            items += PickerItem.Header("All Groups")
            items += PickerItem.Entry(null)            // "None (root level)"
            allGroups.forEach { items += PickerItem.Entry(it) }
        } else {
            // Filtered — no section headers, just matches
            val matched = allGroups.filter { it.name.contains(q, ignoreCase = true) }
            items += PickerItem.Entry(null)            // always keep "None" available
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
        private val selectedId: String?,
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
                tvName.text   = group?.name ?: "None (root level)"
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
