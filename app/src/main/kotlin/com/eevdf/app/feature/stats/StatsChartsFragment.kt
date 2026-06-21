package com.eevdf.app.feature.stats

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.eevdf.app.R
import com.eevdf.data.runlog.RunLogDao
import com.eevdf.data.task.TaskDao
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.eevdf.data.runlog.RunDailySummary
import com.eevdf.data.runlog.RunLogEntry
import com.eevdf.data.task.Task
import com.github.mikephil.charting.charts.*
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import com.eevdf.app.feature.autoswitch.AutoSwitchPrefs

@AndroidEntryPoint
class StatsChartsFragment : Fragment() {

    @Inject lateinit var taskDao: TaskDao
    @Inject lateinit var runLogDao: RunLogDao

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var etWindowRange:      TextInputEditText
    private lateinit var btnApplyWindow:     MaterialButton
    private lateinit var tvPeriodLabel:      TextView

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
    private var windowSeconds: Long = 7L * 86_400L

    // ── Category constants ────────────────────────────────────────────────────
    companion object {
        const val CAT_INT_A = "INT-A"
        const val CAT_INT_B = "INT-B"
        const val CAT_CALL  = "Call"
        val CATEGORIES      = listOf(CAT_INT_A, CAT_INT_B, CAT_CALL)

        val COLOR_INT_A = Color.parseColor("#E65100")   // deep orange
        val COLOR_INT_B = Color.parseColor("#1565C0")   // deep blue
        val COLOR_CALL  = Color.parseColor("#2E7D32")   // dark green
        val COLORS      = intArrayOf(COLOR_INT_A, COLOR_INT_B, COLOR_CALL)
    }

