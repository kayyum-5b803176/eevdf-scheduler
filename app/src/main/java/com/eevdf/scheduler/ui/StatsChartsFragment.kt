package com.eevdf.scheduler.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.eevdf.scheduler.R
import com.eevdf.scheduler.db.TaskDatabase
import com.eevdf.scheduler.model.RunDailySummary
import com.eevdf.scheduler.model.RunLogEntry
import com.eevdf.scheduler.model.Task
import com.github.mikephil.charting.charts.*
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class StatsChartsFragment : Fragment() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var chipGroupPeriod:    ChipGroup
    private lateinit var tvPeriodLabel:      TextView

    private lateinit var pieChart:           PieChart
    private lateinit var tvPieEmpty:         TextView

    private lateinit var barChartDaily:      BarChart
    private lateinit var tvBarDailyEmpty:    TextView

    private lateinit var lineChartTrend:     LineChart
    private lateinit var tvLineEmpty:        TextView

    private lateinit var barChartRunCount:   HorizontalBarChart
    private lateinit var tvBarCountEmpty:    TextView

    private lateinit var radarChartWeekday:  RadarChart
    private lateinit var tvRadarEmpty:       TextView

    private lateinit var scatterChart:       ScatterChart
    private lateinit var tvScatterEmpty:     TextView

    // ── State ─────────────────────────────────────────────────────────────────
    private var periodDays: Int = 7      // 7 | 30 | 90 | Int.MAX_VALUE (all)

    // ── Palette: 10 distinct colors ───────────────────────────────────────────
    private val palette = intArrayOf(
        Color.parseColor("#1565C0"),
        Color.parseColor("#E65100"),
        Color.parseColor("#1B5E20"),
        Color.parseColor("#880E4F"),
        Color.parseColor("#006064"),
        Color.parseColor("#4A148C"),
        Color.parseColor("#F57F17"),
        Color.parseColor("#37474F"),
        Color.parseColor("#B71C1C"),
        Color.parseColor("#33691E")
    )

    // ── Fragment lifecycle ────────────────────────────────────────────────────

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_stats_charts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupChipGroup()
        loadAndRender()
    }

    private fun bindViews(v: View) {
        chipGroupPeriod   = v.findViewById(R.id.chipGroupPeriod)
        tvPeriodLabel     = v.findViewById(R.id.tvChartsPeriodLabel)
        pieChart          = v.findViewById(R.id.pieChart)
        tvPieEmpty        = v.findViewById(R.id.tvPieEmpty)
        barChartDaily     = v.findViewById(R.id.barChartDaily)
        tvBarDailyEmpty   = v.findViewById(R.id.tvBarDailyEmpty)
        lineChartTrend    = v.findViewById(R.id.lineChartTrend)
        tvLineEmpty       = v.findViewById(R.id.tvLineEmpty)
        barChartRunCount  = v.findViewById(R.id.barChartRunCount)
        tvBarCountEmpty   = v.findViewById(R.id.tvBarCountEmpty)
        radarChartWeekday = v.findViewById(R.id.radarChartWeekday)
        tvRadarEmpty      = v.findViewById(R.id.tvRadarEmpty)
        scatterChart      = v.findViewById(R.id.scatterChart)
        tvScatterEmpty    = v.findViewById(R.id.tvScatterEmpty)
    }

    private fun setupChipGroup() {
        chipGroupPeriod.setOnCheckedStateChangeListener { _, checkedIds ->
            periodDays = when (checkedIds.firstOrNull()) {
                R.id.chip7d  -> 7
                R.id.chip30d -> 30
                R.id.chip90d -> 90
                R.id.chipAll -> Int.MAX_VALUE
                else          -> 7
            }
            tvPeriodLabel.text = if (periodDays == Int.MAX_VALUE) "Showing all time"
                                 else "Showing last $periodDays days"
            loadAndRender()
        }
    }

    // ── Data loading ─────────────────────────────────────────────────────────

    private fun loadAndRender() {
        val db  = TaskDatabase.getDatabase(requireContext())
        val rld = db.runLogDao()
        val td  = db.taskDao()

        viewLifecycleOwner.lifecycleScope.launch {
            val nowMs   = System.currentTimeMillis()
            val fromMs  = if (periodDays == Int.MAX_VALUE) 0L
                          else nowMs - periodDays * 86_400_000L

            val tasks      = withContext(Dispatchers.IO) { td.getAllTasksForStats() }
            val logEntries = withContext(Dispatchers.IO) { rld.getEntriesInRange(fromMs, nowMs) }
            val dailyRows  = withContext(Dispatchers.IO) { rld.getDailyInRange(fromMs) }
            val weekdayTot = withContext(Dispatchers.IO) { rld.getGlobalWeekdayTotals() }

            // Build effective daily: merge recent log entries into daily summaries
            val effectiveDaily = buildEffectiveDaily(logEntries, dailyRows)
            // Build effective weekday from both sources
            val effectiveWeekday = buildEffectiveWeekday(logEntries, weekdayTot)

            renderPieChart(tasks, logEntries, effectiveDaily)
            renderDailyBarChart(logEntries, effectiveDaily, fromMs, nowMs)
            renderLineTrendChart(tasks, effectiveDaily, fromMs, nowMs)
            renderRunCountChart(tasks, logEntries, effectiveDaily)
            renderRadarChart(effectiveWeekday)
            renderScatterChart(logEntries, fromMs)
        }
    }

    // ── 1. Pie Chart — Time Distribution ─────────────────────────────────────

    private fun renderPieChart(
        tasks: List<Task>, logEntries: List<RunLogEntry>, daily: List<RunDailySummary>
    ) {
        // Aggregate secs per taskId from log entries + daily summaries
        val secs = aggregateSecsByTask(logEntries, daily)
        val taskById = tasks.associateBy { it.id }
        val sorted = secs.entries.sortedByDescending { it.value }.take(10)

        if (sorted.isEmpty()) {
            pieChart.visibility  = View.GONE
            tvPieEmpty.visibility = View.VISIBLE
            return
        }
        pieChart.visibility   = View.VISIBLE
        tvPieEmpty.visibility = View.GONE

        val entries = sorted.mapIndexed { i, (taskId, totalSecs) ->
            PieEntry(totalSecs.toFloat(), taskById[taskId]?.name?.take(16) ?: "[deleted]")
        }
        val dataSet = PieDataSet(entries, "").apply {
            colors          = palette.toList()
            valueTextSize   = 10f
            valueTextColor  = Color.WHITE
            sliceSpace      = 2f
            selectionShift  = 6f
            valueFormatter  = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String =
                    if (value >= 1f) "${value.toInt()}%" else ""
            }
        }
        val total = sorted.sumOf { it.value }.toFloat().coerceAtLeast(1f)
        dataSet.setValueFormatter(object : ValueFormatter() {
            override fun getPieLabel(value: Float, pieEntry: PieEntry?): String {
                val pct = (value / total * 100f).toInt()
                return if (pct >= 5) "$pct%" else ""
            }
        })

        pieChart.apply {
            data                 = PieData(dataSet).apply { setValueTextSize(10f) }
            isDrawHoleEnabled    = true
            holeRadius           = 38f
            transparentCircleRadius = 43f
            setHoleColor(Color.WHITE)
            description.isEnabled = false
            setEntryLabelColor(Color.WHITE)
            setEntryLabelTextSize(10f)
            legend.apply {
                isEnabled        = true
                orientation      = Legend.LegendOrientation.VERTICAL
                horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT
                verticalAlignment   = Legend.LegendVerticalAlignment.CENTER
                setDrawInside(false)
                textSize         = 10f
            }
            animateY(800)
            invalidate()
        }
    }

    // ── 2. Bar Chart — Daily Activity ─────────────────────────────────────────

    private fun renderDailyBarChart(
        logEntries: List<RunLogEntry>, daily: List<RunDailySummary>,
        fromMs: Long, nowMs: Long
    ) {
        val utc   = TimeZone.getTimeZone("UTC")
        val dayMs = 86_400_000L

        // Build map: startOfDay → totalSecs
        val dayMap = mutableMapOf<Long, Long>()
        fun sod(ms: Long): Long {
            val c = Calendar.getInstance(utc).also { it.timeInMillis = ms }
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
        daily.forEach { d -> dayMap[d.dayEpoch] = (dayMap[d.dayEpoch] ?: 0L) + d.totalSecs }
        logEntries.forEach { e ->
            val key = sod(e.startEpoch)
            dayMap[key] = (dayMap[key] ?: 0L) + e.durationSecs
        }

        // Build a contiguous day list
        val displayDays = minOf(if (fromMs == 0L) 60 else ((nowMs - fromMs) / dayMs + 1).toInt(), 60)
        val labels = mutableListOf<String>()
        val barEntries = mutableListOf<BarEntry>()
        val labelFmt = SimpleDateFormat("d/M", Locale.getDefault())
        for (i in 0 until displayDays) {
            val dayEpoch = sod(nowMs) - (displayDays - 1 - i) * dayMs
            val hours    = (dayMap[dayEpoch] ?: 0L) / 3600f
            barEntries.add(BarEntry(i.toFloat(), hours))
            labels.add(labelFmt.format(Date(dayEpoch)))
        }

        val hasData = barEntries.any { it.y > 0 }
        if (!hasData) {
            barChartDaily.visibility   = View.GONE
            tvBarDailyEmpty.visibility = View.VISIBLE
            return
        }
        barChartDaily.visibility   = View.VISIBLE
        tvBarDailyEmpty.visibility = View.GONE

        val dataSet = BarDataSet(barEntries, "Hours").apply {
            colors      = barEntries.map { if (it.y > 0) palette[0] else Color.parseColor("#E0E0E0") }
            valueTextSize = 0f           // hide values to reduce clutter
        }

        barChartDaily.apply {
            data = BarData(dataSet).apply { barWidth = 0.8f }
            xAxis.apply {
                valueFormatter  = IndexAxisValueFormatter(labels)
                position        = XAxis.XAxisPosition.BOTTOM
                granularity     = 1f
                setDrawGridLines(false)
                textSize        = 8f
                labelRotationAngle = -45f
                setLabelCount(minOf(labels.size, 10), true)
            }
            axisLeft.apply {
                axisMinimum = 0f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(v: Float) = "${v.toInt()}h"
                }
            }
            axisRight.isEnabled   = false
            description.isEnabled = false
            legend.isEnabled      = false
            setFitBars(true)
            animateY(600)
            invalidate()
        }
    }

    // ── 3. Line Chart — Task Trends ───────────────────────────────────────────

    private fun renderLineTrendChart(
        tasks: List<Task>, daily: List<RunDailySummary>, fromMs: Long, nowMs: Long
    ) {
        val utc   = TimeZone.getTimeZone("UTC")
        val dayMs = 86_400_000L
        fun sod(ms: Long): Long {
            val c = Calendar.getInstance(utc).also { it.timeInMillis = ms }
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }

        // Top 5 tasks by total secs in period
        val secsByTask = daily.groupBy { it.taskId }
            .mapValues { (_, rows) -> rows.sumOf { it.totalSecs } }
            .entries.sortedByDescending { it.value }.take(5)
        val taskIds = secsByTask.map { it.key }

        if (taskIds.isEmpty() || daily.size < 2) {
            lineChartTrend.visibility = View.GONE
            tvLineEmpty.visibility    = View.VISIBLE
            return
        }
        lineChartTrend.visibility = View.VISIBLE
        tvLineEmpty.visibility    = View.GONE

        val startDay = sod(if (fromMs == 0L) (daily.minOfOrNull { it.dayEpoch } ?: nowMs) else fromMs)
        val endDay   = sod(nowMs)
        val numDays  = ((endDay - startDay) / dayMs + 1).toInt().coerceIn(2, 90)

        // For each task, build (dayIndex → hours) map
        val dailyByTaskDay = daily.groupBy { it.taskId to sod(it.dayEpoch) }
            .mapValues { (_, rows) -> rows.sumOf { it.totalSecs } }

        val taskById = tasks.associateBy { it.id }
        val dataSets = taskIds.mapIndexed { colorIdx, taskId ->
            val entries = (0 until numDays).mapNotNull { dayOffset ->
                val dayEpoch = startDay + dayOffset * dayMs
                val secs     = dailyByTaskDay[taskId to dayEpoch] ?: 0L
                Entry(dayOffset.toFloat(), secs / 3600f)
            }
            LineDataSet(entries, taskById[taskId]?.name?.take(14) ?: "[deleted]").apply {
                color            = palette[colorIdx % palette.size]
                setCircleColor(palette[colorIdx % palette.size])
                lineWidth        = 2f
                circleRadius     = if (numDays <= 30) 3f else 0f
                setDrawCircleHole(false)
                setDrawValues(false)
                mode             = LineDataSet.Mode.CUBIC_BEZIER
                cubicIntensity   = 0.2f
            }
        }

        // X-axis labels: show ~6 labels
        val labelFmt = SimpleDateFormat("d/M", Locale.getDefault())
        val xLabels = (0 until numDays).map { i ->
            labelFmt.format(Date(startDay + i * dayMs))
        }

        lineChartTrend.apply {
            data = LineData(dataSets)
            xAxis.apply {
                valueFormatter     = IndexAxisValueFormatter(xLabels)
                position           = XAxis.XAxisPosition.BOTTOM
                granularity        = 1f
                setDrawGridLines(false)
                textSize           = 8f
                labelRotationAngle = -45f
                setLabelCount(minOf(numDays, 7), true)
            }
            axisLeft.apply {
                axisMinimum = 0f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(v: Float) = "${v.toInt()}h"
                }
            }
            axisRight.isEnabled   = false
            description.isEnabled = false
            legend.apply {
                isEnabled      = true
                textSize       = 9f
                orientation    = Legend.LegendOrientation.HORIZONTAL
                verticalAlignment   = Legend.LegendVerticalAlignment.BOTTOM
                horizontalAlignment = Legend.LegendHorizontalAlignment.LEFT
                setDrawInside(false)
            }
            animateX(600)
            invalidate()
        }
    }

    // ── 4. Horizontal Bar — Run Count per Task ───────────────────────────────

    private fun renderRunCountChart(
        tasks: List<Task>, logEntries: List<RunLogEntry>, daily: List<RunDailySummary>
    ) {
        // Runs per task: log entries (each is a run) + daily runCount (compacted)
        val runsByTask = mutableMapOf<String, Int>()
        logEntries.forEach { e -> runsByTask[e.taskId] = (runsByTask[e.taskId] ?: 0) + 1 }
        daily.forEach { d -> runsByTask[d.taskId] = (runsByTask[d.taskId] ?: 0) + d.runCount }

        val sorted = runsByTask.entries.sortedByDescending { it.value }.take(10)
        if (sorted.isEmpty()) {
            barChartRunCount.visibility  = View.GONE
            tvBarCountEmpty.visibility   = View.VISIBLE
            return
        }
        barChartRunCount.visibility  = View.VISIBLE
        tvBarCountEmpty.visibility   = View.GONE

        val taskById = tasks.associateBy { it.id }
        val labels   = sorted.map { (id, _) -> taskById[id]?.name?.take(16) ?: "[del]" }
        val barEntries = sorted.mapIndexed { i, (_, runs) ->
            BarEntry(i.toFloat(), runs.toFloat())
        }

        val dataSet = BarDataSet(barEntries, "Runs").apply {
            colors    = sorted.indices.map { palette[it % palette.size] }
            valueTextSize = 10f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(v: Float) = "${v.toInt()}"
            }
        }

        barChartRunCount.apply {
            data = BarData(dataSet).apply { barWidth = 0.7f }
            xAxis.apply {
                valueFormatter  = IndexAxisValueFormatter(labels)
                position        = XAxis.XAxisPosition.BOTTOM
                granularity     = 1f
                setDrawGridLines(false)
                textSize        = 9f
            }
            axisLeft.apply {
                axisMinimum  = 0f
                granularity  = 1f
            }
            axisRight.isEnabled   = false
            description.isEnabled = false
            legend.isEnabled      = false
            setFitBars(true)
            animateX(600)
            invalidate()
        }
    }

    // ── 5. Radar Chart — Weekday Pattern ─────────────────────────────────────

    private fun renderRadarChart(weekdayTots: List<com.eevdf.scheduler.db.RunLogDao.WeekdayTotal>) {
        if (weekdayTots.isEmpty()) {
            radarChartWeekday.visibility = View.GONE
            tvRadarEmpty.visibility      = View.VISIBLE
            return
        }
        radarChartWeekday.visibility = View.VISIBLE
        tvRadarEmpty.visibility      = View.GONE

        val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val map      = weekdayTots.associateBy { it.weekDay }   // 1=Sun … 7=Sat
        val maxSecs  = weekdayTots.maxOf { it.totalSecs }.toFloat().coerceAtLeast(1f)

        // Radar needs all 7 entries (fill missing with 0)
        val radarEntries = (1..7).map { d ->
            RadarEntry((map[d]?.totalSecs ?: 0L).toFloat() / maxSecs * 100f)
        }

        val dataSet = RadarDataSet(radarEntries, "Activity").apply {
            color         = palette[0]
            fillColor     = palette[0]
            setDrawFilled(true)
            fillAlpha     = 80
            lineWidth     = 2f
            setDrawValues(false)
            setDrawHighlightCircleEnabled(true)
            highlightCircleFillColor = palette[0]
        }

        radarChartWeekday.apply {
            data = RadarData(dataSet)
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(dayNames)
                textSize       = 11f
                textColor      = Color.parseColor("#424242")
            }
            yAxis.apply {
                axisMinimum   = 0f
                axisMaximum   = 100f
                setLabelCount(4, true)
                setDrawLabels(false)
            }
            webLineWidth          = 1f
            webColor              = Color.parseColor("#E0E0E0")
            webLineWidthInner     = 0.75f
            webColorInner         = Color.parseColor("#E0E0E0")
            webAlpha              = 100
            description.isEnabled = false
            legend.isEnabled      = false
            animateXY(600, 600)
            invalidate()
        }
    }

    // ── 6. Scatter Chart — Session Duration Distribution ─────────────────────

    private fun renderScatterChart(logEntries: List<RunLogEntry>, fromMs: Long) {
        if (logEntries.isEmpty()) {
            scatterChart.visibility   = View.GONE
            tvScatterEmpty.visibility = View.VISIBLE
            return
        }
        scatterChart.visibility   = View.VISIBLE
        tvScatterEmpty.visibility = View.GONE

        // Group by taskId (up to top 8 tasks) and plot each as a separate dataset
        val taskGroups = logEntries.groupBy { it.taskId }
            .entries.sortedByDescending { it.value.size }.take(8)

        val startMs = logEntries.minOf { it.startEpoch }

        val dataSets = taskGroups.mapIndexed { idx, (taskId, entries) ->
            val scatterEntries = entries.map { e ->
                val dayOffset = ((e.startEpoch - startMs) / 86_400_000f)
                Entry(dayOffset, e.durationSecs / 60f)   // y = minutes
            }
            ScatterDataSet(scatterEntries, taskId.take(8)).apply {
                color       = palette[idx % palette.size]
                setScatterShape(ScatterChart.ScatterShape.CIRCLE)
                scatterShapeSize = 8f
                setDrawValues(false)
            }
        }

        scatterChart.apply {
            data = ScatterData(dataSets)
            xAxis.apply {
                position        = XAxis.XAxisPosition.BOTTOM
                granularity     = 1f
                setDrawGridLines(false)
                textSize        = 9f
                valueFormatter  = object : ValueFormatter() {
                    override fun getFormattedValue(v: Float) = "${v.toInt()}d"
                }
            }
            axisLeft.apply {
                axisMinimum   = 0f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(v: Float) = "${v.toInt()}m"
                }
            }
            axisRight.isEnabled   = false
            description.isEnabled = false
            legend.isEnabled      = false
            animateXY(600, 600)
            invalidate()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Merge log entries + daily summaries into taskId → totalSecs. */
    private fun aggregateSecsByTask(
        logEntries: List<RunLogEntry>, daily: List<RunDailySummary>
    ): Map<String, Long> {
        val secs = mutableMapOf<String, Long>()
        logEntries.forEach { e -> secs[e.taskId] = (secs[e.taskId] ?: 0L) + e.durationSecs }
        daily.forEach { d -> secs[d.taskId] = (secs[d.taskId] ?: 0L) + d.totalSecs }
        return secs
    }

    private fun buildEffectiveDaily(
        logEntries: List<RunLogEntry>, daily: List<RunDailySummary>
    ): List<RunDailySummary> {
        val utc = TimeZone.getTimeZone("UTC")
        fun sod(ms: Long): Long {
            val c = Calendar.getInstance(utc).also { it.timeInMillis = ms }
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
        val existing = daily.map { it.taskId to it.dayEpoch }.toHashSet()
        val synthetic = logEntries
            .groupBy { it.taskId to sod(it.startEpoch) }
            .filter { (k, _) -> k !in existing }
            .map { (k, rows) ->
                val (taskId, dayEpoch) = k
                val cal = Calendar.getInstance(utc).also { it.timeInMillis = dayEpoch }
                RunDailySummary(
                    taskId   = taskId, dayEpoch = dayEpoch,
                    totalSecs = rows.sumOf { it.durationSecs }, runCount = rows.size,
                    weekDay  = cal.get(Calendar.DAY_OF_WEEK)
                )
            }
        return daily + synthetic
    }

    private fun buildEffectiveWeekday(
        logEntries: List<RunLogEntry>,
        tots: List<com.eevdf.scheduler.db.RunLogDao.WeekdayTotal>
    ): List<com.eevdf.scheduler.db.RunLogDao.WeekdayTotal> {
        val utc = TimeZone.getTimeZone("UTC")
        fun wd(e: RunLogEntry): Int {
            if (e.weekDay > 0) return e.weekDay
            return Calendar.getInstance(utc).also { it.timeInMillis = e.startEpoch }
                .get(Calendar.DAY_OF_WEEK)
        }
        val fromLog = logEntries.groupBy { wd(it) }
            .map { (day, rows) ->
                com.eevdf.scheduler.db.RunLogDao.WeekdayTotal(
                    weekDay = day, totalSecs = rows.sumOf { it.durationSecs }, runCount = rows.size)
            }
        val merged = tots.associateBy { it.weekDay }.toMutableMap()
        fromLog.forEach { row ->
            val ex = merged[row.weekDay]
            merged[row.weekDay] = if (ex == null) row else
                com.eevdf.scheduler.db.RunLogDao.WeekdayTotal(
                    weekDay = row.weekDay,
                    totalSecs = ex.totalSecs + row.totalSecs,
                    runCount = ex.runCount + row.runCount)
        }
        return merged.values.sortedBy { it.weekDay }
    }
}
