package com.eevdf.capabilities.taskwake

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.eevdf.capabilities.taskstorage.TaskWake
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText

/**
 * The wake prompt: a mode row (Alarm | Timer) over a 2 × 3 grid of number-only boxes.
 *
 *   Alarm  — Day Month Year / Hour Min Sec: the date and time it fires. Prefilled with a
 *            minute from now, or with the current alarm when one is armed.
 *   Timer  — the same boxes as a duration from now: "2 day 11 s" is Day 2, Sec 11.
 *
 * Set validates (a real date in the future; a non-zero duration) and keeps the dialog open
 * with a message when it is not valid. Clear appears only when a wake is already armed.
 */
object WakePromptDialog {

    fun show(
        context: Context,
        taskName: String,
        existing: TaskWake?,
        nowMs: Long = System.currentTimeMillis(),
        onSet: (mode: String, targetEpochMs: Long) -> Unit,
        onClear: (() -> Unit)?,
        onDismiss: () -> Unit = {},
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_wake_prompt, null)
        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.wakeModeGroup)
        val hint  = view.findViewById<TextView>(R.id.tvWakeModeHint)
        val day    = view.findViewById<TextInputEditText>(R.id.etWakeDay)
        val month  = view.findViewById<TextInputEditText>(R.id.etWakeMonth)
        val year   = view.findViewById<TextInputEditText>(R.id.etWakeYear)
        val hour   = view.findViewById<TextInputEditText>(R.id.etWakeHour)
        val minute = view.findViewById<TextInputEditText>(R.id.etWakeMinute)
        val second = view.findViewById<TextInputEditText>(R.id.etWakeSecond)

        val armed = existing?.takeIf { it.isArmed }
        var mode = armed?.mode ?: TaskWake.MODE_ALARM

        fun setFields(f: WakeRules.Fields) {
            day.setText(f.day.toString());   month.setText(f.month.toString()); year.setText(f.year.toString())
            hour.setText(f.hour.toString()); minute.setText(f.minute.toString()); second.setText(f.second.toString())
        }

        fun fillFor(m: String) {
            if (m == TaskWake.MODE_ALARM) {
                hint.text = "Fires on this date and time (24 h)"
                // An armed alarm shows its own time; otherwise a minute from now, on the dot.
                val base = armed?.takeIf { it.mode == TaskWake.MODE_ALARM }?.targetEpochMs ?: (nowMs + 60_000L)
                setFields(WakeRules.fieldsOf(base).let { if (armed == null) it.copy(second = 0) else it })
            } else {
                hint.text = "Fires this long from now — Day, Month, Year are how many, not a date"
                setFields(WakeRules.Fields(0, 0, 0, 0, 0, 0))
            }
        }

        fun number(box: TextInputEditText): Int? = box.text?.toString().orEmpty().trim().let { if (it.isEmpty()) null else it.toIntOrNull() }

        group.check(if (mode == TaskWake.MODE_ALARM) R.id.btnWakeModeAlarm else R.id.btnWakeModeTimer)
        fillFor(mode)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = if (checkedId == R.id.btnWakeModeAlarm) TaskWake.MODE_ALARM else TaskWake.MODE_TIMER
            fillFor(mode)
        }

        val builder = AlertDialog.Builder(context)
            .setTitle("Wake · $taskName")
            .setView(view)
            .setPositiveButton("Set", null)          // wired below, so an invalid entry does not close it
            .setNegativeButton("Cancel", null)
        if (onClear != null && armed != null) builder.setNeutralButton("Clear", null)
        val dialog = builder.create()

        dialog.setOnDismissListener { onDismiss() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val now = System.currentTimeMillis()
                val target: Long?
                if (mode == TaskWake.MODE_ALARM) {
                    val d = number(day); val mo = number(month); val y = number(year)
                    val h = number(hour); val mi = number(minute); val s = number(second)
                    target = if (d == null || mo == null || y == null || h == null || mi == null || s == null) null
                             else WakeRules.alarmTarget(WakeRules.Fields(d, mo, y, h, mi, s), now)
                    if (target == null) { Toast.makeText(context, "Enter a real date and time in the future", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                } else {
                    val f = WakeRules.Fields(
                        number(day) ?: 0, number(month) ?: 0, number(year) ?: 0,
                        number(hour) ?: 0, number(minute) ?: 0, number(second) ?: 0,
                    )
                    target = WakeRules.timerTarget(f, now)
                    if (target == null) { Toast.makeText(context, "Enter how long from now", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                }
                val resolved: Long = target ?: return@setOnClickListener
                onSet(mode, resolved)
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                onClear?.invoke()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
