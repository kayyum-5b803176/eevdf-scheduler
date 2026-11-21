package com.eevdf.capabilities.grouppicker.output

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.capabilities.grouppicker.R
import com.eevdf.capabilities.grouppicker.state.GroupTaskPrefs
import com.eevdf.capabilities.grouppicker.state.RecentGroupPrefs
import com.eevdf.capabilities.taskstorage.AxisPath
import com.eevdf.capabilities.taskstorage.Task
import com.google.android.material.switchmaterial.SwitchMaterial
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

    // ── Path mode (optional, needs axes) ─────────────────────────────────────
    //
    // A switch in the title row turns the picker into: tap ANY axis, pick a group there (the normal parent
    // picker) — the deepest axis with a pick is the anchor and the axes above it become the route, listing
    // leaf nodes only and skippable ("any"). Taps tick instead of closing, each chip shows its pick, and Done
    // places the row ([AxisPath]). Off — the default — the picker behaves exactly as before.

    /** What each axis lists in path mode. Null = no path mode, no switch. */
    var pathSnapshot: AxisPath.Snapshot? = null

    /** The row being edited and everything inside it cannot be a pick. */
    var pathExclude: String? = null

    /** Invoked on Done with the picks (indexed by axis, null = nothing picked) and where the row goes. */
    var onPathChosen: ((picks: List<Task?>, placement: AxisPath.Placement) -> Unit)? = null

    private var viewedAxisId: String? = null
    private var pathMode = false
    private var picks: Array<AxisPath.Choice?> = emptyArray()
    private var viewedChoices: List<AxisPath.Choice> = emptyList()
    /** Said in the footer when a change dropped an earlier pick (a group cannot be a route entry, a leaf cannot be the group). */
    private var pathNote = ""
    private val axisChips = ArrayList<Chip>()
    private lateinit var footer: View
    private lateinit var tvPathSummary: TextView
    private lateinit var btnPathDone: View
    private lateinit var tvAxisHint: TextView

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
            if (pathMode) { onPathPick(chosen); return@PickerAdapter }
            RecentGroupPrefs.push(requireContext(), chosen?.id)
            val axis = viewedAxisId
            if (axisIds.isNotEmpty() && axis != null) onAxisGroupSelected?.invoke(axis, chosen)
            else onGroupSelected?.invoke(chosen)
            dismiss()
        }
        rvItems.layoutManager = LinearLayoutManager(requireContext())
        rvItems.adapter       = adapter
        // A long list must not push the path footer (Done) off screen.
        if (axisIds.isNotEmpty()) (rvItems as? MaxHeightRecyclerView)?.maxHeightPx = (resources.displayMetrics.heightPixels * 0.38f).toInt()
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
            axisChips += chip
            chip.id = View.generateViewId()
            chip.isChecked = axis == start
            // A row that cannot move can still be looked at on its own axis only.
            chip.isEnabled = !axisLocked || axis == currentAxisId
            chip.setOnCheckedChangeListener { _, checked -> if (checked) { showAxis(axis); etSearch.setText("") } }
            group.addView(chip)
        }
        tvAxisHint = hint
        if (axisLocked && axisLockedHint.isNotEmpty()) {
            hint.text = axisLockedHint
            hint.visibility = View.VISIBLE
        }
        setupPathMode(view)
    }

    // ── Path mode ─────────────────────────────────────────────────────────────

    private fun setupPathMode(view: View) {
        if (pathSnapshot == null) return
        picks = arrayOfNulls(axisIds.size)
        footer        = view.findViewById(R.id.layoutPickerPathFooter)
        tvPathSummary = view.findViewById(R.id.tvPickerPathSummary)
        btnPathDone   = view.findViewById(R.id.btnPickerPathDone)
        view.findViewById<View>(R.id.btnPickerPathCancel).setOnClickListener { dismiss() }
        btnPathDone.setOnClickListener {
            val result = AxisPath.resolve(axisIds, picks.toList())
            if (result is AxisPath.Result.Ok) {
                onPathChosen?.invoke(picks.map { it?.task }, result.placement)
                dismiss()
            }
        }
        val sw = view.findViewById<SwitchMaterial>(R.id.switchPickerPath)
        sw.visibility = View.VISIBLE
        sw.isEnabled = !axisLocked              // a row that cannot move cannot be re-placed either
        sw.setOnCheckedChangeListener { _, on ->
            pathMode = on
            footer.visibility = if (on) View.VISIBLE else View.GONE
            if (on) {
                picks.fill(null)
                pathNote = ""
                etSearch.clearFocus()
                (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(etSearch.windowToken, 0)
            }
            refreshChipLabels()
            etSearch.setText("")
            showAxis(viewedAxisId ?: axisIds.first())
            updateFooter()
        }
    }

    private fun refreshChipLabels() {
        axisChips.forEachIndexed { i, chip ->
            val pick = if (pathMode) picks.getOrNull(i) else null
            chip.text = axisIds[i].uppercase() + (pick?.let { " · ${it.task.name}" } ?: "")
        }
    }

    /**
     * A tap in path mode ticks the entry for the viewed axis (tap it again, or None, to clear) and keeps the dialog
     * open. Picks that can no longer stand are dropped ([AxisPath.normalize]) and said so in the footer. After a pick
     * the view moves to the axis ABOVE: that is the route, and the group axis has just changed which axes list leaves.
     */
    private fun onPathPick(chosen: Task?) {
        val axis = viewedAxisId ?: return
        val i = axisIds.indexOf(axis)
        val already = picks[i]?.task?.id
        picks[i] = if (chosen == null || chosen.id == already) null else viewedChoices.firstOrNull { it.task.id == chosen.id }
        val kept = AxisPath.normalize(axisIds, picks.toList())
        for (k in picks.indices) picks[k] = kept.picks[k]
        pathNote = if (kept.cleared.isEmpty()) "" else
            "Cleared ${kept.cleared.joinToString(", ") { it.uppercase() }}: a route entry must be a task, and the last pick must be a group."
        refreshChipLabels()
        updateFooter()
        if (picks[i] != null && i > 0) axisChips[i - 1].isChecked = true       // on to the route
        else showAxis(axis)
    }

    private fun updateFooter() {
        if (!pathMode) return
        val note = if (pathNote.isEmpty()) "" else pathNote + "\n"
        when (val r = AxisPath.resolve(axisIds, picks.toList())) {
            is AxisPath.Result.Ok -> {
                val p = r.placement
                tvPathSummary.text = note + if (p.route == null) "Placed in group ${p.labels.last()}, wherever it is included."
                                            else "Exists only in:  ${p.labels.joinToString(" › ")}"
                btnPathDone.isEnabled = true
            }
            is AxisPath.Result.Invalid -> {
                tvPathSummary.text = note + r.reason
                btnPathDone.isEnabled = false
            }
        }
    }

    /** List [axis]'s groups; the tick and the "none" wording follow which axis is being looked at. */
    private fun showAxis(axis: String) {
        viewedAxisId = axis
        val snapshot = pathSnapshot
        if (pathMode && snapshot != null) {
            val i = axisIds.indexOf(axis)
            viewedChoices = AxisPath.choices(axisIds, i, picks.toList(), snapshot, pathExclude)
            allGroups = viewedChoices.map { it.task }
            val groups = AxisPath.wantsGroups(i, picks.toList())
            adapter.selectedId = picks[i]?.task?.id          // None is ticked while nothing is picked here
            adapter.noneLabel  = if (groups) "None (no parent group)" else "None (any)"
            adapter.labeler    = null                       // never a mix: nothing needs marking
            // Only when there is nothing to pick: say why, in plain words.
            tvAxisHint.text = if (viewedChoices.isNotEmpty()) "" else
                if (groups) "No groups on ${axis.uppercase()}." else "No tasks on ${axis.uppercase()} to route through."
            tvAxisHint.visibility = if (viewedChoices.isEmpty()) View.VISIBLE else View.GONE
            applyFilter(etSearch.text?.toString() ?: "")
            return
        }
        adapter.labeler = null
        viewedChoices = emptyList()
        if (::tvAxisHint.isInitialized && !(axisLocked && axisLockedHint.isNotEmpty())) tvAxisHint.visibility = View.GONE
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
            val recentIds = if (pathMode) emptyList() else RecentGroupPrefs.getIds(requireContext())
            val recentGroups = recentIds.mapNotNull { id -> allGroups.find { it.id == id } }
            if (recentGroups.isNotEmpty()) {
                items += PickerItem.Header("Recent")
                recentGroups.forEach { items += PickerItem.Entry(it) }
                items += PickerItem.Divider
            }

            // All groups section
            items += PickerItem.Header(
                when {
                    pathMode -> {
                        val i = axisIds.indexOf(viewedAxisId)
                        if (i >= 0 && !AxisPath.wantsGroups(i, picks.toList())) "Route · ${viewedAxisId?.uppercase() ?: ""}"
                        else "Parent group · ${viewedAxisId?.uppercase() ?: ""}"
                    }
                    else     -> viewedAxisId?.let { "All Groups · ${it.uppercase()}" } ?: "All Groups"
                },
            )
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

        /** Optional text for an entry (path mode marks groups); null = just the name. */
        var labeler: ((Task) -> String)? = null

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
                tvName.text   = if (group == null) noneLabel else (labeler?.invoke(group) ?: group.name)
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
