package com.eevdf.capabilities.eventlog

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.kernel.eventbus.BusEventRecord
import com.eevdf.kernel.eventbus.EventBus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Read-only diagnostic screen: every event published on the kernel bus,
 * newest first, live-updating, with a multi-topic filter, expandable
 * payloads, and a way to clear the in-memory log.
 *
 * Every row shows who published it (`BusEventRecord.publisherId`, mandatory
 * on every [EventBus.publish] call) alongside the topic and payload — the
 * whole point of that field being mandatory rather than optional is that
 * this screen never has a row with an unexplained source. A
 * [com.eevdf.kernel.eventbus.RequestTopic] round trip (via
 * [EventBus.request]/[EventBus.respondTo]) shows up as two rows under the
 * same topic name: one from the requester ("→ …") and one from the
 * responder ("← …").
 *
 * Reads [EventBus.log] — a plain diagnostic feed of every [EventBus.publish]
 * call, not a [com.eevdf.kernel.eventbus.Topic] subscription — see this
 * capability's manifest.kt for why that isn't a rule-3 exception.
 *
 * FUTURE (not yet implemented, intentionally out of scope):
 *  - Colour-coding rows by topic category (alarm / task / sync / …).
 */
@AndroidEntryPoint
class EventLogActivity : AppCompatActivity() {

    @Inject lateinit var bus: EventBus

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var clearFilterView: TextView
    private lateinit var filterLabel: TextView
    private val adapter = EventLogAdapter(
        onRowClicked = { id -> toggleExpanded(id) },
        onCopyClicked = { payload -> copyToClipboard(payload) },
    )

    /** Latest snapshot from [EventBus.log] — the full, unfiltered history. */
    private var allRecords: List<BusEventRecord> = emptyList()

    /**
     * Empty means "All". Deliberately a distinct state rather than "every
     * known topic individually checked" — see [showFilterDialog] for how
     * the two get reconciled when they'd otherwise mean the same thing.
     */
    private val selectedTopics = mutableSetOf<String>()

