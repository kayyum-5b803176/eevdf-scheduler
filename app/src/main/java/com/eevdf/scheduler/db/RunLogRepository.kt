package com.eevdf.scheduler.db

import android.content.Context
import com.eevdf.scheduler.model.RunDailySummary
import com.eevdf.scheduler.model.RunLogEntry
import com.eevdf.scheduler.model.RunMonthlySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

/**
 * Manages the three-tier RunLog storage system:
 *
 *   Tier 1 – run_log       per-run detail    30-day TTL   100K row cap
 *   Tier 2 – run_daily     daily aggregates  365-day TTL  500K row cap
 *   Tier 3 – run_monthly   monthly totals    forever      ~18 MB worst-case
 *
 * Compaction is triggered lazily on every [recordRun] call but throttled to at
 * most once every 24 hours via a SharedPreferences timestamp — so it never runs
 * on the hot path during rapid task switching.
 *
 * Storage budget proof (10K tasks, 100 years):
 *   run_log     100K × 120 bytes ≈  12 MB
 *   run_daily   500K ×  80 bytes ≈  40 MB
 *   run_monthly ~240K × 77 bytes ≈  18 MB   (200 active tasks/month avg)
 *   Total                         ≈  70 MB  (well under 256 MB)
 */
class RunLogRepository(context: Context) {

    private val dao    = TaskDatabase.getDatabase(context).runLogDao()
    private val prefs  = context.getSharedPreferences("run_log_prefs", Context.MODE_PRIVATE)
    private val utc    = TimeZone.getTimeZone("UTC")

