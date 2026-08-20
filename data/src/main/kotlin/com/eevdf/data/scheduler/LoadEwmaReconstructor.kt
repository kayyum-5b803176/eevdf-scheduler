package com.eevdf.data.scheduler

import com.eevdf.data.runlog.RunDailySummary
import com.eevdf.data.runlog.RunLogEntry
import kotlin.math.exp

/**
 * Historical EWMA reconstruction for the load average chart — Option B.
 *
 * ── Why Option B ─────────────────────────────────────────────────────────────
 * Option A (replay from slider values) is accurate only within the 30-day
 * run_log retention window and depends on current slider values that the user
 * may have changed since those sessions ran.
 *
 * Option B stores a snapshot of the three EWMA values at the end of every
 * session directly in the [RunLogEntry] row, and carries the last session's
 * snapshot into [RunDailySummary] during compaction.  This gives:
 *
 *   Tier 1 — [RunLogEntry.loadSnapshot*]     0–30 days   session precision
 *   Tier 2 — [RunDailySummary.loadSnapshot*] 30–365 days day precision
 *   Tier 3 — beyond 365 days                 state is 0  (τ_emotional = 60 h;
 *                                             10 × τ ≈ 25 days → effectively 0)
 *
 * ── Reconstruction algorithm ─────────────────────────────────────────────────
 * For any sample time T:
 *   1. Find the most recent anchor whose epochMs ≤ T (either a session-end
 *      snapshot or a day-end snapshot from the daily tier).
 *   2. Apply idle decay from anchor.epochMs to T for each dimension at its
 *      own τ (Cognitive 6 h, Physical 18 h, Emotional 60 h).
 *   3. Record the decayed state as the sample at T.
 *
 * Between sessions (idle gaps) this is exact.  Within a running session, the
 * sample uses the previous anchor (slightly underestimating the mid-session
 * state), then steps up to the session-end snapshot at the session's end epoch.
 * The net visual effect is a smooth rise–and–decay pattern for each session
 * cluster, which is the correct human interpretation.
 *
 * ── No slider values at chart time ───────────────────────────────────────────
 * The reconstructor requires no [TaskLoadFactor] data at chart generation time.
 * Slider values were baked into the snapshot at the moment each session ended.
 * This means charts remain historically correct even if the user later edits
 * the slider configuration.
 */
object LoadEwmaReconstructor {

    // ── Output type ───────────────────────────────────────────────────────────

    /**
     * A single time-stamped load snapshot, all values on the 0–100 scale.
     *
     * @param epochMs   Wall-clock ms when this sample was taken.
     * @param cognitive Cognitive EWMA as % of max slider (0–100).
     * @param physical  Physical EWMA as % of max slider (0–100).
     * @param emotional Emotional EWMA as % of max slider (0–100).
     * @param combined  Composite load via [LoadAverage.combinedLoad], 0–100.
     */
    data class LoadSample(
        val epochMs:   Long,
        val cognitive: Float,
        val physical:  Float,
        val emotional: Float,
        val combined:  Float,
    )

    // ── Internal anchor ───────────────────────────────────────────────────────

    /**
     * A single EWMA state anchor at a known point in time.
     * Built from either a [RunLogEntry] (session-level) or a [RunDailySummary]
     * (day-level).  The reconstruction engine treats both identically.
     */
    private data class Anchor(
        val epochMs:   Long,    // when this state was observed
        val cognitive: Double,  // 0–7 raw EWMA value
        val physical:  Double,
        val emotional: Double,
    ) {
        /** True if this anchor carries real data (non-zero in at least one dimension). */
        val hasData: Boolean get() = cognitive > 0.0 || physical > 0.0 || emotional > 0.0
    }

    // ── Reconstruction ────────────────────────────────────────────────────────

