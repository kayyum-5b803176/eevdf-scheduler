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
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.eevdf.scheduler.R
import com.eevdf.scheduler.db.TaskDatabase
import com.eevdf.scheduler.model.Task
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class StatsActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var etWindowRange:  TextInputEditText
    private lateinit var btnApplyWindow: MaterialButton
    private lateinit var tvWindowLabel:  TextView
    private lateinit var tvTotalRuntime: TextView
    private lateinit var tvTotalRuns:    TextView
    private lateinit var tvActiveTasks:  TextView
    private lateinit var tvAvgRun:       TextView

    private lateinit var llDistribution:  LinearLayout
    private lateinit var llMostFrequent:  LinearLayout
    private lateinit var llLeastFrequent: LinearLayout
    private lateinit var llUntouched:     LinearLayout
    private lateinit var llQuotaViolators:LinearLayout
    private lateinit var llSwitching:     LinearLayout
    private lateinit var llWindowActivity:LinearLayout
    private lateinit var llPeakDay:       LinearLayout

    // ── State ─────────────────────────────────────────────────────────────────
    /** Current analysis window in seconds. Default 7 days. */
    private var windowSeconds: Long = 7L * 86_400L
    private val dateFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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

        btnApplyWindow.setOnClickListener {
            val parsed = parseRangeInput(etWindowRange.text.toString())
            if (parsed > 0) {
                windowSeconds = parsed
                loadStats()
            } else {
                etWindowRange.error = "Invalid range"
            }
        }

        loadStats()
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadStats() {
        val dao = TaskDatabase.getDatabase(this).taskDao()
        lifecycleScope.launch {
            val tasks = withContext(Dispatchers.IO) { dao.getAllTasksForStats() }
            renderStats(tasks)
        }
    }

    private fun renderStats(tasks: List<Task>) {
        if (tasks.isEmpty()) {
            tvTotalRuntime.text = "0s"
            tvTotalRuns.text    = "0"
            tvActiveTasks.text  = "0"
            tvAvgRun.text       = "0s"
            return
        }

        val nowMs       = System.currentTimeMillis()
        val windowMs    = windowSeconds * 1_000L
        val windowStart = nowMs - windowMs

        // Label
        tvWindowLabel.text = "Showing last ${formatDuration(windowSeconds)}"

        // Leaf tasks only for most metrics (exclude pure groups)
        val leafTasks    = tasks.filter { !it.isGroup && it.runCount > 0 }
        val allLeaf      = tasks.filter { !it.isGroup }

        // ── Overview tiles ────────────────────────────────────────────────────
        val totalRuntime = tasks.sumOf { it.totalRunTime }
        val totalRuns    = tasks.sumOf { it.runCount }
        val activeTasks  = tasks.count { !it.isCompleted && !it.isGroup }
        val avgRunPerTask = if (leafTasks.isNotEmpty())
            leafTasks.sumOf { it.totalRunTime } / leafTasks.size else 0L

        tvTotalRuntime.text = formatDuration(totalRuntime)
        tvTotalRuns.text    = "$totalRuns"
        tvActiveTasks.text  = "$activeTasks"
        tvAvgRun.text       = formatDuration(avgRunPerTask)

        // ── Time distribution bars ────────────────────────────────────────────
        val byRuntime = allLeaf.sortedByDescending { it.totalRunTime }
        val maxRuntime = byRuntime.firstOrNull()?.totalRunTime ?: 1L
        llDistribution.removeAllViews()
        byRuntime.take(10).forEach { task ->
            val pct = if (maxRuntime > 0) (task.totalRunTime * 100L / maxRuntime).toInt() else 0
            val globalPct = if (totalRuntime > 0) (task.totalRunTime * 100.0 / totalRuntime * 10).roundToInt() / 10.0 else 0.0
            llDistribution.addView(makeBarRow(
                context      = this,
                label        = task.name,
                sublabel     = "${formatDuration(task.totalRunTime)}  ($globalPct%)",
                percent      = pct,
                barColor     = priorityColor(task.priority),
                showDivider  = byRuntime.take(10).last() != task
            ))
        }
        if (byRuntime.isEmpty()) llDistribution.addView(makeEmptyNote(this, "No runtime data yet"))

        // ── Most frequent ─────────────────────────────────────────────────────
        val mostFreq = leafTasks.sortedByDescending { it.runCount }.take(5)
        llMostFrequent.removeAllViews()
        mostFreq.forEach { task ->
            val avgPerRun = if (task.runCount > 0) task.totalRunTime / task.runCount else 0L
            llMostFrequent.addView(makeStatRow(
                this,
                primary   = task.name,
                valueText = "${task.runCount} runs",
                meta      = "avg ${formatDuration(avgPerRun)}/run",
                accent    = "#1B5E20"
            ))
        }
        if (mostFreq.isEmpty()) llMostFrequent.addView(makeEmptyNote(this, "No run data yet"))

        // ── Least frequent (ran at least once) ────────────────────────────────
        val leastFreq = leafTasks.filter { it.runCount > 0 }.sortedBy { it.runCount }.take(5)
        llLeastFrequent.removeAllViews()
        leastFreq.forEach { task ->
            llLeastFrequent.addView(makeStatRow(
                this,
                primary   = task.name,
                valueText = "${task.runCount} run${if (task.runCount == 1) "" else "s"}",
                meta      = formatDuration(task.totalRunTime) + " total",
                accent    = "#B71C1C"
            ))
        }
        if (leastFreq.isEmpty()) llLeastFrequent.addView(makeEmptyNote(this, "No run data yet"))

        // ── Longest untouched ─────────────────────────────────────────────────
        //
        // "Last touched" = startTimeEpoch when it was last run (> 0), else createdAt.
        // Tasks never run are included and shown as "never run".
        val untouched = allLeaf
            .filter { !it.isCompleted }
            .sortedBy { if (it.startTimeEpoch > 0) it.startTimeEpoch else it.createdAt }
            .take(5)
        llUntouched.removeAllViews()
        untouched.forEach { task ->
            val lastMs = if (task.startTimeEpoch > 0) task.startTimeEpoch else 0L
            val idleSec = if (lastMs > 0) (nowMs - lastMs) / 1_000L else -1L
            val dateStr = if (lastMs > 0) dateFmt.format(Date(lastMs)) else "never run"
            val idleStr = if (idleSec >= 0) "idle ${formatDuration(idleSec)}" else "never run"
            llUntouched.addView(makeStatRow(
                this,
                primary   = task.name,
                valueText = idleStr,
                meta      = dateStr,
                accent    = "#5D4037"
            ))
        }
        if (untouched.isEmpty()) llUntouched.addView(makeEmptyNote(this, "No tasks"))

        // ── Quota violators ───────────────────────────────────────────────────
        val violators = tasks.filter { it.isQuotaEnabled && it.isQuotaExceeded }
            .sortedByDescending { it.quotaOverflowSeconds }
        llQuotaViolators.removeAllViews()
        violators.forEach { task ->
            val overPct = if (task.quotaSeconds > 0)
                (task.quotaOverflowSeconds * 100L / task.quotaSeconds).toInt() else 0
            llQuotaViolators.addView(makeStatRow(
                this,
                primary   = task.name,
                valueText = "-${formatDuration(task.quotaOverflowSeconds)} over",
                meta      = "+$overPct% over quota (limit: ${formatDuration(task.quotaSeconds)})",
                accent    = "#E65100"
            ))
        }
        // Also show tasks approaching (warning zone, not yet exceeded)
        val warnTasks = tasks.filter { it.isQuotaEnabled && it.isQuotaWarning }
            .sortedByDescending { it.quotaProgressPercent }
        warnTasks.forEach { task ->
            llQuotaViolators.addView(makeStatRow(
                this,
                primary   = task.name,
                valueText = "${task.quotaProgressPercent}% used",
                meta      = "${formatDuration(task.quotaRemainingSeconds)} remaining (limit: ${formatDuration(task.quotaSeconds)})",
                accent    = "#F57C00"
            ))
        }
        if (violators.isEmpty() && warnTasks.isEmpty())
            llQuotaViolators.addView(makeEmptyNote(this, "No quota violations"))

        // ── Task switching overhead ───────────────────────────────────────────
        //
        // Estimated as: for any task that was started then stopped (runCount > 0 and
        // startTimeEpoch was reset), each run session includes a small context-switch
        // penalty.  We approximate average session length and flag tasks with very
        // short average runs (< 30 s) as high-churn / high-switching overhead.
        //
        // Total estimated switch overhead = runCount × 0 (can't measure directly),
        // but we surface tasks whose average run is very short as a proxy indicator.
        llSwitching.removeAllViews()
        val switchStats = leafTasks.filter { it.runCount > 1 }
            .map { task ->
                val avgSec = if (task.runCount > 0) task.totalRunTime / task.runCount else 0L
                Triple(task, avgSec, task.runCount)
            }
            .sortedBy { it.second }  // shortest avg run first = most switching

        val totalSessions     = leafTasks.sumOf { it.runCount }
        val totalTaskRuntime  = leafTasks.sumOf { it.totalRunTime }
        val globalAvgSession  = if (totalSessions > 0) totalTaskRuntime / totalSessions else 0L
        llSwitching.addView(makeLabelRow(this, "Avg session length across all tasks: ${formatDuration(globalAvgSession)}"))

        val highChurn = switchStats.filter { it.second < 60L }.take(5)
        if (highChurn.isNotEmpty()) {
            llSwitching.addView(makeLabelRow(this, "High-churn tasks (avg session < 1m):"))
            highChurn.forEach { (task, avg, runs) ->
                llSwitching.addView(makeStatRow(
                    this,
                    primary   = task.name,
                    valueText = "${formatDuration(avg)}/run",
                    meta      = "$runs sessions — frequent context switches",
                    accent    = "#B71C1C"
                ))
            }
        }
        val lowChurn = switchStats.filter { it.second >= 60L }.takeLast(3).reversed()
        if (lowChurn.isNotEmpty()) {
            llSwitching.addView(makeLabelRow(this, "Longest focused tasks:"))
            lowChurn.forEach { (task, avg, _) ->
                llSwitching.addView(makeStatRow(
                    this,
                    primary   = task.name,
                    valueText = "${formatDuration(avg)}/run",
                    meta      = "deep focus sessions",
                    accent    = "#1B5E20"
                ))
            }
        }
        if (switchStats.isEmpty()) llSwitching.addView(makeEmptyNote(this, "Not enough run history"))

        // ── Runtime in window ─────────────────────────────────────────────────
        //
        // For tasks whose last run started within the window, we attribute totalRunTime
        // (not a per-window slice — the app has no per-session log table).
        // Tasks whose startTimeEpoch is within the window are flagged as "recently active".
        llWindowActivity.removeAllViews()
        val windowActive = allLeaf
            .filter { it.startTimeEpoch in (windowStart + 1)..nowMs || it.isRunning }
            .sortedByDescending { it.totalRunTime }
        if (windowActive.isNotEmpty()) {
            val windowTotal = windowActive.sumOf { it.totalRunTime }
            val maxW = windowActive.first().totalRunTime.coerceAtLeast(1L)
            windowActive.take(8).forEach { task ->
                val pct = (task.totalRunTime * 100L / maxW).toInt()
                llWindowActivity.addView(makeBarRow(
                    context     = this,
                    label       = task.name,
                    sublabel    = formatDuration(task.totalRunTime) +
                                  if (windowTotal > 0) "  (${(task.totalRunTime * 100L / windowTotal)}%)" else "",
                    percent     = pct,
                    barColor    = "#1565C0",
                    showDivider = windowActive.take(8).last() != task
                ))
            }
        } else {
            llWindowActivity.addView(makeEmptyNote(this, "No activity in selected window"))
        }

        // ── Peak day per task ─────────────────────────────────────────────────
        //
        // Since there is no per-session log, we show the day-of-week of the last run
        // as a lightweight indicator of typical usage day, plus total runs as context.
        llPeakDay.removeAllViews()
        val dayFmt = SimpleDateFormat("EEEE", Locale.getDefault())
        val peakCandidates = leafTasks.filter { it.startTimeEpoch > 0 }
            .sortedByDescending { it.runCount }
            .take(8)
        peakCandidates.forEach { task ->
            val day   = dayFmt.format(Date(task.startTimeEpoch))
            val date  = dateFmt.format(Date(task.startTimeEpoch))
            llPeakDay.addView(makeStatRow(
                this,
                primary   = task.name,
                valueText = day,
                meta      = "last run: $date  |  ${task.runCount} total runs",
                accent    = "#4A148C"
            ))
        }
        if (peakCandidates.isEmpty()) llPeakDay.addView(makeEmptyNote(this, "No run history"))
    }

    // ── View factory helpers ──────────────────────────────────────────────────

    /**
     * Horizontal bar row: task name + sublabel on left, filled bar underneath.
     */
    private fun makeBarRow(
        context:     Context,
        label:       String,
        sublabel:    String,
        percent:     Int,
        barColor:    String,
        showDivider: Boolean
    ): View {
        val dp = context.resources.displayMetrics.density
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (10 * dp).toInt() }
        }
        // Label row
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(context).apply {
            text = label
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(context).apply {
            text = sublabel
            textSize = 11f
            setTextColor(Color.parseColor("#757575"))
        })
        root.addView(row)
        // Bar
        val barBg = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (6 * dp).toInt()
            ).also { it.topMargin = (4 * dp).toInt() }
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        }
        val barFill = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                (percent.coerceIn(0, 100) / 100.0 *
                    resources.displayMetrics.widthPixels).toInt().coerceAtLeast((2 * dp).toInt()),
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor(barColor))
        }
        barBg.addView(barFill)
        root.addView(barBg)
        if (showDivider) root.addView(makeDivider(context))
        return root
    }

    /**
     * Simple two-line stat row: name | value (right-aligned), meta subtitle.
     */
    private fun makeStatRow(
        context:   Context,
        primary:   String,
        valueText: String,
        meta:      String,
        accent:    String
    ): View {
        val dp   = context.resources.displayMetrics.density
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (10 * dp).toInt() }
        }
        val topRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(TextView(context).apply {
            text = primary
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(TextView(context).apply {
            text = valueText
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(accent))
        })
        root.addView(topRow)
        root.addView(TextView(context).apply {
            text = meta
            textSize = 11f
            setTextColor(Color.parseColor("#9E9E9E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (1 * dp).toInt() }
        })
        root.addView(makeDivider(context))
        return root
    }

    private fun makeLabelRow(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize  = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#616161"))
            val dp = context.resources.displayMetrics.density
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin    = (6  * dp).toInt()
            lp.bottomMargin = (4  * dp).toInt()
            layoutParams = lp
        }

    private fun makeEmptyNote(context: Context, msg: String): TextView =
        TextView(context).apply {
            text = msg
            textSize = 12f
            setTextColor(Color.parseColor("#BDBDBD"))
            gravity = Gravity.CENTER
            val dp = context.resources.displayMetrics.density
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (4 * dp).toInt(); it.bottomMargin = (4 * dp).toInt() }
        }

    private fun makeDivider(context: Context): View {
        val dp = context.resources.displayMetrics.density
        return View(context).apply {
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).also { it.topMargin = (6 * dp).toInt() }
        }
    }

    // ── Formatting helpers ────────────────────────────────────────────────────

    private fun formatDuration(totalSec: Long): String {
        if (totalSec <= 0L) return "0s"
        var rem = totalSec
        val d = rem / 86_400L; rem %= 86_400L
        val h = rem /  3_600L; rem %=  3_600L
        val m = rem /     60L
        val s = rem %     60L
        val parts = buildList {
            if (d > 0) add("${d}d")
            if (h > 0) add("${h}h")
            if (m > 0) add("${m}m")
            if (s > 0) add("${s}s")
        }
        return parts.take(2).joinToString(" ").ifEmpty { "0s" }
    }

    private fun parseRangeInput(raw: String): Long {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return 0L
        val d   = Regex("""(\d+)\s*d""").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val h   = Regex("""(\d+)\s*h""").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val m   = Regex("""(\d+)\s*m(?!o)""").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val sec = Regex("""(\d+)\s*s""").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val total = d * 86_400L + h * 3_600L + m * 60L + sec
        val bare  = if (d == 0L && h == 0L && m == 0L && sec == 0L) s.toLongOrNull() else null
        val result = bare ?: total
        if (result <= 0L) return 0L
        return result.coerceIn(1L, 365L * 86_400L)
    }

    private fun priorityColor(priority: Int): String = when (priority) {
        1    -> "#9C27B0"
        2    -> "#3F51B5"
        3    -> "#2196F3"
        4    -> "#4CAF50"
        5    -> "#FF9800"
        6    -> "#F57F17"
        7    -> "#F44336"
        else -> "#1565C0"
    }
}