    companion object {
        private const val PREF_LAST_COMPACT = "last_compact_epoch"
        private const val COMPACT_INTERVAL_MS = 24L * 60 * 60 * 1_000  // 24 hours
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Records one completed timer session and triggers throttled compaction.
     *
     * @param taskId      The task that ran.
     * @param startEpoch  Epoch ms when the session started.
     * @param durationSecs How long it lasted.
     */
    suspend fun recordRun(taskId: String, startEpoch: Long, durationSecs: Long) =
        withContext(Dispatchers.IO) {
            val prevEntry  = dao.getLatestEntry()
            val prevTaskId = resolvePrevTask(prevEntry, startEpoch)
            val weekDay    = weekDayOf(startEpoch)

            dao.insertLog(RunLogEntry(
                taskId       = taskId,
                startEpoch   = startEpoch,
                durationSecs = durationSecs,
                prevTaskId   = prevTaskId,
                weekDay      = weekDay
            ))

            // Enforce hard cap — keep newest rows
            val count = dao.count()
            if (count > RunLogEntry.MAX_ROWS) {
                dao.deleteOldest(count - RunLogEntry.MAX_ROWS)
            }

            maybeCompact()
        }

    // ── Compaction ────────────────────────────────────────────────────────────

    /**
     * Runs the full two-stage compaction pipeline if 24 h have elapsed since the
     * last run.  Safe to call frequently — the timestamp check is O(1).
     */
    private suspend fun maybeCompact() {
        val now  = System.currentTimeMillis()
        val last = prefs.getLong(PREF_LAST_COMPACT, 0L)
        if (now - last < COMPACT_INTERVAL_MS) return

        compactLogToDaily()
        compactDailyToMonthly()

        prefs.edit().putLong(PREF_LAST_COMPACT, now).apply()
    }

    /**
     * Compacts run_log entries older than 30 days into run_daily.
     * Groups by (taskId, calendar day), then deletes the source rows.
     */
    private suspend fun compactLogToDaily() {
        val cutoff  = System.currentTimeMillis() - RunLogEntry.TTL_DAYS * 86_400_000L
        val entries = dao.getEntriesOlderThan(cutoff)
        if (entries.isEmpty()) return

        // Group by (taskId, dayEpoch)
        data class Key(val taskId: String, val dayEpoch: Long)
        data class Acc(var totalSecs: Long, var runCount: Int, var switchInCount: Int, val weekDay: Int)

        val groups = mutableMapOf<Key, Acc>()
        for (e in entries) {
            val day = startOfDayEpoch(e.startEpoch)
            val key = Key(e.taskId, day)
            val acc = groups.getOrPut(key) { Acc(0L, 0, 0, weekDayOf(e.startEpoch)) }
            acc.totalSecs += e.durationSecs
            acc.runCount  += 1
            // Count as a switch-in if a *different* task was running just before
            if (e.prevTaskId != null && e.prevTaskId != e.taskId) acc.switchInCount += 1
        }

        for ((key, acc) in groups) {
            // Merge into existing row if present (REPLACE strategy handles upsert)
            val existing = dao.getDailyForTask(key.taskId)
                .firstOrNull { it.dayEpoch == key.dayEpoch }
            val merged = RunDailySummary(
                taskId       = key.taskId,
                dayEpoch     = key.dayEpoch,
                totalSecs    = (existing?.totalSecs ?: 0L) + acc.totalSecs,
                runCount     = (existing?.runCount  ?: 0)  + acc.runCount,
                switchInCount= (existing?.switchInCount ?: 0) + acc.switchInCount,
                weekDay      = acc.weekDay
            )
            dao.upsertDaily(merged)
        }

        dao.deleteEntriesOlderThan(cutoff)

        // Enforce daily cap
        val dailyCount = dao.countDaily()
        if (dailyCount > RunDailySummary.MAX_ROWS) {
            dao.deleteOldestDaily(dailyCount - RunDailySummary.MAX_ROWS)
        }
    }

    /**
     * Compacts run_daily entries older than 365 days into run_monthly,
     * then deletes the source rows.
     */
    private suspend fun compactDailyToMonthly() {
        val cutoff = System.currentTimeMillis() - RunDailySummary.TTL_DAYS * 86_400_000L
        val rows   = dao.getDailyOlderThan(cutoff)
        if (rows.isEmpty()) return

        data class Key(val taskId: String, val monthEpoch: Long)
        data class Acc(var totalSecs: Long, var runCount: Int)

        val groups = mutableMapOf<Key, Acc>()
        for (r in rows) {
            val month = startOfMonthEpoch(r.dayEpoch)
            val key   = Key(r.taskId, month)
            val acc   = groups.getOrPut(key) { Acc(0L, 0) }
            acc.totalSecs += r.totalSecs
            acc.runCount  += r.runCount
        }

        for ((key, acc) in groups) {
            val existing = dao.getMonthlyForTask(key.taskId)
                .firstOrNull { it.monthEpoch == key.monthEpoch }
            dao.upsertMonthly(RunMonthlySummary(
                taskId     = key.taskId,
                monthEpoch = key.monthEpoch,
                totalSecs  = (existing?.totalSecs ?: 0L) + acc.totalSecs,
                runCount   = (existing?.runCount  ?: 0)  + acc.runCount
            ))
        }

        dao.deleteDailyOlderThan(cutoff)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the previous task ID if the gap between the previous session's
     * end and [currentStartEpoch] is within [RunLogEntry.SWITCH_GAP_THRESHOLD_MS].
     * Gaps larger than the threshold are not counted as context switches
     * (the user stepped away from the app entirely).
     */
    private fun resolvePrevTask(prev: RunLogEntry?, currentStartEpoch: Long): String? {
        if (prev == null) return null
        val prevEndEpoch = prev.startEpoch + prev.durationSecs * 1_000L
        val gap = currentStartEpoch - prevEndEpoch
        return if (gap in 0..RunLogEntry.SWITCH_GAP_THRESHOLD_MS) prev.taskId else null
    }

    /** Epoch ms of the UTC midnight at the start of the day containing [epochMs]. */
    private fun startOfDayEpoch(epochMs: Long): Long {
        val cal = Calendar.getInstance(utc)
        cal.timeInMillis = epochMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Epoch ms of the UTC midnight of the 1st of the month containing [epochMs]. */
    private fun startOfMonthEpoch(epochMs: Long): Long {
        val cal = Calendar.getInstance(utc)
        cal.timeInMillis = epochMs
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun weekDayOf(epochMs: Long): Int {
        val cal = Calendar.getInstance(utc)
        cal.timeInMillis = epochMs
        return cal.get(Calendar.DAY_OF_WEEK)   // 1=Sun … 7=Sat
    }
}
