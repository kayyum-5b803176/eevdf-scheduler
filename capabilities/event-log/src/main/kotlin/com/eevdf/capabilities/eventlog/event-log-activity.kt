package com.eevdf.capabilities.eventlog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
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
 * newest first, live-updating.
 *
 * Reads [EventBus.log] — a plain diagnostic feed of every [EventBus.publish]
 * call, not a [com.eevdf.kernel.eventbus.Topic] subscription — see this
 * capability's manifest.kt for why that isn't a rule-3 exception.
 *
 * FUTURE (not yet implemented, intentionally out of scope for v1):
 *  - Filtering the list by topic name.
 *  - Tap a row to expand/copy the full, untruncated payload.
 *  - Colour-coding rows by topic category (alarm / task / sync / …).
 *  - A "clear" action to reset the in-memory log.
 */
@AndroidEntryPoint
class EventLogActivity : AppCompatActivity() {

    @Inject lateinit var bus: EventBus

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private val adapter = EventLogAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_log)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Event Log"

        recyclerView = findViewById(R.id.rvEventLog)
        emptyView    = findViewById(R.id.tvEventLogEmpty)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // repeatOnLifecycle so collection pauses/resumes with the Activity
        // instead of leaking a live collector while backgrounded.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bus.log.collect { records ->
                    adapter.submit(records)
                    emptyView.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

private class EventLogAdapter : RecyclerView.Adapter<EventLogAdapter.VH>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var records: List<BusEventRecord> = emptyList()

    fun submit(newRecords: List<BusEventRecord>) {
        records = newRecords
        notifyDataSetChanged()
    }

    override fun getItemCount() = records.size

    override fun getItemId(position: Int) = records[position].id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_event_log_entry, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val record = records[position]
        holder.topic.text     = record.topicName
        holder.timestamp.text = timeFormat.format(Date(record.timestampMs))
        holder.payload.text   = record.payload.ifBlank { "(no payload)" }
    }

    init { setHasStableIds(true) }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val topic:     TextView = v.findViewById(R.id.tvEventLogTopic)
        val timestamp: TextView = v.findViewById(R.id.tvEventLogTimestamp)
        val payload:   TextView = v.findViewById(R.id.tvEventLogPayload)
    }
}
