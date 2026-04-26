package com.eevdf.scheduler.ui

import android.content.Context

/**
 * Sealed class representing every legal state of the alarm system.
 *
 * This is the single source of truth that survives process death.
 * It is persisted to SharedPreferences with commit() (synchronous write) so
 * the state on disk is always consistent with what AlarmManager has scheduled.
 *
 * ── State machine ─────────────────────────────────────────────────────────────
 *
 *   Idle  ──schedule()──▶  Scheduled  ──onAlarmFired()──▶  Ringing
 *    ▲                          │                               │
 *    └────cancel()──────────────┘                               │
 *    └────stop()─────────────────────────────────────────────────┘
 *
 * ── Transition ownership ──────────────────────────────────────────────────────
 *
 *   ALL transitions go through AlarmScheduler (never call write() directly).
 *   AlarmScheduler is the only class that touches AlarmManager or writes
 *   alarm state to disk.  Nothing else — not the ViewModel, not the service,
 *   not onCleared() — is allowed to schedule or cancel the AlarmManager entry.
 *
 * ── Why sealed over boolean flags ─────────────────────────────────────────────
 *
 *   Boolean flags on SharedPreferences require callers to update multiple keys
 *   atomically and to understand their combinations.  A sealed class makes
 *   impossible states (Scheduled with no trigger epoch, Ringing with no task
 *   name) unrepresentable, and the exhaustive when() forces every call site to
 *   handle every state.
 */
sealed class AlarmState {

    /** No alarm is scheduled. AlarmManager has no pending entry. */
    object Idle : AlarmState()

    /**
     * A Doze-immune alarm is pending in AlarmManager.
     * [triggerEpoch] = System.currentTimeMillis() + remainingSecs * 1000.
     * State written BEFORE the AlarmManager call so process-death between the
     * two operations leaves the state as Scheduled, which is correct — the
     * receiver will fire and forward to the service.
     */
    data class Scheduled(
        val taskName: String,
        val triggerEpoch: Long,
        val taskType: String
    ) : AlarmState()

    /**
     * The alarm has fired and the service is playing sound / holding WakeLock.
     * AlarmManager entry is gone (it already fired).
     */
    data class Ringing(
        val taskName: String,
        val taskType: String
    ) : AlarmState()

    // ── Disk persistence ──────────────────────────────────────────────────────
    // internal visibility — only AlarmScheduler may write.

    companion object {
        private const val PREFS     = "eevdf_alarm_state_v2"
        private const val KEY_STATE = "state"          // "IDLE" | "SCHEDULED" | "RINGING"
        private const val KEY_NAME  = "task_name"
        private const val KEY_EPOCH = "trigger_epoch"
        private const val KEY_TYPE  = "task_type"

        /**
         * Read the current alarm state from disk.
         * Safe to call from any process/thread — SharedPreferences reads are atomic.
         * Returns Idle when no valid state is stored (first launch, cleared data).
         */
        fun read(context: Context): AlarmState {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return when (p.getString(KEY_STATE, "IDLE")) {
                "SCHEDULED" -> Scheduled(
                    taskName     = p.getString(KEY_NAME,  "") ?: "",
                    triggerEpoch = p.getLong(KEY_EPOCH, 0L),
                    taskType     = p.getString(KEY_TYPE,  "DEFAULT") ?: "DEFAULT"
                )
                "RINGING" -> Ringing(
                    taskName = p.getString(KEY_NAME, "") ?: "",
                    taskType = p.getString(KEY_TYPE, "DEFAULT") ?: "DEFAULT"
                )
                else -> Idle
            }
        }

        /**
         * Write alarm state to disk synchronously.
         *
         * commit() not apply() — the state on disk must be authoritative before
         * this function returns.  A crash between writing state and calling
         * AlarmManager is recoverable (read() returns the correct intent).
         * A crash after calling AlarmManager but before writing state is also
         * recoverable because the receiver guards on read().
         *
         * internal — callers outside this package must go through AlarmScheduler.
         */
        internal fun write(context: Context, state: AlarmState) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                when (state) {
                    is Idle -> {
                        putString(KEY_STATE, "IDLE")
                        remove(KEY_NAME)
                        remove(KEY_EPOCH)
                        remove(KEY_TYPE)
                    }
                    is Scheduled -> {
                        putString(KEY_STATE, "SCHEDULED")
                        putString(KEY_NAME,  state.taskName)
                        putLong(KEY_EPOCH,   state.triggerEpoch)
                        putString(KEY_TYPE,  state.taskType)
                    }
                    is Ringing -> {
                        putString(KEY_STATE, "RINGING")
                        putString(KEY_NAME,  state.taskName)
                        remove(KEY_EPOCH)
                        putString(KEY_TYPE,  state.taskType)
                    }
                }
            }.commit()   // synchronous
        }
    }
}
