package com.eevdf.scheduler.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.eevdf.scheduler.R
import com.eevdf.scheduler.db.TaskDatabase
import com.eevdf.scheduler.model.RunDailySummary
import com.eevdf.scheduler.model.RunLogEntry
import com.eevdf.scheduler.model.Task
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class StatsCalendarFragment : Fragment() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var btnPrevMonth:    MaterialButton
    private lateinit var btnNextMonth:    MaterialButton
    private lateinit var tvMonthTitle:    TextView
    private lateinit var llCalHeader:     LinearLayout
    private lateinit var llCalGrid:       LinearLayout
    private lateinit var llLegend:        LinearLayout
    private lateinit var cardDayDetail:   CardView
    private lateinit var tvDetailDate:    TextView
    private lateinit var llDetailSessions:LinearLayout
    private lateinit var tvDetailEmpty:   TextView

    // ── State ─────────────────────────────────────────────────────────────────
    /** Calendar pointing to the first day of the currently displayed month (UTC). */
    private val displayCal: Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    /**
     * Loaded once per data-reload:
     *   key = startOfDay(epoch ms)
     *   value = list of (taskId, durationSecs, startEpochMs)
     */
    private data class SessionSummary(val taskId: String, val durationSecs: Long, val startMs: Long)
    private var daySessionMap: Map<Long, List<SessionSummary>> = emptyMap()
    private var taskById:      Map<String, Task>               = emptyMap()

    /** Fixed palette: up to 10 tasks get a color, rest use grey. */
    private val palette = listOf(
        "#1565C0", "#E65100", "#1B5E20", "#880E4F", "#006064",
        "#4A148C", "#F57F17", "#37474F", "#B71C1C", "#33691E"
    )
    /** taskId → color string (populated after data loads) */
    private var taskColorMap: Map<String, String> = emptyMap()

    // ── Formatters ────────────────────────────────────────────────────────────
    private val monthFmt    = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val timeFmt     = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }
    private val detailFmt   = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_stats_calendar, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        buildDayOfWeekHeader()

        btnPrevMonth.setOnClickListener {
            displayCal.add(Calendar.MONTH, -1)
            renderCalendar()
        }
        btnNextMonth.setOnClickListener {
            // Don't go beyond current month
            val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            if (displayCal.get(Calendar.YEAR) < now.get(Calendar.YEAR) ||
                (displayCal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                 displayCal.get(Calendar.MONTH) < now.get(Calendar.MONTH))) {
                displayCal.add(Calendar.MONTH, 1)
                renderCalendar()
            }
        }
        loadData()
    }

    private fun bindViews(v: View) {
        btnPrevMonth     = v.findViewById(R.id.btnPrevMonth)
        btnNextMonth     = v.findViewById(R.id.btnNextMonth)
        tvMonthTitle     = v.findViewById(R.id.tvMonthTitle)
        llCalHeader      = v.findViewById(R.id.llCalHeader)
        llCalGrid        = v.findViewById(R.id.llCalGrid)
        llLegend         = v.findViewById(R.id.llLegend)
        cardDayDetail    = v.findViewById(R.id.cardDayDetail)
        tvDetailDate     = v.findViewById(R.id.tvDetailDate)
        llDetailSessions = v.findViewById(R.id.llDetailSessions)
        tvDetailEmpty    = v.findViewById(R.id.tvDetailEmpty)
    }

    // ── Data Loading ──────────────────────────────────────────────────────────

    private fun loadData() {
        val db  = TaskDatabase.getDatabase(requireContext())
        val rld = db.runLogDao()
        val td  = db.taskDao()

        viewLifecycleOwner.lifecycleScope.launch {
            val nowMs = System.currentTimeMillis()

            val tasks      = withContext(Dispatchers.IO) { td.getAllTasksForStats() }
            val logEntries = withContext(Dispatchers.IO) { rld.getEntriesInRange(0L, nowMs) }
            val dailyRows  = withContext(Dispatchers.IO) { rld.getDailyInRange(0L) }

            taskById = tasks.associateBy { it.id }

            // Build color map from tasks sorted by total run time (most active = most prominent color)
            val activeTasks = tasks.filter { !it.isGroup && it.runCount > 0 }
                .sortedByDescending { it.totalRunTime }
            taskColorMap = activeTasks.mapIndexed { i, t ->
                t.id to (if (i < palette.size) palette[i] else "#9E9E9E")
            }.toMap()

            // Merge log entries + daily rows into daySessionMap
            val utc   = TimeZone.getTimeZone("UTC")
            val map   = mutableMapOf<Long, MutableList<SessionSummary>>()

            fun sod(ms: Long): Long {
                val c = Calendar.getInstance(utc).also { c -> c.timeInMillis = ms }
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
                c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                return c.timeInMillis
            }

            // Recent raw log entries — each is one session with a real start time
            logEntries.forEach { e ->
                val key = sod(e.startEpoch)
                map.getOrPut(key) { mutableListOf() }
                    .add(SessionSummary(e.taskId, e.durationSecs, e.startEpoch))
            }

            // Compacted daily summaries — we don't have per-session start times,
            // so synthesise one entry per (task, day) with dayEpoch as startMs
            dailyRows.forEach { d ->
                val key = sod(d.dayEpoch)
                // Only add if there's no raw entry for this (task, day) pair already
                val existing = map[key]
                if (existing == null || existing.none { it.taskId == d.taskId }) {
                    map.getOrPut(key) { mutableListOf() }
                        .add(SessionSummary(d.taskId, d.totalSecs, d.dayEpoch))
                }
            }

            daySessionMap = map
            renderCalendar()
            renderLegend()
        }
    }

    // ── Calendar Rendering ────────────────────────────────────────────────────

    private fun buildDayOfWeekHeader() {
        llCalHeader.removeAllViews()
        val days = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
        days.forEach { d ->
            llCalHeader.addView(makeDayHeaderCell(d))
        }
    }

    private fun renderCalendar() {
        tvMonthTitle.text = monthFmt.format(displayCal.time)
        llCalGrid.removeAllViews()
        cardDayDetail.visibility = View.GONE

        val utc = TimeZone.getTimeZone("UTC")

        // Clone display cal to iterate
        val cal = displayCal.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)

        // Day-of-week of first day (Sun=1 … Sat=7), convert to 0-based offset
        val firstDow = cal.get(Calendar.DAY_OF_WEEK) - 1  // 0=Sun … 6=Sat
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        val nowCal = Calendar.getInstance(utc)
        val todayYear  = nowCal.get(Calendar.YEAR)
        val todayMonth = nowCal.get(Calendar.MONTH)
        val todayDay   = nowCal.get(Calendar.DAY_OF_MONTH)

        val displayYear  = displayCal.get(Calendar.YEAR)
        val displayMonth = displayCal.get(Calendar.MONTH)

        // Build rows of 7 cells
        var row: LinearLayout? = null
        val totalCells = firstDow + daysInMonth
        val numRows    = (totalCells + 6) / 7

        for (cellIdx in 0 until numRows * 7) {
            if (cellIdx % 7 == 0) {
                row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                llCalGrid.addView(row)
            }
            val dayNumber = cellIdx - firstDow + 1
            val isValid   = dayNumber in 1..daysInMonth
            val isToday   = isValid &&
                displayYear == todayYear && displayMonth == todayMonth && dayNumber == todayDay
            val isFuture  = isValid && run {
                val c = Calendar.getInstance(utc).also { it.timeInMillis = displayCal.timeInMillis }
                c.set(Calendar.DAY_OF_MONTH, dayNumber)
                c.after(nowCal)
            }

            // Get sessions for this day
            val dayEpoch: Long = if (isValid) {
                val c = Calendar.getInstance(utc).also { it.timeInMillis = displayCal.timeInMillis }
                c.set(Calendar.DAY_OF_MONTH, dayNumber)
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
                c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                c.timeInMillis
            } else 0L

            val sessions = if (isValid && !isFuture) daySessionMap[dayEpoch] ?: emptyList() else emptyList()

            val cell = makeDayCell(
                dayNumber  = if (isValid) dayNumber else 0,
                sessions   = sessions,
                isToday    = isToday,
                isEmpty    = !isValid,
                isFuture   = isFuture
            )

            if (isValid && !isFuture) {
                cell.setOnClickListener { showDayDetail(dayEpoch, dayNumber, sessions) }
            }
            row?.addView(cell)
        }
    }

    /**
     * Creates one calendar cell (48dp × 56dp).
     * Shows the day number + up to 3 small color dots representing tasks that ran.
     */
    private fun makeDayCell(
        dayNumber: Int, sessions: List<SessionSummary>,
        isToday: Boolean, isEmpty: Boolean, isFuture: Boolean
    ): View {
        val dp  = resources.displayMetrics.density
        val ctx = requireContext()

        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, (56 * dp).toInt(), 1f).also {
                it.setMargins((1 * dp).toInt(), (1 * dp).toInt(), (1 * dp).toInt(), (1 * dp).toInt())
            }
            if (isToday) setBackgroundColor(Color.parseColor("#E3F2FD"))
        }

        if (isEmpty) return outer   // blank cell for padding

        // Day number label
        val tvDay = TextView(ctx).apply {
            text      = "$dayNumber"
            textSize  = 12f
            gravity   = Gravity.CENTER
            setTextColor(when {
                isToday  -> Color.parseColor("#1565C0")
                isFuture -> Color.parseColor("#BDBDBD")
                else     -> Color.parseColor("#212121")
            })
            if (isToday) setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (24 * dp).toInt()
            )
        }
        outer.addView(tvDay)

        // Up to 3 task color dots
        if (sessions.isNotEmpty() && !isFuture) {
            val dotRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (12 * dp).toInt()
                ).also { it.topMargin = (2 * dp).toInt() }
            }
            val dotSize   = (7 * dp).toInt()
            val dotMargin = (2 * dp).toInt()
            // Unique task IDs in this day, prioritised by total duration
            val uniqueTasks = sessions.groupBy { it.taskId }
                .entries.sortedByDescending { (_, s) -> s.sumOf { it.durationSecs } }
                .map { it.key }
                .take(3)
            uniqueTasks.forEach { taskId ->
                val colorStr = taskColorMap[taskId] ?: "#9E9E9E"
                val dot = View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).also {
                        it.marginEnd = dotMargin
                    }
                    background = ctx.getDrawable(android.R.drawable.presence_online)
                        ?: View(ctx).apply { setBackgroundColor(Color.parseColor(colorStr)) }.background
                    setBackgroundColor(Color.parseColor(colorStr))
                    // Make a circle via clipToOutline
                    val shape = android.graphics.drawable.ShapeDrawable(
                        android.graphics.drawable.shapes.OvalShape()
                    ).also { s -> s.paint.color = Color.parseColor(colorStr) }
                    background = shape
                }
                dotRow.addView(dot)
            }
            // If more tasks than shown, add a small "+" indicator
            if (uniqueTasks.size < sessions.groupBy { it.taskId }.size) {
                dotRow.addView(TextView(ctx).apply {
                    text     = "+"
                    textSize = 7f
                    setTextColor(Color.parseColor("#757575"))
                    gravity  = Gravity.CENTER
                })
            }
            outer.addView(dotRow)
        }

        return outer
    }

    private fun makeDayHeaderCell(label: String): TextView {
        val dp = resources.displayMetrics.density
        return TextView(requireContext()).apply {
            text      = label
            textSize  = 11f
            gravity   = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#757575"))
            layoutParams = LinearLayout.LayoutParams(0, (32 * dp).toInt(), 1f)
        }
    }

    // ── Day Detail ────────────────────────────────────────────────────────────

    private fun showDayDetail(dayEpoch: Long, dayNumber: Int, sessions: List<SessionSummary>) {
        cardDayDetail.visibility = View.VISIBLE
        tvDetailDate.text = detailFmt.format(Date(dayEpoch))

        llDetailSessions.removeAllViews()

        if (sessions.isEmpty()) {
            tvDetailEmpty.visibility    = View.VISIBLE
            llDetailSessions.visibility = View.GONE
            return
        }
        tvDetailEmpty.visibility    = View.GONE
        llDetailSessions.visibility = View.VISIBLE

        // Group by task, sort by total duration desc
        sessions.groupBy { it.taskId }
            .entries.sortedByDescending { (_, s) -> s.sumOf { it.durationSecs } }
            .forEach { (taskId, taskSessions) ->
                val task      = taskById[taskId]
                val taskName  = task?.name ?: "[deleted]"
                val totalSecs = taskSessions.sumOf { it.durationSecs }
                val color     = taskColorMap[taskId] ?: "#9E9E9E"

                llDetailSessions.addView(makeDetailTaskRow(taskName, totalSecs, color))

                // Show individual sessions if more than one
                if (taskSessions.size > 1) {
                    taskSessions.sortedBy { it.startMs }.forEach { s ->
                        llDetailSessions.addView(makeDetailSessionRow(s))
                    }
                } else {
                    taskSessions.forEach { s ->
                        llDetailSessions.addView(makeDetailSessionRow(s))
                    }
                }
            }

        // Scroll to detail card — walk up until we find the NestedScrollView
        cardDayDetail.post {
            var v: View? = cardDayDetail.parent as? View
            while (v != null && v !is androidx.core.widget.NestedScrollView) {
                v = v.parent as? View
            }
            (v as? androidx.core.widget.NestedScrollView)?.smoothScrollTo(0, cardDayDetail.bottom)
        }
    }

    private fun makeDetailTaskRow(name: String, totalSecs: Long, colorHex: String): View {
        val dp  = resources.displayMetrics.density
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (4 * dp).toInt(); it.topMargin = (8 * dp).toInt() }
        }
        // Color swatch
        row.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((12 * dp).toInt(), (12 * dp).toInt()).also {
                it.marginEnd = (8 * dp).toInt()
            }
            val shape = android.graphics.drawable.ShapeDrawable(
                android.graphics.drawable.shapes.OvalShape()
            ).also { s -> s.paint.color = Color.parseColor(colorHex) }
            background = shape
        })
        row.addView(TextView(ctx).apply {
            text = name; textSize = 14f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(ctx).apply {
            text = formatDur(totalSecs); textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(colorHex))
        })
        return row
    }

    private fun makeDetailSessionRow(s: SessionSummary): View {
        val dp  = resources.displayMetrics.density
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (3 * dp).toInt(); it.marginStart = (20 * dp).toInt() }
        }
        val startStr = if (s.startMs > 86_400_000L * 2) timeFmt.format(Date(s.startMs)) else "--:--"
        row.addView(TextView(ctx).apply {
            text = "⏱ $startStr"; textSize = 11f
            setTextColor(Color.parseColor("#757575"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(ctx).apply {
            text = formatDur(s.durationSecs); textSize = 11f
            setTextColor(Color.parseColor("#9E9E9E"))
        })
        return row
    }

    // ── Legend ────────────────────────────────────────────────────────────────

    private fun renderLegend() {
        llLegend.removeAllViews()
        val dp  = resources.displayMetrics.density
        val ctx = requireContext()

        if (taskColorMap.isEmpty()) {
            llLegend.addView(TextView(ctx).apply {
                text = "No task data yet"; textSize = 12f
                setTextColor(Color.parseColor("#BDBDBD"))
            })
            return
        }

        // Show up to 10 tasks in legend
        taskColorMap.entries.take(10).forEach { (taskId, colorHex) ->
            val taskName = taskById[taskId]?.name ?: "[deleted]"
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (6 * dp).toInt() }
            }
            // Color dot
            row.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((10 * dp).toInt(), (10 * dp).toInt()).also {
                    it.marginEnd = (8 * dp).toInt()
                }
                val shape = android.graphics.drawable.ShapeDrawable(
                    android.graphics.drawable.shapes.OvalShape()
                ).also { s -> s.paint.color = Color.parseColor(colorHex) }
                background = shape
            })
            row.addView(TextView(ctx).apply {
                text = taskName; textSize = 12f
                setTextColor(Color.parseColor("#424242"))
            })
            llLegend.addView(row)
        }

        if (taskColorMap.size > 10) {
            llLegend.addView(TextView(ctx).apply {
                text = "+ ${taskColorMap.size - 10} more tasks (shown in grey)"
                textSize = 11f; setTextColor(Color.parseColor("#9E9E9E"))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (4 * dp).toInt()
                layoutParams = lp
            })
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatDur(s: Long): String {
        if (s <= 0L) return "0s"
        var r = s
        val d = r / 86_400L; r %= 86_400L
        val h = r / 3_600L;  r %= 3_600L
        val m = r / 60L;     val sec = r % 60L
        return buildList {
            if (d > 0) add("${d}d"); if (h > 0) add("${h}h")
            if (m > 0) add("${m}m"); if (sec > 0 && d == 0L) add("${sec}s")
        }.take(2).joinToString(" ").ifEmpty { "0s" }
    }
}
