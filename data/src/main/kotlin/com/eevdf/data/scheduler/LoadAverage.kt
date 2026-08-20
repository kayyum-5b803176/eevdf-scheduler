package com.eevdf.data.scheduler

import com.eevdf.data.task.Task
import kotlin.math.exp

/**
 * Per-task load average — three separate EWMAs, one per NASA-TLX dimension.
 *
 * ── Why three instead of one ────────────────────────────────────────────────
 * The original single EWMA used a 24-hour window for everything, which was
 * correct when load factor was an undifferentiated float.  With the three-slider
 * model (Cognitive, Physical, Emotional) each dimension has a distinct
 * physiological recovery half-life that a single τ cannot represent:
 *
 *   Cognitive   τ =  6 h — deep-focus and analytical load recovers within a day
 *   Physical    τ = 18 h — muscular fatigue resolves with overnight sleep
 *   Emotional   τ = 60 h — stress and emotional weight lingers 2–3 days
 *
 * Running three independent EWMAs preserves this asymmetry so a high-emotional
 * task in the morning correctly suppresses afternoon capacity even after the
 * cognitive and physical components have recovered.
 *
 * ── Output ──────────────────────────────────────────────────────────────────
 * [combinedLoad] maps the three EWMA values to a single 0–100 number using the
 * same formula as TaskLoadFactor.compute — so the stats bar and the slider
 * display share one canonical scale.
 *
 * [Task.loadAverage] is kept as the combined output; [Task.loadAvgCognitive],
 * [Task.loadAvgPhysical], [Task.loadAvgEmotional] are the intermediate state.
 * [Task.loadLastUpdateEpoch] remains the single anchor for the combined value;
 * three additional per-dimension anchors track individual decay timing.
 *
 * ── Running vs. idle targets ─────────────────────────────────────────────────
 * While running:  each EWMA decays toward its slider value (1–7).
 * While idle:     each EWMA decays toward 0 (full recovery).
 *
 * ── Calling convention ───────────────────────────────────────────────────────
 * [advanced] is called by [TaskRepository.applyLoadAccounting] on session end
 * with the task's actual slider targets.
 *
 * [currentValue] and [systemLoad] are called by the UI/ViewModel for live
 * display.  Since the side table is not available in that path, the running
 * target is approximated from [Task.loadFactor] (the stored composite 0–100
 * value) by distributing it evenly across the three dimensions.  This produces
 * identical peak values and correct decay behaviour for display purposes.
 */
object LoadAverage {

    // ── Decay time constants ─────────────────────────────────────────────────

    /** Cognitive τ:  6 h in seconds. */
    const val TAU_COGNITIVE: Double = 6.0  * 3_600.0

    /** Physical τ:  18 h in seconds. */
    const val TAU_PHYSICAL: Double  = 18.0 * 3_600.0

    /** Emotional τ: 60 h in seconds. */
    const val TAU_EMOTIONAL: Double = 60.0 * 3_600.0

    /**
     * Legacy constant kept for call sites that reference it directly.
     * The three per-dimension τ values above are now authoritative.
     */
    @Suppress("unused")
    const val LOAD_WINDOW_SECONDS: Double = 86_400.0

    // ── Core EWMA step ───────────────────────────────────────────────────────

    /**
     * Advance one EWMA value by [dtSeconds] toward [target] with time constant [tau].
     *
     *   value' = target + (value − target) · e^(−Δt / τ)
     *
     * Equivalent to:  value' = value · e^(−Δt/τ) + target · (1 − e^(−Δt/τ))
     */
    private fun step(value: Double, target: Double, dtSeconds: Double, tau: Double): Double {
        if (dtSeconds <= 0.0) return value
        val decay = exp(-dtSeconds / tau)
        return value * decay + target * (1.0 - decay)
    }

    // ── Combined display formula ─────────────────────────────────────────────

    /**
     * Maps three per-dimension EWMA values to the unified 0–100 load output.
     *
     * Uses the same linear formula as TaskLoadFactor.compute so that the
     * peak displayed value when a task is running matches what the sliders show.
     * Clamped to 0 below the floor (i.e. when all EWMAs have decayed close to 0).
     *
     *   combined = max(0, ((C + P + E − 3) / 18) × 100)
     */
    /**
     * Maps three per-dimension EWMA values (0–7 range each) to the unified
     * 0–100 load output.
     *
     * ── Why this formula differs from TaskLoadFactor.compute ─────────────────
     * [TaskLoadFactor.compute] uses `((C+P+E-3)/18)×100` with a −3 offset
     * because sliders have a minimum of 1 — the minimum sum is 3.
     *
     * EWMA values live in the [0,7] range, not [1,7].  They start at 0 and
     * decay back toward 0 when idle.  Subtracting 3 from a freshly-started
     * EWMA (where each dimension is ≪1.0 after a short session) produces a
     * large negative number that clamps to zero — the bug that caused the
     * stats bar to show 0.00 after any session shorter than several hours.
     *
     * The correct denominator for the [0,7] range is 21 (= 7 × 3 dimensions):
     *   [0,0,0] → 0    (fully recovered, no history)
     *   [7,7,7] → 100  (maximum sustained load)
     *   [4,4,4] → 57   (default sliders after sustained running — intentionally
     *                    above 50 because 57 means "sustained mid-intensity"
     *                    while 50 means "configured at mid-intensity right now";
     *                    these are distinct and the difference is correct)
     */
    fun combinedLoad(avgC: Double, avgP: Double, avgE: Double): Double =
        ((avgC + avgP + avgE) / 21.0 * 100.0).coerceIn(0.0, 100.0)