    /**
     * Reconstructs the system EWMA time series for [windowStartMs]…[nowMs].
     *
     * @param logEntries    Recent run_log entries (within or just before the window),
     *                      sorted ascending by startEpoch.  Each entry's
     *                      [RunLogEntry.loadSnapshot*] fields are the EWMA state
     *                      at that session's end time.
     * @param dailyEntries  run_daily rows for the portion of the window older than
     *                      30 days, sorted ascending by dayEpoch.  Each row's
     *                      [RunDailySummary.loadSnapshot*] is the last session's
     *                      EWMA state for that calendar day.
     * @param seedEntry     The run_log entry whose session ended most recently
     *                      before [windowStartMs].  Used to seed the initial EWMA
     *                      state at the window start via idle decay.  May be null.
     * @param seedDaily     The run_daily row whose day ended most recently before
     *                      [windowStartMs].  Fallback seed when no [seedEntry]
     *                      exists within reach.  May be null.
     * @param windowStartMs Start of the analysis window (epoch ms).
     * @param nowMs         Current time (epoch ms).
     * @return ~60 evenly-spaced [LoadSample]s, or empty when no snapshots exist.
     */
    fun reconstruct(
        logEntries:    List<RunLogEntry>,
        dailyEntries:  List<RunDailySummary>,
        seedEntry:     RunLogEntry?,
        seedDaily:     RunDailySummary?,
        windowStartMs: Long,
        nowMs:         Long,
    ): List<LoadSample> {
        if (windowStartMs >= nowMs) return emptyList()

        // ── Build sorted anchor list (both tiers) ─────────────────────────────
        val anchors = ArrayList<Anchor>(logEntries.size + dailyEntries.size + 2)

        // Seed anchor (state just before the window opens)
        val seed = resolveSeed(seedEntry, seedDaily, windowStartMs)
        if (seed != null) anchors.add(seed)

        // Tier 2: daily anchors — epochMs = end of that calendar day (UTC)
        for (d in dailyEntries) {
            val a = Anchor(
                epochMs   = d.dayEpoch + 86_400_000L,
                cognitive = d.loadSnapshotCognitive,
                physical  = d.loadSnapshotPhysical,
                emotional = d.loadSnapshotEmotional,
            )
            if (a.epochMs > windowStartMs) anchors.add(a)
        }

        // Tier 1: session-end anchors — epochMs = startEpoch + durationSecs × 1000
        for (e in logEntries) {
            val endMs = e.startEpoch + e.durationSecs * 1_000L
            val a = Anchor(
                epochMs   = endMs,
                cognitive = e.loadSnapshotCognitive,
                physical  = e.loadSnapshotPhysical,
                emotional = e.loadSnapshotEmotional,
            )
            if (a.epochMs in (windowStartMs + 1)..nowMs) anchors.add(a)
        }

        anchors.sortBy { it.epochMs }

        if (anchors.isEmpty()) return emptyList()

        // ── Sample generation ─────────────────────────────────────────────────
        val windowMs       = (nowMs - windowStartMs).coerceAtLeast(1L)
        val sampleInterval = (windowMs / 60L).coerceAtLeast(3_600_000L)  // min 1h

        val samples = ArrayList<LoadSample>(64)
        var t = windowStartMs
        while (t <= nowMs) {
            val sample = sampleAt(t, anchors)
            if (sample != null) samples.add(sample)
            t += sampleInterval
        }
        // Always include the current moment
        val last = sampleAt(nowMs, anchors)
        if (last != null && (samples.isEmpty() || samples.last().epochMs < nowMs)) {
            samples.add(last)
        }

        return samples
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves the best seed anchor for the window start by picking the more
     * recent of [seedEntry] (session-level, more precise) and [seedDaily]
     * (day-level, further back in time).  Applies idle decay from the seed's
     * epoch to [windowStartMs] so the window starts with the correct state.
     */
    private fun resolveSeed(
        seedEntry:     RunLogEntry?,
        seedDaily:     RunDailySummary?,
        windowStartMs: Long,
    ): Anchor? {
        // Build candidate anchors from both seeds
        val candidates = buildList {
            if (seedEntry != null) {
                val endMs = seedEntry.startEpoch + seedEntry.durationSecs * 1_000L
                add(Anchor(endMs,
                    seedEntry.loadSnapshotCognitive,
                    seedEntry.loadSnapshotPhysical,
                    seedEntry.loadSnapshotEmotional,
                ))
            }
            if (seedDaily != null) {
                add(Anchor(seedDaily.dayEpoch + 86_400_000L,
                    seedDaily.loadSnapshotCognitive,
                    seedDaily.loadSnapshotPhysical,
                    seedDaily.loadSnapshotEmotional,
                ))
            }
        }.filter { it.epochMs <= windowStartMs && it.hasData }

        val best = candidates.maxByOrNull { it.epochMs } ?: return null

        // Decay from seed epoch to window start
        val dtSecs = ((windowStartMs - best.epochMs) / 1_000.0).coerceAtLeast(0.0)
        return Anchor(
            epochMs   = windowStartMs,
            cognitive = idleDecay(best.cognitive, dtSecs, LoadAverage.TAU_COGNITIVE),
            physical  = idleDecay(best.physical,  dtSecs, LoadAverage.TAU_PHYSICAL),
            emotional = idleDecay(best.emotional, dtSecs, LoadAverage.TAU_EMOTIONAL),
        )
    }

    /**
     * Computes the EWMA state at time [t] by finding the most recent anchor
     * at or before [t] and applying idle decay forward to [t].
     * Returns null when no anchors exist before or at [t].
     */
    private fun sampleAt(t: Long, anchors: List<Anchor>): LoadSample? {
        // Binary search: last anchor with epochMs ≤ t
        var lo = 0; var hi = anchors.size - 1; var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (anchors[mid].epochMs <= t) { best = mid; lo = mid + 1 }
            else hi = mid - 1
        }
        if (best < 0) return null

        val anchor = anchors[best]
        val dtSecs = ((t - anchor.epochMs) / 1_000.0).coerceAtLeast(0.0)

        val c = idleDecay(anchor.cognitive, dtSecs, LoadAverage.TAU_COGNITIVE)
        val p = idleDecay(anchor.physical,  dtSecs, LoadAverage.TAU_PHYSICAL)
        val e = idleDecay(anchor.emotional, dtSecs, LoadAverage.TAU_EMOTIONAL)

        return toSample(t, c, p, e)
    }

    /** Continuous EWMA idle decay toward 0: value × e^(−Δt / τ). */
    private fun idleDecay(value: Double, dtSecs: Double, tau: Double): Double {
        if (dtSecs <= 0.0 || value == 0.0) return value
        return value * exp(-dtSecs / tau)
    }

    private fun toSample(epochMs: Long, c: Double, p: Double, e: Double) = LoadSample(
        epochMs   = epochMs,
        cognitive = ((c / 7.0) * 100.0).toFloat().coerceIn(0f, 100f),
        physical  = ((p / 7.0) * 100.0).toFloat().coerceIn(0f, 100f),
        emotional = ((e / 7.0) * 100.0).toFloat().coerceIn(0f, 100f),
        combined  = LoadAverage.combinedLoad(c, p, e).toFloat().coerceIn(0f, 100f),
    )
}
