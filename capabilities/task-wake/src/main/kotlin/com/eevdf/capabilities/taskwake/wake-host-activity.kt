package com.eevdf.capabilities.taskwake

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * A see-through activity that puts one of the wake's two dialogs on top of whatever is open,
 * so the task list and the edit screen need no wake dialogs of their own — they only publish
 * "the wake icon was tapped / held".
 *
 *   MODE_PROMPT  the input prompt (mode row + the 2 × 3 number grid)
 *   MODE_STATE   the read-only state popup
 *
 * The PREEMPT CARD is not here: it is published over the bus (`card.show`) and drawn by the
 * timer-card capability in the timer card's own place, the way an expired alarm takes the card over.
 *
 * Every decision is the [WakeCoordinator]'s; this class only shows what it is told to and
 * reports what was chosen. The activity closes when its dialog does.
 */
@AndroidEntryPoint
class WakeHostActivity : AppCompatActivity() {

    @Inject lateinit var coordinator: WakeCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wake_host)
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_TASK_ID) ?: run { finish(); return }
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_PROMPT -> showPrompt(id)
            MODE_STATE  -> showState(id)
            else        -> finish()
        }
    }

    private fun showPrompt(id: String) = lifecycleScope.launch {
        val data = coordinator.promptData(id) ?: run { finish(); return@launch }
        WakePromptDialog.show(
            context = this@WakeHostActivity,
            taskName = data.taskName,
            existing = data.wake,
            onSet = { mode, target -> lifecycleScope.launch { coordinator.arm(id, mode, target) } },
            onClear = { lifecycleScope.launch { coordinator.disarm(id) } },
            onDismiss = { finish() },
        )
    }

    private fun showState(id: String) = lifecycleScope.launch {
        val data = coordinator.stateData(id) ?: run { finish(); return@launch }
        WakeStateDialog.show(
            context = this@WakeHostActivity,
            taskName = data.taskName,
            lines = data.lines,
            onCancelWake = if (data.canCancel) ({ lifecycleScope.launch { coordinator.disarm(id) } }) else null,
            onDismiss = { finish() },
        )
    }

    companion object {
        const val EXTRA_MODE = "wake_mode"
        const val EXTRA_TASK_ID = "wake_task_id"
        const val MODE_PROMPT = "prompt"
        const val MODE_STATE = "state"
    }
}