    // ── Fragment lifecycle ────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_stats_charts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        btnApplyWindow.setOnClickListener {
            val p = parseRangeInput(etWindowRange.text.toString())
            if (p > 0) {
                windowSeconds = p
                tvPeriodLabel.text = "Showing last ${formatDur(windowSeconds)}"
                loadAndRender()
            } else {
                etWindowRange.error = "Invalid range"
            }
        }
        tvPeriodLabel.text = "Showing last ${formatDur(windowSeconds)}"
        loadAndRender()
    }

    private fun bindViews(v: View) {
        etWindowRange     = v.findViewById(R.id.etChartsWindowRange)
        btnApplyWindow    = v.findViewById(R.id.btnChartsApplyWindow)
        tvPeriodLabel     = v.findViewById(R.id.tvChartsPeriodLabel)
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

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadAndRender() {
        val ctx = requireContext()

        // Call-assign task ID from SharedPrefs — on main thread is fine (cached)
        val callTaskId = AutoSwitchPrefs.getCallTaskId(ctx)

        viewLifecycleOwner.lifecycleScope.launch {
            val nowMs  = System.currentTimeMillis()
            val fromMs = nowMs - windowSeconds * 1_000L

            val allTasks   = withContext(Dispatchers.IO) { taskDao.getAllTasksForStats() }
            val logEntries = withContext(Dispatchers.IO) { runLogDao.getEntriesInRange(fromMs, nowMs) }
            val dailyRows  = withContext(Dispatchers.IO) { runLogDao.getDailyInRange(fromMs) }
            val weekdayTot = withContext(Dispatchers.IO) { runLogDao.getGlobalWeekdayTotals() }

            // ── Build category map: taskId → CAT_INT_A / CAT_INT_B / CAT_CALL ──
            // Only leaf tasks that are interrupt or the designated call task
            val categoryMap = mutableMapOf<String, String>()
            for (t in allTasks) {
                if (t.isGroup) continue
                when {
                    t.isInterrupt && t.interruptSlot == "B" -> categoryMap[t.id] = CAT_INT_B
                    t.isInterrupt                           -> categoryMap[t.id] = CAT_INT_A
                    t.id == callTaskId                      -> categoryMap[t.id] = CAT_CALL
                }
            }

            val targetIds  = categoryMap.keys.toHashSet()
            val targetTasks = allTasks.filter { it.id in targetIds }

            if (targetIds.isEmpty()) {
                // No target tasks configured — show all charts empty
                showAllEmpty()
                return@launch
            }

            // Filter log/daily to target tasks only
            val tLog   = logEntries.filter { it.taskId in targetIds }
            val tDaily = dailyRows.filter  { it.taskId in targetIds }

            // Build single source of truth — no double-counting
            val effectiveDaily   = buildEffectiveDaily(tLog, tDaily)
            val effectiveWeekday = buildEffectiveWeekday(tLog, weekdayTot, targetIds)

            renderDailyBarChart(effectiveDaily, categoryMap, fromMs, nowMs)
            renderLineTrendChart(targetTasks, effectiveDaily, categoryMap, fromMs, nowMs)
            renderRunCountChart(targetTasks, effectiveDaily, categoryMap)
            renderRadarChart(effectiveWeekday)
            renderScatterChart(tLog, categoryMap, fromMs)
        }
    }

    private fun showAllEmpty() {
        val charts = listOf(
            barChartDaily to tvBarDailyEmpty,
            lineChartTrend to tvLineEmpty,
            barChartRunCount to tvBarCountEmpty,
            radarChartWeekday to tvRadarEmpty,
            scatterChart to tvScatterEmpty
        )
        charts.forEach { (chart, empty) ->
            chart.visibility = View.GONE
            empty.visibility = View.VISIBLE
            empty.text = "No interrupt/call tasks configured"
        }
    }

    // ── 1. Daily Activity — Stacked Bar (INT-A / INT-B / Call) ───────────────

    private fun renderDailyBarChart(
        effectiveDaily: List<RunDailySummary>,
        categoryMap:    Map<String, String>,
        fromMs: Long,
        nowMs:  Long
    ) {
        val localTz = TimeZone.getDefault()
        val dayMs   = 86_400_000L

        fun sod(ms: Long): Long {
            val c = Calendar.getInstance(localTz); c.timeInMillis = ms
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0);      c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }

        // Aggregate per (day, category)
        val dayMap = mutableMapOf<Long, MutableMap<String, Long>>()
        for (d in effectiveDaily) {
            val cat = categoryMap[d.taskId] ?: continue
            val key = sod(d.dayEpoch)
            dayMap.getOrPut(key) { mutableMapOf() }.merge(cat, d.totalSecs, Long::plus)
        }

        val displayDays = ((nowMs - fromMs) / dayMs + 1L).toInt().coerceIn(1, 60)
        val todaySod    = sod(nowMs)
        val labelFmt    = SimpleDateFormat("d/M", Locale.getDefault())

        // Only include categories that actually have at least one non-zero day
        val activeCategories = CATEGORIES.filter { cat ->
            (0 until displayDays).any { i ->
                val dayEpoch = todaySod - (displayDays - 1 - i) * dayMs
                (dayMap[dayEpoch]?.get(cat) ?: 0L) > 0L
            }
        }

        if (activeCategories.isEmpty()) {
            barChartDaily.visibility   = View.GONE
            tvBarDailyEmpty.visibility = View.VISIBLE
            return
        }
        barChartDaily.visibility   = View.VISIBLE
        tvBarDailyEmpty.visibility = View.GONE

        val labels = (0 until displayDays).map { i ->
            labelFmt.format(Date(todaySod - (displayDays - 1 - i) * dayMs))
        }

        // One BarDataSet per active category — each with one BarEntry per day
        val numCats    = activeCategories.size
        // Spacing formula: (barWidth + barSpace) * numCats + groupSpace = 1.0
        val groupSpace = 0.08f
        val barSpace   = 0.02f
        val barWidth   = (1f - groupSpace) / numCats - barSpace   // ≈ 0.29 for 3 cats

        val dataSets = activeCategories.map { cat ->
            val entries = (0 until displayDays).map { i ->
                val dayEpoch = todaySod - (displayDays - 1 - i) * dayMs
                val hours    = (dayMap[dayEpoch]?.get(cat) ?: 0L)
                    .coerceAtMost(86_400L) / 3_600f
                BarEntry(i.toFloat(), hours)
            }
            BarDataSet(entries, cat).apply {
                color = colorFor(cat)
                setDrawValues(false)
            }
        }

        val barData = BarData(dataSets).apply {
            this.barWidth = barWidth
            // groupBars repositions each dataset's entries to sit side-by-side
            groupBars(0f, groupSpace, barSpace)
        }

        barChartDaily.apply {
            data = barData
            xAxis.apply {
                // Center label under the whole group for each day
                setCenterAxisLabels(true)
                valueFormatter     = IndexAxisValueFormatter(labels)
                position           = XAxis.XAxisPosition.BOTTOM
                granularity        = 1f
                setDrawGridLines(false)
                textSize           = 8f
                labelRotationAngle = -45f
                axisMinimum        = 0f
                axisMaximum        = displayDays.toFloat()
                setLabelCount(minOf(displayDays, 10), true)
            }
            axisLeft.apply {
                axisMinimum    = 0f
                axisMaximum    = 24f
                valueFormatter = HourMinuteFormatter()
            }
            axisRight.isEnabled   = false
            description.isEnabled = false
            legend.apply {
                isEnabled           = true
                textSize            = 10f
                orientation         = Legend.LegendOrientation.HORIZONTAL
                verticalAlignment   = Legend.LegendVerticalAlignment.BOTTOM
                horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                setDrawInside(false)
            }
            setFitBars(false)   // must be false when using groupBars
            animateY(600)
            invalidate()
        }
    }

    // ── 2. Line Chart — Task Trends ───────────────────────────────────────────

    private fun renderLineTrendChart(
        targetTasks:   List<Task>,
        effectiveDaily: List<RunDailySummary>,
        categoryMap:   Map<String, String>,
        fromMs: Long,
        nowMs:  Long
    ) {
        val localTz = TimeZone.getDefault()
        val dayMs   = 86_400_000L

        fun sod(ms: Long): Long {
            val c = Calendar.getInstance(localTz); c.timeInMillis = ms
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }

        val taskIds = targetTasks.map { it.id }
        if (taskIds.isEmpty() || effectiveDaily.size < 2) {
            lineChartTrend.visibility = View.GONE
            tvLineEmpty.visibility    = View.VISIBLE
            return
        }
        lineChartTrend.visibility = View.VISIBLE
        tvLineEmpty.visibility    = View.GONE

        val startDay = sod(effectiveDaily.minOf { it.dayEpoch }.coerceAtLeast(fromMs))
        val endDay   = sod(nowMs)
        val numDays  = ((endDay - startDay) / dayMs + 1).toInt().coerceIn(2, 90)

        // (taskId, localDay) → secs — already deduplicated
        val byTaskDay = effectiveDaily
            .groupBy { it.taskId to sod(it.dayEpoch) }
            .mapValues { (_, rows) -> rows.sumOf { it.totalSecs } }

        val taskById = targetTasks.associateBy { it.id }

        val dataSets = taskIds.mapNotNull { taskId ->
            val cat = categoryMap[taskId] ?: return@mapNotNull null
            val color = colorFor(cat)
            val entries = (0 until numDays).map { i ->
                Entry(i.toFloat(), (byTaskDay[taskId to (startDay + i * dayMs)] ?: 0L) / 3_600f)
            }
            if (entries.all { it.y == 0f }) return@mapNotNull null
            LineDataSet(entries, taskById[taskId]?.name?.take(14) ?: cat).apply {
                this.color = color
                setCircleColor(color)
                lineWidth     = 2f
                circleRadius  = if (numDays <= 30) 3f else 0f
                setDrawCircleHole(false)
                setDrawValues(false)
                mode          = LineDataSet.Mode.CUBIC_BEZIER
                cubicIntensity = 0.2f
            }
        }

        if (dataSets.isEmpty()) {
            lineChartTrend.visibility = View.GONE
            tvLineEmpty.visibility    = View.VISIBLE
            return
        }

        val labelFmt = SimpleDateFormat("d/M", Locale.getDefault())
        val xLabels  = (0 until numDays).map { labelFmt.format(Date(startDay + it * dayMs)) }

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
                axisMinimum    = 0f
                valueFormatter = HourMinuteFormatter()
            }
            axisRight.isEnabled   = false
            description.isEnabled = false
            legend.apply {
                isEnabled           = true
                textSize            = 9f
                orientation         = Legend.LegendOrientation.HORIZONTAL
                verticalAlignment   = Legend.LegendVerticalAlignment.BOTTOM
                horizontalAlignment = Legend.LegendHorizontalAlignment.LEFT
                setDrawInside(false)
            }
            animateX(600)
            invalidate()
        }
    }

    // ── 3. Horizontal Bar — Run Frequency ─────────────────────────────────────

    private fun renderRunCountChart(
        targetTasks:   List<Task>,
        effectiveDaily: List<RunDailySummary>,
        categoryMap:   Map<String, String>
    ) {
        val runsByTask = mutableMapOf<String, Int>()
        effectiveDaily.forEach { d -> runsByTask[d.taskId] = (runsByTask[d.taskId] ?: 0) + d.runCount }

        val sorted = runsByTask.entries
            .filter { it.key in categoryMap }
            .sortedByDescending { it.value }
            .take(10)

        if (sorted.isEmpty()) {
            barChartRunCount.visibility = View.GONE
            tvBarCountEmpty.visibility  = View.VISIBLE
            return
        }
        barChartRunCount.visibility = View.VISIBLE
        tvBarCountEmpty.visibility  = View.GONE

        val taskById   = targetTasks.associateBy { it.id }
        val labels     = sorted.map { (id, _) ->
            val cat  = categoryMap[id] ?: ""
            val name = taskById[id]?.name?.take(12) ?: "[del]"
            "$name ($cat)"
        }
        val barEntries = sorted.mapIndexed { i, (_, runs) -> BarEntry(i.toFloat(), runs.toFloat()) }
        val colors     = sorted.map { (id, _) -> colorFor(categoryMap[id] ?: "") }

        val dataSet = BarDataSet(barEntries, "Runs").apply {
            this.colors = colors
            valueTextSize = 10f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(v: Float) = "${v.toInt()}"
            }
        }

        barChartRunCount.apply {
            data = BarData(dataSet).apply { barWidth = 0.7f }
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(labels)
                position       = XAxis.XAxisPosition.BOTTOM
                granularity    = 1f
                setDrawGridLines(false)
                textSize       = 9f
            }
            axisLeft.apply { axisMinimum = 0f; granularity = 1f }
            axisRight.isEnabled   = false
            description.isEnabled = false
            legend.isEnabled      = false
            setFitBars(true)
            animateX(600)
            invalidate()
        }
    }

    // ── 4. Radar — Weekday Pattern ────────────────────────────────────────────

    private fun renderRadarChart(weekdayTots: List<RunLogDao.WeekdayTotal>) {
        if (weekdayTots.isEmpty()) {
            radarChartWeekday.visibility = View.GONE
            tvRadarEmpty.visibility      = View.VISIBLE
            return
        }
        radarChartWeekday.visibility = View.VISIBLE
        tvRadarEmpty.visibility      = View.GONE

        val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val map      = weekdayTots.associateBy { it.weekDay }
        val maxSecs  = weekdayTots.maxOf { it.totalSecs }.toFloat().coerceAtLeast(1f)
        val entries  = (1..7).map { d ->
            RadarEntry((map[d]?.totalSecs ?: 0L).toFloat() / maxSecs * 100f)
        }

        val dataSet = RadarDataSet(entries, "Activity").apply {
            color     = COLOR_INT_A; fillColor = COLOR_INT_A
            setDrawFilled(true); fillAlpha = 80; lineWidth = 2f
            setDrawValues(false)
            isDrawHighlightCircleEnabled = true
            highlightCircleFillColor = COLOR_INT_A
        }

        radarChartWeekday.apply {
            data = RadarData(dataSet)
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(dayNames)
                textSize = 11f; textColor = Color.parseColor("#424242")
            }
            yAxis.apply {
                axisMinimum = 0f; axisMaximum = 100f
                setLabelCount(4, true); setDrawLabels(false)
            }
            webLineWidth = 1f;         webColor      = Color.parseColor("#E0E0E0")
            webLineWidthInner = 0.75f; webColorInner = Color.parseColor("#E0E0E0")
            webAlpha              = 100
            description.isEnabled = false
            legend.isEnabled      = false
            animateXY(600, 600)
            invalidate()
        }
    }

    // ── 5. Scatter — Session Duration Distribution ────────────────────────────

    private fun renderScatterChart(
        leafLog:     List<RunLogEntry>,
        categoryMap: Map<String, String>,
        _fromMs:     Long
    ) {
        val filtered = leafLog.filter { it.taskId in categoryMap }
        if (filtered.isEmpty()) {
            scatterChart.visibility   = View.GONE
            tvScatterEmpty.visibility = View.VISIBLE
            return
        }
        scatterChart.visibility   = View.VISIBLE
        tvScatterEmpty.visibility = View.GONE

        val startMs = filtered.minOf { it.startEpoch }

        // One scatter dataset per category that has data
        val dataSets = CATEGORIES.mapNotNull { cat ->
            val entries = filtered
                .filter { categoryMap[it.taskId] == cat }
                .map { e ->
                    Entry(
                        (e.startEpoch - startMs) / 86_400_000f,
                        e.durationSecs / 60f           // y = minutes (formatter converts to h:m)
                    )
                }
            if (entries.isEmpty()) return@mapNotNull null
            ScatterDataSet(entries, cat).apply {
                color = colorFor(cat)
                setScatterShape(ScatterChart.ScatterShape.CIRCLE)
                scatterShapeSize = 8f
                setDrawValues(false)
            }
        }

        scatterChart.apply {
            data = ScatterData(dataSets)
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM; granularity = 1f
                setDrawGridLines(false); textSize = 9f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(v: Float) = "${v.toInt()}d"
                }
            }
            axisLeft.apply {
                axisMinimum    = 0f
                valueFormatter = MinuteFormatter()   // y is stored as minutes
            }
            axisRight.isEnabled   = false
            description.isEnabled = false
            legend.apply {
                isEnabled           = true
                textSize            = 9f
                orientation         = Legend.LegendOrientation.HORIZONTAL
                verticalAlignment   = Legend.LegendVerticalAlignment.BOTTOM
                horizontalAlignment = Legend.LegendHorizontalAlignment.LEFT
                setDrawInside(false)
            }
            animateXY(600, 600)
            invalidate()
        }
    }

    // ── Y-axis formatters ─────────────────────────────────────────────────────

    /**
     * For axes where the raw value is in HOURS (float).
     * Zoom-adaptive: shows only the resolution that fits.
     *   24f    → "24h"
     *    2.4f  → "2h 24m"
     *    0.5f  → "30m"
     *    0.083f → "5m"
     */
    private class HourMinuteFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            if (value <= 0f) return "0"
            val totalMins = (value * 60f + 0.5f).toInt()   // round to nearest minute
            val h = totalMins / 60
            val m = totalMins % 60
            return when {
                h == 0 -> "${m}m"
                m == 0 -> "${h}h"
                else   -> "${h}h ${m}m"
            }
        }
    }

    /**
     * For axes where the raw value is in MINUTES (float) — used by scatter chart.
     *   90f  → "1h 30m"
     *   45f  → "45m"
     *    5f  → "5m"
     */
    private class MinuteFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            if (value <= 0f) return "0"
            val totalMins = (value + 0.5f).toInt()
            val h = totalMins / 60
            val m = totalMins % 60
            return when {
                h == 0 -> "${m}m"
                m == 0 -> "${h}h"
                else   -> "${h}h ${m}m"
            }
        }
    }

    // ── Data helpers ──────────────────────────────────────────────────────────

    private fun colorFor(cat: String): Int = when (cat) {
        CAT_INT_A -> COLOR_INT_A
        CAT_INT_B -> COLOR_INT_B
        CAT_CALL  -> COLOR_CALL
        else      -> Color.GRAY
    }

    /**
     * Merges run_log entries + daily rows into one deduplicated list.
     * Each (taskId, localDay) pair appears exactly once — totalSecs is the sum of both sources.
     */
    private fun buildEffectiveDaily(
        logEntries: List<RunLogEntry>,
        dailyRows:  List<RunDailySummary>
    ): List<RunDailySummary> {
        val localTz = TimeZone.getDefault()
        fun sod(ms: Long): Long {
            val c = Calendar.getInstance(localTz); c.timeInMillis = ms
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }

        data class LogAgg(val secs: Long, val count: Int)
        val logAgg = mutableMapOf<Pair<String, Long>, LogAgg>()
        for (e in logEntries) {
            val key  = e.taskId to sod(e.startEpoch)
            val prev = logAgg[key] ?: LogAgg(0L, 0)
            logAgg[key] = LogAgg(prev.secs + e.durationSecs, prev.count + 1)
        }

        val result = mutableMapOf<Pair<String, Long>, RunDailySummary>()
        for (d in dailyRows) {
            val localDay = sod(d.dayEpoch)
            val key      = d.taskId to localDay
            val extra    = logAgg[key]
            result[key]  = RunDailySummary(
                taskId    = d.taskId, dayEpoch  = localDay,
                totalSecs = d.totalSecs + (extra?.secs  ?: 0L),
                runCount  = d.runCount  + (extra?.count ?: 0),
                weekDay   = d.weekDay
            )
        }
        for ((key, agg) in logAgg) {
            if (key !in result) {
                val (taskId, dayEpoch) = key
                val cal = Calendar.getInstance(localTz).also { it.timeInMillis = dayEpoch }
                result[key] = RunDailySummary(
                    taskId    = taskId, dayEpoch  = dayEpoch,
                    totalSecs = agg.secs, runCount = agg.count,
                    weekDay   = cal.get(Calendar.DAY_OF_WEEK)
                )
            }
        }
        return result.values.toList()
    }

    /**
     * Weekday totals, merged with recent log entries, filtered to target task IDs only.
     */
    private fun buildEffectiveWeekday(
        logEntries:  List<RunLogEntry>,
        tots:        List<RunLogDao.WeekdayTotal>,
        targetIds:   Set<String>
    ): List<RunLogDao.WeekdayTotal> {
        val localTz = TimeZone.getDefault()
        fun wd(e: RunLogEntry): Int {
            if (e.weekDay > 0) return e.weekDay
            return Calendar.getInstance(localTz).also { it.timeInMillis = e.startEpoch }
                .get(Calendar.DAY_OF_WEEK)
        }
        val merged = tots.associateBy { it.weekDay }.toMutableMap()
        logEntries.filter { it.taskId in targetIds }
            .groupBy { wd(it) }
            .forEach { (day, rows) ->
                val ex = merged[day]
                merged[day] = if (ex == null)
                    RunLogDao.WeekdayTotal(day, rows.sumOf { it.durationSecs }, rows.size)
                else RunLogDao.WeekdayTotal(
                    weekDay   = day,
                    totalSecs = ex.totalSecs + rows.sumOf { it.durationSecs },
                    runCount  = ex.runCount  + rows.size
                )
            }
        return merged.values.sortedBy { it.weekDay }
    }

    // ── Input / formatting helpers ────────────────────────────────────────────

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
}
