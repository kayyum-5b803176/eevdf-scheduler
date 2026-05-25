package com.eevdf.scheduler.ui.stats

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.eevdf.scheduler.R
import com.eevdf.scheduler.db.RunLogDao
import com.eevdf.scheduler.db.TaskDatabase
import com.eevdf.scheduler.model.runlog.RunDailySummary
import com.eevdf.scheduler.model.runlog.RunLogEntry
import com.eevdf.scheduler.model.task.Task
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class StatsOverviewFragment : Fragment() {

    private lateinit var etWindowRange:   TextInputEditText
    private lateinit var btnApplyWindow:  MaterialButton
    private lateinit var tvWindowLabel:   TextView
    private lateinit var tvTotalRuntime:  TextView
    private lateinit var tvTotalRuns:     TextView
    private lateinit var tvActiveTasks:   TextView
    private lateinit var tvAvgRun:        TextView

    private lateinit var llDistribution:   LinearLayout
    private lateinit var llMostFrequent:   LinearLayout
    private lateinit var llLeastFrequent:  LinearLayout
    private lateinit var llUntouched:      LinearLayout
    private lateinit var llQuotaViolators: LinearLayout
    private lateinit var llSwitching:      LinearLayout
    private lateinit var llWindowActivity: LinearLayout
    private lateinit var llPeakDay:        LinearLayout
    private lateinit var llWeekday:        LinearLayout
    private lateinit var llStreak:         LinearLayout

    private var windowSeconds: Long = 7L * 86_400L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_stats_overview, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etWindowRange  = view.findViewById(R.id.etWindowRange)
        btnApplyWindow = view.findViewById(R.id.btnApplyWindow)
        tvWindowLabel  = view.findViewById(R.id.tvWindowLabel)
        tvTotalRuntime = view.findViewById(R.id.tvTotalRuntime)
        tvTotalRuns    = view.findViewById(R.id.tvTotalRuns)
        tvActiveTasks  = view.findViewById(R.id.tvActiveTasks)
        tvAvgRun       = view.findViewById(R.id.tvAvgRun)

        llDistribution   = view.findViewById(R.id.llDistribution)
        llMostFrequent   = view.findViewById(R.id.llMostFrequent)
        llLeastFrequent  = view.findViewById(R.id.llLeastFrequent)
        llUntouched      = view.findViewById(R.id.llUntouched)
        llQuotaViolators = view.findViewById(R.id.llQuotaViolators)
        llSwitching      = view.findViewById(R.id.llSwitching)
        llWindowActivity = view.findViewById(R.id.llWindowActivity)
        llPeakDay        = view.findViewById(R.id.llPeakDay)
        llWeekday        = view.findViewById(R.id.llWeekday)
        llStreak         = view.findViewById(R.id.llStreak)

        btnApplyWindow.setOnClickListener {
            val p = parseRangeInput(etWindowRange.text.toString())
            if (p > 0) { windowSeconds = p; loadStats() }
            else etWindowRange.error = "Invalid range"
        }
        loadStats()
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadStats() {
        val db  = TaskDatabase.getDatabase(requireContext())
        val dao = db.taskDao()
        val rld = db.runLogDao()
        viewLifecycleOwner.lifecycleScope.launch {
            val nowMs  = System.currentTimeMillis()
            val fromMs = nowMs - windowSeconds * 1_000L
            val tasks         = withContext(Dispatchers.IO) { dao.getAllTasksForStats() }
            val logEntries    = withContext(Dispatchers.IO) { rld.getEntriesInRange(fromMs, nowMs) }
            val allLogEntries = withContext(Dispatchers.IO) { rld.getEntriesInRange(0L, nowMs) }
            val dailyAll      = withContext(Dispatchers.IO) { rld.getDailyInRange(0L) }
            renderStats(tasks, logEntries, allLogEntries, dailyAll, fromMs, nowMs)
        }
    }

    // ── Render orchestrator (mirrors improved new StatsActivity logic) ─────────

    private fun renderStats(
        tasks: List<Task>,
        logEntries: List<RunLogEntry>,
        allLogEntries: List<RunLogEntry>,
        dailyAll: List<RunDailySummary>,
        fromMs: Long,
        nowMs: Long
    ) {
        tvWindowLabel.text = "Showing last ${formatDur(windowSeconds)}"

        val leafTasks       = tasks.filter { !it.isGroup }
        val taskById        = tasks.associateBy { it.id }


        // Window-filtered slices
        val windowDailyRows = dailyAll.filter { it.dayEpoch >= fromMs }

        // Window header stats (leaf tasks only — groups never appear in run_log)
        val leafIds         = leafTasks.map { it.id }.toHashSet()
        val leafLogEntries  = logEntries.filter { it.taskId in leafIds }
        val leafWindowDaily = windowDailyRows.filter { it.taskId in leafIds }

        val windowRunSecs  = leafLogEntries.sumOf { it.durationSecs } +
                             leafWindowDaily.sumOf { it.totalSecs }
        val windowRunCount = leafLogEntries.size + leafWindowDaily.sumOf { it.runCount }
        val windowTaskIds  = (leafLogEntries.map { it.taskId } +
                              leafWindowDaily.map { it.taskId }).distinct()
        val avgPerTask     = if (windowTaskIds.isNotEmpty()) windowRunSecs / windowTaskIds.size else 0L

        tvTotalRuntime.text = formatDur(windowRunSecs)
        tvTotalRuns.text    = "$windowRunCount"
        tvActiveTasks.text  = "${windowTaskIds.size}"
        tvAvgRun.text       = formatDur(avgPerTask)

        // Window-scoped weekday totals
        val windowWeekdayTots    = buildEffectiveWeekdayTots(leafLogEntries, windowDailyRows)
        val windowEffectiveDaily = buildEffectiveDailyAll(leafLogEntries, windowDailyRows)

        renderDistribution(leafTasks, leafTasks.sumOf { it.totalRunTime })
        renderFrequency(leafLogEntries, windowDailyRows, taskById)
        renderUntouched(leafTasks, leafLogEntries, windowDailyRows, nowMs)
        renderQuotaViolators(tasks)
        renderSwitching(logEntries, taskById)
        renderWindowActivity(leafLogEntries, windowDailyRows, taskById)
        renderPeakDay(windowEffectiveDaily, taskById)
        renderWeekdayHeatmap(windowWeekdayTots)
        renderStreaks(windowEffectiveDaily, taskById, nowMs)
    }

    // ── Section renderers ─────────────────────────────────────────────────────

    private fun renderDistribution(leafTasks: List<Task>, totalRT: Long) {
        llDistribution.removeAllViews()
        val sorted = leafTasks.sortedByDescending { it.totalRunTime }
        val maxS   = sorted.firstOrNull()?.totalRunTime?.coerceAtLeast(1L) ?: 1L
        sorted.take(10).forEachIndexed { i, t ->
            val pct  = (t.totalRunTime * 100L / maxS).toInt()
            val gPct = if (totalRT > 0) (t.totalRunTime * 1000L / totalRT) / 10.0 else 0.0
            llDistribution.addView(makeBarRow(
                t.name, "${formatDur(t.totalRunTime)}  ($gPct%)",
                pct, priorityColor(t.priority), i < minOf(9, sorted.size - 1)))
        }
        if (sorted.isEmpty()) llDistribution.addView(makeEmpty("No runtime data yet"))
    }

    private fun renderFrequency(
        logEntries: List<RunLogEntry>,
        windowDailyRows: List<RunDailySummary>,
        taskById: Map<String, Task>
    ) {
        llMostFrequent.removeAllViews()
        llLeastFrequent.removeAllViews()

        data class Stat(val secs: Long, val runs: Int)
        val statsMap = mutableMapOf<String, Stat>()
        logEntries.forEach { e ->
            val c = statsMap[e.taskId] ?: Stat(0L, 0)
            statsMap[e.taskId] = Stat(c.secs + e.durationSecs, c.runs + 1)
        }
        windowDailyRows.forEach { d ->
            val c = statsMap[d.taskId] ?: Stat(0L, 0)
            statsMap[d.taskId] = Stat(c.secs + d.totalSecs, c.runs + d.runCount)
        }

        if (statsMap.isEmpty()) {
            llMostFrequent.addView(makeEmpty("No run data in selected window"))
            llLeastFrequent.addView(makeEmpty("No run data in selected window"))
            return
        }

        val sorted = statsMap.entries.sortedByDescending { it.value.runs }
        sorted.take(5).forEach { (id, stat) ->
            val avg = if (stat.runs > 0) stat.secs / stat.runs else 0L
            llMostFrequent.addView(makeStatRow(
                taskById[id]?.name ?: "[deleted]", "${stat.runs} runs",
                "avg ${formatDur(avg)}/run", "#1B5E20"))
        }
        sorted.reversed().take(5).forEach { (id, stat) ->
            llLeastFrequent.addView(makeStatRow(
                taskById[id]?.name ?: "[deleted]",
                "${stat.runs} run${if (stat.runs == 1) "" else "s"}",
                "${formatDur(stat.secs)} total", "#B71C1C"))
        }
    }

    private fun renderUntouched(
        leafTasks: List<Task>,
        logEntries: List<RunLogEntry>,
        windowDailyRows: List<RunDailySummary>,
        nowMs: Long
    ) {
        llUntouched.removeAllViews()
        val lastRunFmt    = SimpleDateFormat("EEE dd-MM-yyyy", Locale.getDefault())
        val activeInWindow = (logEntries.map { it.taskId } +
                              windowDailyRows.map { it.taskId }).toHashSet()
        val lastEndByTask = logEntries
            .groupBy { it.taskId }
            .mapValues { (_, entries) ->
                entries.maxOf { it.startEpoch + it.durationSecs * 1_000L }
            }

        data class Row(val task: Task, val lastRunEndMs: Long, val idleSec: Long)

        val rows = leafTasks
            .filter { it.totalRunTime >= 1_000L }
            .filter { it.id in activeInWindow }
            .map { t ->
                val endMs   = lastEndByTask[t.id] ?: t.startTimeEpoch.coerceAtLeast(0L)
                val idleSec = if (endMs > 0L) (nowMs - endMs) / 1_000L else -1L
                Row(t, endMs, idleSec)
            }
            .sortedByDescending { it.idleSec }
            .take(8)

        rows.forEach { (t, lastRunEndMs, idleSec) ->
            val rightVal = if (idleSec >= 0L) formatDur(idleSec) else "never run"
            val meta     = if (lastRunEndMs > 0L)
                "${lastRunFmt.format(Date(lastRunEndMs))}  last run"
            else "never run"
            llUntouched.addView(makeStatRow(t.name, rightVal, meta, "#5D4037"))
        }
        if (llUntouched.childCount == 0)
            llUntouched.addView(makeEmpty("No qualifying tasks in selected window"))
    }

    private fun renderQuotaViolators(tasks: List<Task>) {
        llQuotaViolators.removeAllViews()
        tasks.filter { it.isQuotaEnabled && it.isQuotaExceeded }
            .sortedByDescending { it.quotaOverflowSeconds }
            .forEach { t ->
                val over = if (t.quotaSeconds > 0)
                    (t.quotaOverflowSeconds * 100L / t.quotaSeconds).toInt() else 0
                llQuotaViolators.addView(makeStatRow(t.name,
                    "-${formatDur(t.quotaOverflowSeconds)} over",
                    "+$over% over limit  (${formatDur(t.quotaSeconds)})", "#E65100"))
            }
        tasks.filter { it.isQuotaEnabled && it.isQuotaWarning }
            .sortedByDescending { it.quotaProgressPercent }
            .forEach { t ->
                llQuotaViolators.addView(makeStatRow(t.name,
                    "${t.quotaProgressPercent}% used",
                    "${formatDur(t.quotaRemainingSeconds)} left  (${formatDur(t.quotaSeconds)})", "#F57C00"))
            }
        if (llQuotaViolators.childCount == 0)
            llQuotaViolators.addView(makeEmpty("No quota violations"))
    }

    private fun renderSwitching(entries: List<RunLogEntry>, taskById: Map<String, Task>) {
        llSwitching.removeAllViews()
        if (entries.isEmpty()) {
            llSwitching.addView(makeEmpty("No run history in selected window"))
            return
        }
        val avgSession = entries.sumOf { it.durationSecs } / entries.size
        llSwitching.addView(makeLabel("Avg session length: ${formatDur(avgSession)}"))

        data class SwitchPair(val from: String, val to: String)
        val pairCount = mutableMapOf<SwitchPair, Int>()
        val switchIns = mutableMapOf<String, Int>()
        for (e in entries) {
            if (e.prevTaskId == null || e.prevTaskId == e.taskId) continue
            val p = SwitchPair(e.prevTaskId, e.taskId)
            pairCount[p] = (pairCount[p] ?: 0) + 1
            switchIns[e.taskId] = (switchIns[e.taskId] ?: 0) + 1
        }
        llSwitching.addView(makeLabel("Total context switches: ${pairCount.values.sum()}"))

        pairCount.entries.sortedByDescending { it.value }.take(5).forEach { (p, n) ->
            val from = taskById[p.from]?.name ?: "[deleted]"
            val to   = taskById[p.to]?.name   ?: "[deleted]"
            llSwitching.addView(makeStatRow("$from  →  $to", "${n}x",
                "switched $n time${if (n == 1) "" else "s"}", "#1565C0"))
        }
        switchIns.entries.sortedByDescending { it.value }.take(3).let { list ->
            if (list.isNotEmpty()) {
                llSwitching.addView(makeLabel("Most interrupted:"))
                list.forEach { (id, n) ->
                    llSwitching.addView(makeStatRow(
                        taskById[id]?.name ?: "[deleted]", "$n switch-ins", "in window", "#B71C1C"))
                }
            }
        }
        entries.groupBy { it.taskId }
            .mapValues { (_, es) -> es.sumOf { it.durationSecs } / es.size }
            .entries.filter { entries.count { e -> e.taskId == it.key } >= 3 }
            .sortedByDescending { it.value }.take(3).let { list ->
                if (list.isNotEmpty()) {
                    llSwitching.addView(makeLabel("Deepest focus (avg session):"))
                    list.forEach { (id, avg) ->
                        llSwitching.addView(makeStatRow(
                            taskById[id]?.name ?: "[deleted]",
                            "${formatDur(avg)}/run", "deep focus", "#1B5E20"))
                    }
                }
            }
    }

    private fun renderWindowActivity(
        logEntries: List<RunLogEntry>,
        windowDailyRows: List<RunDailySummary>,
        taskById: Map<String, Task>
    ) {
        llWindowActivity.removeAllViews()
        val secs = mutableMapOf<String, Long>()
        logEntries.forEach { e -> secs[e.taskId] = (secs[e.taskId] ?: 0L) + e.durationSecs }
        windowDailyRows.forEach { d -> secs[d.taskId] = (secs[d.taskId] ?: 0L) + d.totalSecs }
        val sorted = secs.entries.sortedByDescending { it.value }
        val total  = sorted.sumOf { it.value }.coerceAtLeast(1L)
        val maxS   = sorted.firstOrNull()?.value?.coerceAtLeast(1L) ?: 1L
        if (sorted.isEmpty()) {
            llWindowActivity.addView(makeEmpty("No activity in selected window"))
            return
        }
        sorted.take(10).forEachIndexed { i, (id, s) ->
            llWindowActivity.addView(makeBarRow(
                taskById[id]?.name ?: "[deleted]",
                "${formatDur(s)}  (${s * 100L / total}%)",
                (s * 100L / maxS).toInt(), "#1565C0", i < minOf(9, sorted.size - 1)))
        }
    }

    private fun renderPeakDay(dailyAll: List<RunDailySummary>, taskById: Map<String, Task>) {
        llPeakDay.removeAllViews()
        val dayFmt = SimpleDateFormat("EEE dd-MM-yyyy", Locale.getDefault())
        dailyAll.groupBy { it.taskId }
            .mapValues { (_, rows) -> rows.maxByOrNull { it.totalSecs }!! }
            .entries.sortedByDescending { it.value.totalSecs }.take(8)
            .forEach { (id, row) ->
                llPeakDay.addView(makeStatRow(
                    taskById[id]?.name ?: "[deleted]",
                    formatDur(row.totalSecs),
                    "${dayFmt.format(Date(row.dayEpoch))}" +
                    "  |  ${row.runCount} run${if (row.runCount == 1) "" else "s"}",
                    "#4A148C"))
            }
        if (dailyAll.isEmpty()) llPeakDay.addView(makeEmpty("No daily history in selected window"))
    }

    private fun renderWeekdayHeatmap(tots: List<RunLogDao.WeekdayTotal>) {
        llWeekday.removeAllViews()
        if (tots.isEmpty()) { llWeekday.addView(makeEmpty("Not enough history yet")); return }
        val dn   = listOf("", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val maxS = tots.maxOf { it.totalSecs }.coerceAtLeast(1L)
        val map  = tots.associateBy { it.weekDay }
        for (d in 1..7) {
            val row  = map[d]
            val secs = row?.totalSecs ?: 0L
            val pct  = (secs * 100L / maxS).toInt()
            llWeekday.addView(makeBarRow(
                dn.getOrElse(d) { "?" },
                if (secs > 0) "${formatDur(secs)}  (${row?.runCount ?: 0} runs)" else "no data",
                pct,
                when { pct >= 80 -> "#1B5E20"; pct >= 50 -> "#388E3C"
                       pct >= 20 -> "#66BB6A"; pct > 0   -> "#A5D6A7"; else -> "#E0E0E0" },
                d < 7))
        }
    }

    private fun renderStreaks(
        dailyAll: List<RunDailySummary>, taskById: Map<String, Task>, nowMs: Long
    ) {
        llStreak.removeAllViews()
        if (dailyAll.isEmpty()) { llStreak.addView(makeEmpty("Not enough history yet")); return }
        val utc   = TimeZone.getTimeZone("UTC")
        val dayMs = 86_400_000L
        fun startOfDay(ms: Long): Long {
            val c = Calendar.getInstance(utc); c.timeInMillis = ms
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0);      c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
        val today      = startOfDay(nowMs)
        val daysByTask = dailyAll.groupBy { it.taskId }
            .mapValues { (_, rows) -> rows.map { it.dayEpoch }.toSortedSet() }
        daysByTask.mapValues { (_, days) ->
            var s = 0; var cur = today
            while (days.contains(cur)) { s++; cur -= dayMs }; s
        }.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(8)
            .forEach { (id, streak) ->
                val total = daysByTask[id]?.size ?: 0
                llStreak.addView(makeStatRow(
                    taskById[id]?.name ?: "[deleted]",
                    "$streak day${if (streak == 1) "" else "s"}",
                    "current streak  |  $total total active days",
                    when { streak >= 30 -> "#1B5E20"; streak >= 7 -> "#388E3C"
                           streak >= 3  -> "#F57C00"; else -> "#757575" }))
            }
        if (llStreak.childCount == 0) llStreak.addView(makeEmpty("No active streaks"))
    }

    // ── Data helpers (mirrors improved StatsActivity) ─────────────────────────

    private fun buildEffectiveDailyAll(
        logEntries: List<RunLogEntry>,
        dailyAll: List<RunDailySummary>
    ): List<RunDailySummary> {
        val utc = TimeZone.getTimeZone("UTC")
        fun startOfDay(ms: Long): Long {
            val c = Calendar.getInstance(utc); c.timeInMillis = ms
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0);      c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
        val existingKeys = dailyAll.map { it.taskId to it.dayEpoch }.toHashSet()
        val syntheticRows = logEntries
            .groupBy { it.taskId to startOfDay(it.startEpoch) }
            .filter { (key, _) -> key !in existingKeys }
            .map { (key, entries) ->
                val (taskId, dayEpoch) = key
                val cal = Calendar.getInstance(utc).also { it.timeInMillis = dayEpoch }
                RunDailySummary(
                    taskId        = taskId,
                    dayEpoch      = dayEpoch,
                    totalSecs     = entries.sumOf { it.durationSecs },
                    runCount      = entries.size,
                    switchInCount = entries.count { it.prevTaskId != null && it.prevTaskId != taskId },
                    weekDay       = cal.get(Calendar.DAY_OF_WEEK)
                )
            }
        return dailyAll + syntheticRows
    }

    private fun buildEffectiveWeekdayTots(
        logEntries: List<RunLogEntry>,
        dailyRows: List<RunDailySummary>
    ): List<RunLogDao.WeekdayTotal> {
        val utc = TimeZone.getTimeZone("UTC")
        fun weekDayOf(entry: RunLogEntry): Int {
            if (entry.weekDay > 0) return entry.weekDay
            return Calendar.getInstance(utc).also { it.timeInMillis = entry.startEpoch }
                .get(Calendar.DAY_OF_WEEK)
        }
        val merged = mutableMapOf<Int, RunLogDao.WeekdayTotal>()
        // Seed from compacted daily rows
        dailyRows.groupBy { it.weekDay }.forEach { (day, rows) ->
            if (day > 0) merged[day] = RunLogDao.WeekdayTotal(
                weekDay   = day,
                totalSecs = rows.sumOf { it.totalSecs },
                runCount  = rows.sumOf { it.runCount }
            )
        }
        // Merge recent run_log entries (not yet compacted)
        logEntries.groupBy { weekDayOf(it) }.forEach { (day, entries) ->
            val existing = merged[day]
            merged[day] = if (existing == null)
                RunLogDao.WeekdayTotal(day, entries.sumOf { it.durationSecs }, entries.size)
            else RunLogDao.WeekdayTotal(
                weekDay   = day,
                totalSecs = existing.totalSecs + entries.sumOf { it.durationSecs },
                runCount  = existing.runCount  + entries.size
            )
        }
        return merged.values.sortedBy { it.weekDay }
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private fun makeBarRow(
        label: String, sublabel: String,
        percent: Int, barColor: String, showDivider: Boolean
    ): View {
        val dp   = resources.displayMetrics.density
        val ctx  = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (10 * dp).toInt() }
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(ctx).apply {
            text = label; textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(ctx).apply {
            text = sublabel; textSize = 11f; setTextColor(Color.parseColor("#757575"))
        })
        root.addView(row)
        val track = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (6 * dp).toInt()
            ).also { it.topMargin = (4 * dp).toInt() }
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        }
        track.addView(View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                (percent.coerceIn(0, 100) / 100f *
                    resources.displayMetrics.widthPixels).toInt().coerceAtLeast((2 * dp).toInt()),
                FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor(barColor))
        })
        root.addView(track)
        if (showDivider) root.addView(divider())
        return root
    }

    private fun makeStatRow(
        primary: String, valueText: String, meta: String, accent: String
    ): View {
        val dp   = resources.displayMetrics.density
        val ctx  = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (10 * dp).toInt() }
        }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(TextView(ctx).apply {
            text = primary; textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(TextView(ctx).apply {
            text = valueText; textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(accent))
        })
        root.addView(top)
        root.addView(TextView(ctx).apply {
            text = meta; textSize = 11f; setTextColor(Color.parseColor("#9E9E9E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (1 * dp).toInt() }
        })
        root.addView(divider())
        return root
    }

    private fun makeLabel(text: String): TextView {
        val dp = resources.displayMetrics.density
        return TextView(requireContext()).apply {
            this.text = text; textSize = 12f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#616161"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (6 * dp).toInt(); it.bottomMargin = (4 * dp).toInt() }
        }
    }

    private fun makeEmpty(msg: String) = TextView(requireContext()).apply {
        text = msg; textSize = 12f; setTextColor(Color.parseColor("#BDBDBD"))
        gravity = Gravity.CENTER
        val dp = resources.displayMetrics.density
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = (4 * dp).toInt(); it.bottomMargin = (4 * dp).toInt() }
    }

    private fun divider(): View {
        val dp = resources.displayMetrics.density
        return View(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).also { it.topMargin = (6 * dp).toInt() }
        }
    }

    // ── Formatting helpers ────────────────────────────────────────────────────

    private fun formatDur(s: Long): String {
        if (s <= 0L) return "0s"
        var r = s
        val d = r / 86_400L; r %= 86_400L
        val h = r / 3_600L;  r %= 3_600L
        val m = r / 60L;     val sec = r % 60L
        return buildList {
            if (d > 0) add("${d}d"); if (h > 0) add("${h}h")
            if (m > 0) add("${m}m"); if (sec > 0) add("${sec}s")
        }.take(2).joinToString(" ").ifEmpty { "0s" }
    }

    private fun parseRangeInput(raw: String): Long {
        val s   = raw.trim().lowercase()
        val d   = Regex("""(\d+)\s*d""").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val h   = Regex("""(\d+)\s*h""").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val m   = Regex("""(\d+)\s*m(?!o)""").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val sec = Regex("""(\d+)\s*s""").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val tot = d * 86_400L + h * 3_600L + m * 60L + sec
        val bare = if (d == 0L && h == 0L && m == 0L && sec == 0L) s.toLongOrNull() else null
        return (bare ?: tot).coerceIn(1L, 365L * 86_400L)
    }

    private fun priorityColor(p: Int) = when (p) {
        1 -> "#9C27B0"; 2 -> "#3F51B5"; 3 -> "#2196F3"
        4 -> "#4CAF50"; 5 -> "#FF9800"; 6 -> "#F57F17"
        7 -> "#F44336"; else -> "#1565C0"
    }
}