    // ── Persistence path (session end) ───────────────────────────────────────

    /**
     * Returns a copy of [task] with all three per-dimension EWMAs advanced to
     * [nowEpoch], using explicit per-dimension [target*] values.
     *
     * Called by [TaskRepository.applyLoadAccounting] with the task's actual
     * slider values from the [TaskLoadFactor] side table:
     *   isRunning = false → targets should all be 0.0  (idle/end step)
     *   isRunning = true  → targets are the slider integers (1.0–7.0)
     *
     * First-ever call (all timestamps == 0) seeds the anchors without applying
     * a spurious initial jump, matching [LoadAverage]'s previous seed behaviour.
     */
    fun advanced(
        task: Task,
        nowEpoch: Long,
        isRunning: Boolean,
        targetCognitive: Double = 0.0,
        targetPhysical:  Double = 0.0,
        targetEmotional: Double = 0.0,
    ): Task {
        val tC = if (isRunning) targetCognitive else 0.0
        val tP = if (isRunning) targetPhysical  else 0.0
        val tE = if (isRunning) targetEmotional else 0.0

        val firstEver = task.loadLastUpdateCognitive == 0L &&
                        task.loadLastUpdatePhysical  == 0L &&
                        task.loadLastUpdateEmotional == 0L

        val newC: Double
        val newP: Double
        val newE: Double

        if (firstEver) {
            // Seed: start each dimension at its target (or current value if idle)
            newC = if (isRunning) tC else task.loadAvgCognitive
            newP = if (isRunning) tP else task.loadAvgPhysical
            newE = if (isRunning) tE else task.loadAvgEmotional
        } else {
            val dtC = if (task.loadLastUpdateCognitive == 0L) 0.0
                      else (nowEpoch - task.loadLastUpdateCognitive) / 1_000.0
            val dtP = if (task.loadLastUpdatePhysical  == 0L) 0.0
                      else (nowEpoch - task.loadLastUpdatePhysical)  / 1_000.0
            val dtE = if (task.loadLastUpdateEmotional == 0L) 0.0
                      else (nowEpoch - task.loadLastUpdateEmotional) / 1_000.0

            newC = step(task.loadAvgCognitive, tC, dtC, TAU_COGNITIVE)
            newP = step(task.loadAvgPhysical,  tP, dtP, TAU_PHYSICAL)
            newE = step(task.loadAvgEmotional, tE, dtE, TAU_EMOTIONAL)
        }

        return task.copy(
            loadAvgCognitive         = newC,
            loadLastUpdateCognitive  = nowEpoch,
            loadAvgPhysical          = newP,
            loadLastUpdatePhysical   = nowEpoch,
            loadAvgEmotional         = newE,
            loadLastUpdateEmotional  = nowEpoch,
            loadAverage              = combinedLoad(newC, newP, newE),
            loadLastUpdateEpoch      = nowEpoch,
        )
    }

    // ── Display path (no side table access) ──────────────────────────────────

    /**
     * Read-only current combined load for display — does NOT mutate or persist.
     *
     * When [isRunning] the running-target per dimension is approximated from
     * [Task.loadFactor] (the stored composite 0–100 value) by distributing it
     * evenly: approx = (loadFactor / 100) × 7.  This produces the correct peak
     * and decay for display without requiring access to the side table.
     *
     * When idle all targets are 0 and each dimension decays toward 0 at its own τ.
     */
    fun currentValue(task: Task, nowEpoch: Long, isRunning: Boolean): Double {
        // Approximate per-dimension running target from the stored composite
        val approx = if (isRunning) (task.loadFactor / 100.0) * 7.0 else 0.0

        fun dim(avg: Double, lastUpdate: Long, tau: Double): Double {
            if (lastUpdate == 0L) return approx   // not yet seeded
            val dt = (nowEpoch - lastUpdate) / 1_000.0
            return step(avg, approx, dt, tau)
        }

        val avgC = dim(task.loadAvgCognitive, task.loadLastUpdateCognitive, TAU_COGNITIVE)
        val avgP = dim(task.loadAvgPhysical,  task.loadLastUpdatePhysical,  TAU_PHYSICAL)
        val avgE = dim(task.loadAvgEmotional, task.loadLastUpdateEmotional, TAU_EMOTIONAL)

        return combinedLoad(avgC, avgP, avgE)
    }

    /**
     * System load = sum of every task's current combined load average.
     * Signature unchanged from the previous single-EWMA version.
     */
    fun systemLoad(tasks: List<Task>, nowEpoch: Long, runningId: String?): Double =
        tasks.sumOf { currentValue(it, nowEpoch, isRunning = it.id == runningId) }
}
