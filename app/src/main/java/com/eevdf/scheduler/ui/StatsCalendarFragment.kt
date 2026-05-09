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
    private lateinit var btnPrevMonth:     MaterialButton
    private lateinit var btnNextMonth:     MaterialButton
    private lateinit var tvMonthTitle:     TextView
    private lateinit var llCalHeader:      LinearLayout
    private lateinit var llCalGrid:        LinearLayout
    private lateinit var cardDayDetail:    CardView
    private lateinit var tvDetailDate:     TextView
    private lateinit var tvDetailMode:     TextView
    private lateinit var llDetailSessions: LinearLayout
    private lateinit var tvDetailEmpty:    TextView

    // ── Timezone — LOCAL throughout (user sees local time) ────────────────────
    private val localTz: TimeZone = TimeZone.getDefault()

    // ── Display calendar anchored to 1st of month in LOCAL tz ────────────────
    private val displayCal: Calendar = Calendar.getInstance(TimeZone.getDefault()).apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
    }

    /**
     * One timed segment that belongs to exactly one calendar day.
     * Sessions crossing midnight are split: each piece becomes its own SessionSummary.
     */
    private data class SessionSummary(
        val taskId:       String,
        val durationSecs: Long,
        val startMs:      Long,       // wall-clock start of THIS segment
        val isFromRunLog: Boolean     // true = has a real HH:mm; false = compacted daily row
    )

    /** key = startOfLocalDay epoch-ms */
    private var daySessionMap: Map<Long, List<SessionSummary>> = emptyMap()
    private var taskById:      Map<String, Task>               = emptyMap()

    private val palette = listOf(
        "#1565C0", "#E65100", "#1B5E20", "#880E4F", "#006064",
        "#4A148C", "#F57F17", "#37474F", "#B71C1C", "#33691E"
    )
    private var taskColorMap: Map<String, String> = emptyMap()

    // ── Formatters ────────────────────────────────────────────────────────────
    private val monthFmt  = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val detailFmt = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())
    private val timeFmt   = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }

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
            val now = Calendar.getInstance(localTz)
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
        cardDayDetail    = v.findViewById(R.id.cardDayDetail)
        tvDetailDate     = v.findViewById(R.id.tvDetailDate)
        tvDetailMode     = v.findViewById(R.id.tvDetailMode)
        llDetailSessions = v.findViewById(R.id.llDetailSessions)
        tvDetailEmpty    = v.findViewById(R.id.tvDetailEmpty)
    }

    // ── Data Loading ──────────────────────────────────────────────────────────

    private fun loadData() {
        val db  = TaskDatabase.getDatabase(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val nowMs      = System.currentTimeMillis()
            val tasks      = withContext(Dispatchers.IO) { db.taskDao().getAllTasksForStats() }
            val logEntries = withContext(Dispatchers.IO) { db.runLogDao().getEntriesInRange(0L, nowMs) }
            val dailyRows  = withContext(Dispatchers.IO) { db.runLogDao().getDailyInRange(0L) }

            taskById = tasks.associateBy { it.id }

            val activeTasks = tasks.filter { !it.isGroup && it.runCount > 0 }
                .sortedByDescending { it.totalRunTime }
            taskColorMap = activeTasks.mapIndexed { i, t ->
                t.id to if (i < palette.size) palette[i] else "#9E9E9E"
            }.toMap()

            daySessionMap = buildDaySessionMap(logEntries, dailyRows)
            renderCalendar()
        }
    }

    /**
     * Build the per-day session map with midnight-boundary splitting.
     *
     * Rule: data belongs to the LOCAL calendar day that it occurred in.
     * Example: session 23:00→01:00 → 1 h on day D, 1 h on day D+1.
     */
    private fun buildDaySessionMap(
        logEntries: List<RunLogEntry>,
        dailyRows:  List<RunDailySummary>
    ): Map<Long, List<SessionSummary>> {

        val map = mutableMapOf<Long, MutableList<SessionSummary>>()

        // 1. Midnight-split every raw run_log entry
        for (e in logEntries) {
            for (seg in splitAtMidnight(e.taskId, e.startEpoch, e.durationSecs)) {
                map.getOrPut(seg.dayEpoch) { mutableListOf() }
                    .add(SessionSummary(seg.taskId, seg.segSecs, seg.startMs, isFromRunLog = true))
            }
        }

        // 2. Compacted daily rows — only fill (task, day) pairs not already covered
        val covered = map.flatMap { (day, segs) -> segs.map { it.taskId to day } }.toHashSet()
        for (d in dailyRows) {
            val localDay = startOfLocalDay(d.dayEpoch)
            if ((d.taskId to localDay) !in covered) {
                map.getOrPut(localDay) { mutableListOf() }
                    .add(SessionSummary(d.taskId, d.totalSecs, localDay, isFromRunLog = false))
            }
        }

        return map
    }

    // ── Midnight-split ────────────────────────────────────────────────────────

    private data class Seg(
        val dayEpoch: Long,      // startOfLocalDay for this piece
        val taskId:   String,
        val segSecs:  Long,
        val startMs:  Long       // actual wall-clock start of this piece
    )

    private fun splitAtMidnight(taskId: String, startMs: Long, durationSecs: Long): List<Seg> {
        if (durationSecs <= 0L) return emptyList()
        val endMs  = startMs + durationSecs * 1_000L
        val result = mutableListOf<Seg>()
        var curMs  = startMs
        while (curMs < endMs) {
            val dayEpoch     = startOfLocalDay(curMs)
            val nextMidnight = dayEpoch + 86_400_000L
            val segEndMs     = minOf(endMs, nextMidnight)
            val segSecs      = (segEndMs - curMs) / 1_000L
            if (segSecs > 0L) result.add(Seg(dayEpoch, taskId, segSecs, curMs))
            curMs = nextMidnight
        }
        return result
    }

    private fun startOfLocalDay(ms: Long): Long {
        val c = Calendar.getInstance(localTz)
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0);      c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ── Calendar Rendering ────────────────────────────────────────────────────

    private fun buildDayOfWeekHeader() {
        llCalHeader.removeAllViews()
        val dp = resources.displayMetrics.density
        listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa").forEach { d ->
            llCalHeader.addView(TextView(requireContext()).apply {
                text = d; textSize = 9f; gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#9E9E9E"))
                layoutParams = LinearLayout.LayoutParams(0, (18 * dp).toInt(), 1f)
            })
        }
    }

    private fun renderCalendar() {
        tvMonthTitle.text = monthFmt.format(displayCal.time)
        llCalGrid.removeAllViews()
        cardDayDetail.visibility = View.GONE

        val nowCal     = Calendar.getInstance(localTz)
        val todayYear  = nowCal.get(Calendar.YEAR)
        val todayMonth = nowCal.get(Calendar.MONTH)
        val todayDay   = nowCal.get(Calendar.DAY_OF_MONTH)
        val dispYear   = displayCal.get(Calendar.YEAR)
        val dispMonth  = displayCal.get(Calendar.MONTH)

        val cal = Calendar.getInstance(localTz).also {
            it.set(dispYear, dispMonth, 1, 0, 0, 0); it.set(Calendar.MILLISECOND, 0)
        }
        val firstDow    = cal.get(Calendar.DAY_OF_WEEK) - 1
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val numRows     = (firstDow + daysInMonth + 6) / 7

        var row: LinearLayout? = null
        for (cellIdx in 0 until numRows * 7) {
            if (cellIdx % 7 == 0) {
                row = LinearLayout(requireContext()).apply {
                    orientation  = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                llCalGrid.addView(row)
            }

            val dayNum  = cellIdx - firstDow + 1
            val isValid = dayNum in 1..daysInMonth
            val isToday = isValid && dispYear == todayYear && dispMonth == todayMonth && dayNum == todayDay
            val isFuture = isValid && Calendar.getInstance(localTz).let {
                it.set(dispYear, dispMonth, dayNum, 0, 0, 0); it.set(Calendar.MILLISECOND, 0)
                it.after(nowCal)
            }

            val dayEpoch: Long = if (isValid) Calendar.getInstance(localTz).let {
                it.set(dispYear, dispMonth, dayNum, 0, 0, 0); it.set(Calendar.MILLISECOND, 0)
                it.timeInMillis
            } else 0L

            val sessions = if (isValid && !isFuture) daySessionMap[dayEpoch] ?: emptyList()
                           else emptyList()

            val cell = makeDayCell(
                dayNumber = if (isValid) dayNum else 0,
                sessions  = sessions,
                isToday   = isToday,
                isEmpty   = !isValid,
                isFuture  = isFuture
            )

            if (isValid && !isFuture) {
                cell.setOnClickListener         { showDayDetail(dayEpoch, sessions, showRunLog = false) }
                cell.setOnLongClickListener     { showDayDetail(dayEpoch, sessions, showRunLog = true); true }
            }
            row?.addView(cell)
        }
    }

    /** Compact 36 dp cell: day number + up to 3 colour dots. */
    private fun makeDayCell(
        dayNumber: Int, sessions: List<SessionSummary>,
        isToday: Boolean, isEmpty: Boolean, isFuture: Boolean
    ): View {
        val dp  = resources.displayMetrics.density
        val ctx = requireContext()

        val outer = LinearLayout(ctx).apply {
            orientation  = LinearLayout.VERTICAL
            gravity      = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, (36 * dp).toInt(), 1f).also {
                it.setMargins((1 * dp).toInt(), (1 * dp).toInt(), (1 * dp).toInt(), (1 * dp).toInt())
            }
            if (isToday) setBackgroundColor(Color.parseColor("#E3F2FD"))
        }
        if (isEmpty) return outer

        outer.addView(TextView(ctx).apply {
            text = "$dayNumber"; textSize = 10f; gravity = Gravity.CENTER
            setTextColor(when {
                isToday  -> Color.parseColor("#1565C0")
                isFuture -> Color.parseColor("#BDBDBD")
                else     -> Color.parseColor("#212121")
            })
            if (isToday) setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (20 * dp).toInt())
        })

        if (sessions.isNotEmpty() && !isFuture) {
            val dotRow = LinearLayout(ctx).apply {
                orientation  = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, (8 * dp).toInt()
                ).also { it.topMargin = (2 * dp).toInt() }
            }
            val dotPx = (5 * dp).toInt(); val gapPx = (2 * dp).toInt()
            val uniqueTasks = sessions
                .groupBy { it.taskId }
                .entries.sortedByDescending { (_, s) -> s.sumOf { it.durationSecs } }
                .map { it.key }
            uniqueTasks.take(3).forEach { taskId ->
                val shape = android.graphics.drawable.ShapeDrawable(
                    android.graphics.drawable.shapes.OvalShape()
                ).also { s -> s.paint.color = Color.parseColor(taskColorMap[taskId] ?: "#9E9E9E") }
                dotRow.addView(View(ctx).apply {
                    background   = shape
                    layoutParams = LinearLayout.LayoutParams(dotPx, dotPx).also { it.marginEnd = gapPx }
                })
            }
            if (uniqueTasks.size > 3) dotRow.addView(TextView(ctx).apply {
                text = "+"; textSize = 6f
                setTextColor(Color.parseColor("#9E9E9E")); gravity = Gravity.CENTER
            })
            outer.addView(dotRow)
        }
        return outer
    }

    // ── Day Detail Panel ──────────────────────────────────────────────────────

    /**
     * [showRunLog] = false (tap)       → task name + total for the day
     * [showRunLog] = true  (long-press) → above + indented "• HH:mm  (dur)" for each segment
     */
    private fun showDayDetail(dayEpoch: Long, sessions: List<SessionSummary>, showRunLog: Boolean) {
        cardDayDetail.visibility = View.VISIBLE
        tvDetailDate.text = detailFmt.format(Date(dayEpoch))
        tvDetailMode.text = if (showRunLog) "run times" else "overview"

        llDetailSessions.removeAllViews()

        if (sessions.isEmpty()) {
            tvDetailEmpty.visibility    = View.VISIBLE
            llDetailSessions.visibility = View.GONE
            scrollToDetail(); return
        }
        tvDetailEmpty.visibility    = View.GONE
        llDetailSessions.visibility = View.VISIBLE

        // Per-task, ordered by total duration desc
        sessions.groupBy { it.taskId }
            .entries.sortedByDescending { (_, s) -> s.sumOf { it.durationSecs } }
            .forEach { (taskId, taskSessions) ->
                val name      = taskById[taskId]?.name ?: "[deleted]"
                val color     = taskColorMap[taskId] ?: "#9E9E9E"
                val totalSecs = taskSessions.sumOf { it.durationSecs }

                // Summary line — always shown
                llDetailSessions.addView(makeSummaryRow(name, totalSecs, color))

                // Run-log bullet lines — only in long-press mode, only for raw entries
                if (showRunLog) {
                    val segs = taskSessions.filter { it.isFromRunLog }.sortedBy { it.startMs }
                    if (segs.isNotEmpty()) llDetailSessions.addView(makeRunLogBullets(segs))
                }
            }

        scrollToDetail()
    }

    /** [●] Task Name ─────────── 2h 30m */
    private fun makeSummaryRow(name: String, totalSecs: Long, colorHex: String): View {
        val dp  = resources.displayMetrics.density
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation  = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (4 * dp).toInt() }
        }
        val dotPx = (10 * dp).toInt()
        val shape = android.graphics.drawable.ShapeDrawable(
            android.graphics.drawable.shapes.OvalShape()
        ).also { s -> s.paint.color = Color.parseColor(colorHex) }
        row.addView(View(ctx).apply {
            background   = shape
            layoutParams = LinearLayout.LayoutParams(dotPx, dotPx).also { it.marginEnd = (8 * dp).toInt() }
        })
        row.addView(TextView(ctx).apply {
            text = name; textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(ctx).apply {
            text = formatDur(totalSecs); textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(colorHex))
        })
        return row
    }

    /**
     * Indented bullet list of run-log session starts:
     *   •  10:54  (1h 30m)
     *   •  12:09  (45m)
     */
    private fun makeRunLogBullets(segments: List<SessionSummary>): View {
        val dp  = resources.displayMetrics.density
        val ctx = requireContext()
        val col = LinearLayout(ctx).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = (18 * dp).toInt(); it.bottomMargin = (6 * dp).toInt() }
        }
        segments.forEach { seg ->
            val startStr = if (seg.startMs > 86_400_000L * 2)
                timeFmt.format(Date(seg.startMs)) else "--:--"
            col.addView(TextView(ctx).apply {
                text = "• $startStr  (${formatDur(seg.durationSecs)})"
                textSize = 11f
                setTextColor(Color.parseColor("#616161"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (2 * dp).toInt() }
            })
        }
        return col
    }

    private fun scrollToDetail() {
        cardDayDetail.post {
            var v: View? = cardDayDetail.parent as? View
            while (v != null && v !is androidx.core.widget.NestedScrollView) v = v.parent as? View
            (v as? androidx.core.widget.NestedScrollView)?.smoothScrollTo(0, cardDayDetail.bottom)
        }
    }

    // ── Format ────────────────────────────────────────────────────────────────

    private fun formatDur(s: Long): String {
        if (s <= 0L) return "0s"
        var r = s
        val d = r / 86_400L; r %= 86_400L
        val h = r / 3_600L;  r %= 3_600L
        val m = r / 60L;     val sec = r % 60L
        return buildList {
            if (d > 0) add("${d}d"); if (h > 0) add("${h}h")
            if (m > 0) add("${m}m"); if (sec > 0 && d == 0L) add("${sec}s")
        }.take(2).joinToString(" ").ifEmpty { "<1m" }
    }
}
