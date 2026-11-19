package com.eevdf.capabilities.taskwake

import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * What a long-press on the wake icon shows: the state of the wake as plain lines (built by
 * the task list, which knows whether it is waiting on the running task), with a way to
 * cancel it. Read-only otherwise.
 */
object WakeStateDialog {

    fun show(context: Context, taskName: String, lines: List<String>, onCancelWake: (() -> Unit)?, onDismiss: () -> Unit = {}) {
        val builder = AlertDialog.Builder(context)
            .setTitle("Wake · $taskName")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("Close", null)
        if (onCancelWake != null) builder.setNegativeButton("Cancel wake") { _, _ -> onCancelWake() }
        builder.setOnDismissListener { onDismiss() }
        builder.show()
    }
}