    private val expandedIds = mutableSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_log)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Event Log"

        recyclerView    = findViewById(R.id.rvEventLog)
        emptyView       = findViewById(R.id.tvEventLogEmpty)
        clearFilterView = findViewById(R.id.tvEventLogClearFilter)
        filterLabel     = findViewById(R.id.tvEventLogFilterLabel)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.rowEventLogFilter).setOnClickListener { showFilterDialog() }
        clearFilterView.setOnClickListener {
            selectedTopics.clear()
            refresh()
        }

        // repeatOnLifecycle so collection pauses/resumes with the Activity
        // instead of leaking a live collector while backgrounded.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bus.log.collect { records ->
                    allRecords = records
                    // A topic the filter is currently pinned to can fall out
                    // of the log entirely (buffer eviction, or Clear log) —
                    // pruning it here is what makes "fall back to All when
                    // nothing's left for this topic" actually happen, rather
                    // than leaving the user stuck on a permanently-empty
                    // filtered view with no obvious way out.
                    val known = records.map { it.topicName }.toHashSet()
                    selectedTopics.retainAll(known)
                    refresh()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_event_log, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_clear_log -> { confirmClearLog(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── Clear log ────────────────────────────────────────────────────────────

    private fun confirmClearLog() {
        val count = allRecords.size
        AlertDialog.Builder(this)
            .setTitle("Clear event log?")
            .setMessage("This removes all $count recorded events from this screen. It can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                bus.clearLog()
                // bus.log's own emission (empty list) drives allRecords/refresh
                // via the collector above; clearing the filter and expanded
                // set here too so neither survives pointing at ids that no
                // longer exist.
                selectedTopics.clear()
                expandedIds.clear()
            }
            .show()
    }

    // ── Row expand/collapse ──────────────────────────────────────────────────

    private fun toggleExpanded(id: Long) {
        if (!expandedIds.add(id)) expandedIds.remove(id)
        refresh()
    }

    private fun copyToClipboard(payload: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Event payload", payload))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    // ── Filter dialog ────────────────────────────────────────────────────────

    /**
     * Topics offered here come from [allRecords] — what the log currently
     * holds, not a permanently-accumulating history — so a topic that
     * scrolled out of the (capacity-bounded) log stops being offered as a
     * filter option. This matches "Clear log" resetting the filter list to
     * empty too, since both read the same source.
     *
     * Selection semantics: "All" and the individual topic checks are
     * mutually exclusive states, not one implying the other — picking "All"
     * clears every individual pick, and picking any individual topic while
     * "All" is active replaces it with just that one topic (not "every
     * topic except this one"). If individually picking topics ever reaches
     * every known topic, that's collapsed back to the "All" state, since
     * the two are then visually and functionally identical.
     */
    private fun showFilterDialog() {
        val known = allRecords.map { it.topicName }.distinct().sorted()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_event_log_filter, null)
        val searchBox   = dialogView.findViewById<EditText>(R.id.etFilterSearch)
        val searchClear = dialogView.findViewById<ImageView>(R.id.ivFilterSearchClear)
        val list        = dialogView.findViewById<RecyclerView>(R.id.rvFilterTopics)

        lateinit var filterAdapter: FilterTopicAdapter
        filterAdapter = FilterTopicAdapter(
            allTopics = known,
            isAllSelected = { selectedTopics.isEmpty() },
            isTopicSelected = { topic -> topic in selectedTopics },
            onAllClicked = {
                selectedTopics.clear()
                filterAdapter.notifyDataSetChanged()
                refresh()
            },
            onTopicClicked = { topic ->
                if (selectedTopics.isEmpty()) {
                    // Coming from "All": tapping one topic means "just this
                    // one", not "every topic except this one".
                    selectedTopics.add(topic)
                } else if (!selectedTopics.remove(topic)) {
                    selectedTopics.add(topic)
                }
                if (selectedTopics.size == known.size && known.isNotEmpty()) {
                    selectedTopics.clear()
                }
                filterAdapter.notifyDataSetChanged()
                refresh()
            },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = filterAdapter

        searchBox.addTextChangedListener { text ->
            val query = text?.toString().orEmpty()
            searchClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            filterAdapter.setQuery(query)
        }
        searchClear.setOnClickListener { searchBox.setText("") }

        // Selection applies live as each row is tapped (see onAllClicked /
        // onTopicClicked above) rather than needing a separate Apply step —
        // simpler state to reason about than tracking pending vs. applied
        // selections, and the list underneath re-filtering immediately is
        // itself useful feedback while picking.
        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Done", null)
            .show()
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private fun refresh() {
        val visible = if (selectedTopics.isEmpty()) {
            allRecords
        } else {
            allRecords.filter { it.topicName in selectedTopics }
        }
        adapter.submit(visible.map { EventLogRow(it, expanded = it.id in expandedIds) })

        filterLabel.text = when {
            selectedTopics.isEmpty() -> "Topic: All"
            selectedTopics.size == 1 -> "Topic: ${selectedTopics.first()}"
            else                     -> "Topic: ${selectedTopics.size} selected"
        }

        val noneAtAll     = allRecords.isEmpty()
        val filteredEmpty = visible.isEmpty() && !noneAtAll
        emptyView.visibility = if (noneAtAll || filteredEmpty) View.VISIBLE else View.GONE
        emptyView.text = if (noneAtAll) "No events yet" else "No events for this topic"
        clearFilterView.visibility = if (filteredEmpty) View.VISIBLE else View.GONE
    }
}

// ── Event list ───────────────────────────────────────────────────────────────

private data class EventLogRow(val record: BusEventRecord, val expanded: Boolean)

private class EventLogAdapter(
    private val onRowClicked: (Long) -> Unit,
    private val onCopyClicked: (String) -> Unit,
) : RecyclerView.Adapter<EventLogAdapter.VH>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var rows: List<EventLogRow> = emptyList()

    fun submit(newRows: List<EventLogRow>) {
        val diff = DiffUtil.calculateDiff(RowDiff(rows, newRows))
        rows = newRows
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = rows.size
    override fun getItemId(position: Int) = rows[position].record.id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_event_log_entry, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.topic.text        = row.record.topicName
        holder.timestamp.text    = timeFormat.format(Date(row.record.timestampMs))
        holder.publisher.text    = "by ${row.record.publisherId}"
        holder.payload.text      = row.record.payload.ifBlank { "(no payload)" }
        holder.payload.maxLines  = if (row.expanded) Int.MAX_VALUE else 2
        holder.copyRow.visibility = if (row.expanded) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onRowClicked(row.record.id) }
        holder.copyText.setOnClickListener { onCopyClicked(row.record.payload) }
    }

    init { setHasStableIds(true) }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val topic:     TextView = v.findViewById(R.id.tvEventLogTopic)
        val timestamp: TextView = v.findViewById(R.id.tvEventLogTimestamp)
        val publisher: TextView = v.findViewById(R.id.tvEventLogPublisher)
        val payload:   TextView = v.findViewById(R.id.tvEventLogPayload)
        val copyRow:   View     = v.findViewById(R.id.rowEventLogCopy)
        val copyText:  TextView = v.findViewById(R.id.tvEventLogCopy)
    }

    private class RowDiff(
        private val old: List<EventLogRow>,
        private val new: List<EventLogRow>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) =
            old[oldPos].record.id == new[newPos].record.id
        override fun areContentsTheSame(oldPos: Int, newPos: Int) =
            old[oldPos] == new[newPos]
    }
}

// ── Filter dialog's topic list ────────────────────────────────────────────────

private class FilterTopicAdapter(
    private val allTopics: List<String>,
    private val isAllSelected: () -> Boolean,
    private val isTopicSelected: (String) -> Boolean,
    private val onAllClicked: () -> Unit,
    private val onTopicClicked: (String) -> Unit,
) : RecyclerView.Adapter<FilterTopicAdapter.TopicVH>() {

    private var visibleTopics: List<String> = allTopics

    fun setQuery(query: String) {
        visibleTopics = if (query.isBlank()) {
            allTopics
        } else {
            allTopics.filter { it.contains(query, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }

    // "All" is always shown, even mid-search — searching narrows individual
    // topics, not the shortcut back to everything.
    override fun getItemCount() = 1 + visibleTopics.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        TopicVH(LayoutInflater.from(parent.context).inflate(R.layout.item_event_log_filter_topic, parent, false))

    override fun onBindViewHolder(holder: TopicVH, position: Int) {
        if (position == 0) {
            holder.name.text = "All"
            holder.indicator.setImageResource(
                if (isAllSelected()) R.drawable.ic_circle_checked else R.drawable.ic_circle_unchecked
            )
            holder.itemView.setOnClickListener { onAllClicked() }
        } else {
            val topic = visibleTopics[position - 1]
            holder.name.text = topic
            holder.indicator.setImageResource(
                if (isTopicSelected(topic)) R.drawable.ic_circle_checked else R.drawable.ic_circle_unchecked
            )
            holder.itemView.setOnClickListener { onTopicClicked(topic) }
        }
    }

    class TopicVH(v: View) : RecyclerView.ViewHolder(v) {
        val indicator: ImageView = v.findViewById(R.id.ivFilterTopicIndicator)
        val name:       TextView = v.findViewById(R.id.tvFilterTopicName)
    }
}
