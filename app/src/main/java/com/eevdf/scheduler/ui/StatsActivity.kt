package com.eevdf.scheduler.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.eevdf.scheduler.R
import com.eevdf.scheduler.db.RunLogDao
import com.eevdf.scheduler.db.TaskDatabase
import com.eevdf.scheduler.model.RunDailySummary
import com.eevdf.scheduler.model.RunLogEntry
import com.eevdf.scheduler.model.Task
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class StatsActivity : AppCompatActivity() {

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
    private val dateFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        val toolbar = findViewById<Toolbar>(R.id.statsToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        etWindowRange  = findViewById(R.id.etWindowRange)
        btnApplyWindow = findViewById(R.id.btnApplyWindow)
        tvWindowLabel  = findViewById(R.id.tvWindowLabel)
        tvTotalRuntime = findViewById(R.id.tvTotalRuntime)
        tvTotalRuns    = findViewById(R.id.tvTotalRuns)
        tvActiveTasks  = findViewById(R.id.tvActiveTasks)
        tvAvgRun       = findViewById(R.id.tvAvgRun)

        llDistribution   = findViewById(R.id.llDistribution)
        llMostFrequent   = findViewById(R.id.llMostFrequent)
        llLeastFrequent  = findViewById(R.id.llLeastFrequent)
        llUntouched      = findViewById(R.id.llUntouched)
        llQuotaViolators = findViewById(R.id.llQuotaViolators)
        llSwitching      = findViewById(R.id.llSwitching)
        llWindowActivity = findViewById(R.id.llWindowActivity)
        llPeakDay        = findViewById(R.id.llPeakDay)
        llWeekday        = findViewById(R.id.llWeekday)
        llStreak         = findViewById(R.id.llStreak)

        btnApplyWindow.setOnClickListener {
            val p = parseRangeInput(etWindowRange.text.toString())
            if (p > 0) { windowSeconds = p; loadStats() }
            else etWindowRange.error = "Invalid range"
        }
        loadStats()
    }

    private fun loadStats() {
        val db  = TaskDatabase.getDatabase(this)
        val dao = db.taskDao()
        val rld = db.runLogDao()
        lifecycleScope.launch {
            val nowMs  = System.currentTimeMillis()
            val fromMs = nowMs - windowSeconds * 1_000L
            val tasks       = withContext(Dispatchers.IO) { dao.getAllTasksForStats() }
            val logEntries  = withContext(Dispatchers.IO) { rld.getEntriesInRange(fromMs, nowMs) }
            val dailyAll    = withContext(Dispatchers.IO) { rld.getDailyInRange(0L) }
            val weekdayTots = withContext(Dispatchers.IO) { rld.getGlobalWeekdayTotals() }
            renderStats(tasks, logEntries, dailyAll, weekdayTots, fromMs, nowMs)
        }
    }

    private fun renderStats(
        tasks: List<Task>, logEntries: List<RunLogEntry>,
        dailyAll: List<RunDailySummary>,
        weekdayTots: List<RunLogDao.WeekdayTotal>,
        fromMs: Long, nowMs: Long
    ) {
        tvWindowLabel.text = "Showing last ${formatDur(windowSeconds)}"
        val leafTasks  = tasks.filter { !it.isGroup }
        val ranTasks   = leafTasks.filter { it.runCount > 0 }
        val totalRT    = leafTasks.sumOf { it.totalRunTime }
        val totalRuns  = leafTasks.sumOf { it.runCount }
        val active     = leafTasks.count { !it.isCompleted }
        val avgPerTask = if (ranTasks.isNotEmpty()) totalRT / ranTasks.size else 0L
        tvTotalRuntime.text = formatDur(totalRT)
        tvTotalRuns.text    = "$totalRuns"
        tvActiveTasks.text  = "$active"
        tvAvgRun.text       = formatDur(avgPerTask)

        val taskById = tasks.associateBy { it.id }

        renderDistribution(leafTasks, totalRT)
        renderFrequency(ranTasks)
        renderUntouched(leafTasks, nowMs)
        renderQuotaViolators(tasks)
        renderSwitching(logEntries, taskById)
        renderWindowActivity(logEntries, dailyAll, taskById, fromMs)
        renderPeakDay(dailyAll, taskById)
        renderWeekdayHeatmap(weekdayTots)
        renderStreaks(dailyAll, taskById, nowMs)
    }

    private fun renderDistribution(leafTasks: List<Task>, totalRT: Long) {
        llDistribution.removeAllViews()
        val sorted = leafTasks.sortedByDescending { it.totalRunTime }
        val maxS   = sorted.firstOrNull()?.totalRunTime?.coerceAtLeast(1L) ?: 1L
        sorted.take(10).forEachIndexed { i, t ->
            val pct  = (t.totalRunTime * 100L / maxS).toInt()
            val gPct = if (totalRT > 0) (t.totalRunTime * 1000L / totalRT) / 10.0 else 0.0
            llDistribution.addView(makeBarRow(t.name,
                "${formatDur(t.totalRunTime)}  ($gPct%)",
                pct, priorityColor(t.priority), i < minOf(9, sorted.size - 1)))
        }
        if (sorted.isEmpty()) llDistribution.addView(makeEmpty("No runtime data yet"))
    }

    private fun renderFrequency(ranTasks: List<Task>) {
        llMostFrequent.removeAllViews()
        ranTasks.sortedByDescending { it.runCount }.take(5).forEach { t ->
            val avg = if (t.runCount > 0) t.totalRunTime / t.runCount else 0L
            llMostFrequent.addView(makeStatRow(t.name, "${t.runCount} runs",
                "avg ${formatDur(avg)}/run", "#1B5E20"))
        }
        if (ranTasks.isEmpty()) llMostFrequent.addView(makeEmpty("No run data yet"))

        llLeastFrequent.removeAllViews()
        ranTasks.sortedBy { it.runCount }.take(5).forEach { t ->
            llLeastFrequent.addView(makeStatRow(t.name,
                "${t.runCount} run${if (t.runCount == 1) "" else "s"}",
                "${formatDur(t.totalRunTime)} total", "#B71C1C"))
        }
        if (ranTasks.isEmpty()) llLeastFrequent.addView(makeEmpty("No run data yet"))
    }

    private fun renderUntouched(leafTasks: List<Task>, nowMs: Long) {
        llUntouched.removeAllViews()
        leafTasks.filter { !it.isCompleted }
            .sortedBy { if (it.startTimeEpoch > 0) it.startTimeEpoch else it.createdAt }
            .take(5)
            .forEach { t ->
                val lastMs  = if (t.startTimeEpoch > 0) t.startTimeEpoch else 0L
                val idleSec = if (lastMs > 0) (nowMs - lastMs) / 1_000L else -1L
                val date    = if (lastMs > 0) dateFmt.format(Date(lastMs)) else "never run"
                val idle    = if (idleSec >= 0) "idle ${formatDur(idleSec)}" else "never run"
                llUntouched.addView(makeStatRow(t.name, idle, date, "#5D4037"))
            }
        if (leafTasks.isEmpty()) llUntouched.addView(makeEmpty("No tasks"))
    }

    private fun renderQuotaViolators(tasks: List<Task>) {
        llQuotaViolators.removeAllViews()
        tasks.filter { it.isQuotaEnabled && it.isQuotaExceeded }
            .sortedByDescending { it.quotaOverflowSeconds }
            .forEach { t ->
                val over = if (t.quotaSeconds > 0) (t.quotaOverflowSeconds * 100L / t.quotaSeconds).toInt() else 0
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

        data class Pair(val from: String, val to: String)
        val pairCount  = mutableMapOf<Pair, Int>()
        val switchIns  = mutableMapOf<String, Int>()
        for (e in entries) {
            if (e.prevTaskId == null || e.prevTaskId == e.taskId) continue
            val p = Pair(e.prevTaskId, e.taskId)
            pairCount[p] = (pairCount[p] ?: 0) + 1
            switchIns[e.taskId] = (switchIns[e.taskId] ?: 0) + 1
        }
        llSwitching.addView(makeLabel("Total context switches: ${pairCount.values.sum()}"))

        pairCount.entries.sortedByDescending { it.value }.take(5).forEach { (p, n) ->
            val from = taskById[p.from]?.name ?: p.from.take(10)
            val to   = taskById[p.to]?.name   ?: p.to.take(10)
            llSwitching.addView(makeStatRow("$from  →  $to", "${n}x",
                "switched $n time${if (n == 1) "" else "s"}", "#1565C0"))
        }
        switchIns.entries.sortedByDescending { it.value }.take(3).let { list ->
            if (list.isNotEmpty()) {
                llSwitching.addView(makeLabel("Most interrupted:"))
                list.forEach { (id, n) ->
                    llSwitching.addView(makeStatRow(
                        taskById[id]?.name ?: id.take(10), "$n switch-ins", "in window", "#B71C1C"))
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
                            taskById[id]?.name ?: id.take(10),
                            "${formatDur(avg)}/run", "deep focus", "#1B5E20"))
                    }
                }
            }
    }

    private fun renderWindowActivity(
        logEntries: List<RunLogEntry>, dailyAll: List<RunDailySummary>,
        taskById: Map<String, Task>, fromMs: Long
    ) {
        llWindowActivity.removeAllViews()
        val secs = mutableMapOf<String, Long>()
        logEntries.forEach { e -> secs[e.taskId] = (secs[e.taskId] ?: 0L) + e.durationSecs }
        dailyAll.filter { it.dayEpoch >= fromMs }
            .forEach { d -> secs[d.taskId] = (secs[d.taskId] ?: 0L) + d.totalSecs }
        val sorted = secs.entries.sortedByDescending { it.value }
        val total  = sorted.sumOf { it.value }.coerceAtLeast(1L)
        val maxS   = sorted.firstOrNull()?.value?.coerceAtLeast(1L) ?: 1L
        if (sorted.isEmpty()) {
            llWindowActivity.addView(makeEmpty("No activity in selected window"))
            return
        }
        sorted.take(10).forEachIndexed { i, (id, s) ->
            llWindowActivity.addView(makeBarRow(
                taskById[id]?.name ?: id.take(10),
                "${formatDur(s)}  (${s * 100L / total}%)",
                (s * 100L / maxS).toInt(), "#1565C0", i < minOf(9, sorted.size - 1)))
        }
    }

    private fun renderPeakDay(dailyAll: List<RunDailySummary>, taskById: Map<String, Task>) {
        llPeakDay.removeAllViews()
        val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        val dn = listOf("","Sun","Mon","Tue","Wed","Thu","Fri","Sat")
        dailyAll.groupBy { it.taskId }
            .mapValues { (_, rows) -> rows.maxByOrNull { it.totalSecs }!! }
            .entries.sortedByDescending { it.value.totalSecs }.take(8)
            .forEach { (id, row) ->
                llPeakDay.addView(makeStatRow(
                    taskById[id]?.name ?: id.take(10),
                    formatDur(row.totalSecs),
                    "${dn.getOrElse(row.weekDay){"?"}} ${dayFmt.format(Date(row.dayEpoch))}" +
                    "  |  ${row.runCount} run${if (row.runCount == 1) "" else "s"}",
                    "#4A148C"))
            }
        if (dailyAll.isEmpty()) llPeakDay.addView(makeEmpty("No daily history yet"))
    }

    private fun renderWeekdayHeatmap(tots: List<RunLogDao.WeekdayTotal>) {
        llWeekday.removeAllViews()
        if (tots.isEmpty()) { llWeekday.addView(makeEmpty("Not enough history yet")); return }
        val dn    = listOf("","Sun","Mon","Tue","Wed","Thu","Fri","Sat")
        val maxS  = tots.maxOf { it.totalSecs }.coerceAtLeast(1L)
        val map   = tots.associateBy { it.weekDay }
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
        val today = startOfDay(nowMs)
        val daysByTask = dailyAll.groupBy { it.taskId }
            .mapValues { (_, rows) -> rows.map { it.dayEpoch }.toSortedSet() }
        daysByTask.mapValues { (_, days) ->
            var s = 0; var cur = today
            while (days.contains(cur)) { s++; cur -= dayMs }; s
        }.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(8)
            .forEach { (id, streak) ->
                val total = daysByTask[id]?.size ?: 0
                llStreak.addView(makeStatRow(
                    taskById[id]?.name ?: id.take(10),
                    "$streak day${if (streak == 1) "" else "s"}",
                    "current streak  |  $total total active days",
                    when { streak >= 30 -> "#1B5E20"; streak >= 7 -> "#388E3C"
                           streak >= 3  -> "#F57C00"; else -> "#757575" }))
            }
        if (llStreak.childCount == 0) llStreak.addView(makeEmpty("No active streaks"))
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private fun makeBarRow(label: String, sublabel: String,
                           percent: Int, barColor: String, showDivider: Boolean): View {
        val dp   = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (10 * dp).toInt() }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply {
            text = label; textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = sublabel; textSize = 11f; setTextColor(Color.parseColor("#757575"))
        })
        root.addView(row)
        val track = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (6 * dp).toInt()
            ).also { it.topMargin = (4 * dp).toInt() }
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        }
        track.addView(View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                (percent.coerceIn(0,100) / 100f *
                    resources.displayMetrics.widthPixels).toInt().coerceAtLeast((2*dp).toInt()),
                FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor(barColor))
        })
        root.addView(track)
        if (showDivider) root.addView(divider())
        return root
    }

    private fun makeStatRow(primary: String, valueText: String,
                            meta: String, accent: String): View {
        val dp   = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (10 * dp).toInt() }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(TextView(this).apply {
            text = primary; textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(TextView(this).apply {
            text = valueText; textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(accent))
        })
        root.addView(top)
        root.addView(TextView(this).apply {
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
        return TextView(this).apply {
            this.text = text; textSize = 12f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#616161"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (6 * dp).toInt(); it.bottomMargin = (4 * dp).toInt() }
        }
    }

    private fun makeEmpty(msg: String) = TextView(this).apply {
        text = msg; textSize = 12f; setTextColor(Color.parseColor("#BDBDBD")); gravity = Gravity.CENTER
        val dp = resources.displayMetrics.density
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = (4 * dp).toInt(); it.bottomMargin = (4 * dp).toInt() }
    }

    private fun divider(): View {
        val dp = resources.displayMetrics.density
        return View(this).apply {
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).also { it.topMargin = (6 * dp).toInt() }
        }
    }

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
        val bare= if (d == 0L && h == 0L && m == 0L && sec == 0L) s.toLongOrNull() else null
        return (bare ?: tot).coerceIn(1L, 365L * 86_400L)
    }

    private fun priorityColor(p: Int) = when (p) {
        1 -> "#9C27B0"; 2 -> "#3F51B5"; 3 -> "#2196F3"
        4 -> "#4CAF50"; 5 -> "#FF9800"; 6 -> "#F57F17"
        7 -> "#F44336"; else -> "#1565C0"
    }
}
