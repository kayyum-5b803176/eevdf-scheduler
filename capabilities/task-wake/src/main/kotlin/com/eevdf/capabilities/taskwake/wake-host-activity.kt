package com.eevdf.capabilities.taskwake

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The one screen of the wake capability: a see-through activity that puts a dialog or the
 * preempt card on top of whatever is open, so the task list, the edit screen and the rest of
 * the app need no wake UI of their own — they only publish "the wake icon was tapped".
 *
 *   MODE_PROMPT   the input prompt (mode row + the 2 × 3 number grid)
 *   MODE_STATE    the read-only state popup
 *   MODE_PREEMPT  the preempt card, ringing the wake's profile until it is answered
 *
 * Every decision is the [WakeCoordinator]'s; this class only shows what it is told to and
 * reports the answer. Leaving the card with Back does NOT answer it: the alert stops, the
 * wake stays pending and the card comes back the next time the app is opened.
 */
@AndroidEntryPoint
class WakeHostActivity : AppCompatActivity() {

    @Inject lateinit var coordinator: WakeCoordinator

    private lateinit var card: PreemptCardView
    private var taskId: String? = null
    private var ringing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wake_host)
        card = findViewById(R.id.cardWakePreempt)
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_TASK_ID) ?: run { finish(); return }
        taskId = id
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_PROMPT  -> showPrompt(id)
            MODE_STATE   -> showState(id)
            MODE_PREEMPT -> showPreempt(id)
            else         -> finish()
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

    private fun showPreempt(id: String) = lifecycleScope.launch {
        val data = coordinator.cardData(id) ?: run { finish(); return@launch }
        card.show(
            titleText = data.title,
            subtitleText = data.subtitle,
            primaryLabel = data.primaryLabel,
            onPrimary = { lifecycleScope.launch { coordinator.accept(id); ringing = false; finish() } },
            onDismiss = { lifecycleScope.launch { coordinator.dismiss(id); ringing = false; finish() } },
        )
        if (!ringing) { ringing = true; coordinator.startAlert(data.profile) }
    }

    override fun onDestroy() {
        // Ring only while the card is up. An unanswered card keeps its wake pending (it re-shows).
        if (ringing) lifecycleScope.launch { coordinator.stopAlert() }
        coordinator.onHostClosed(taskId)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODE = "wake_mode"
        const val EXTRA_TASK_ID = "wake_task_id"
        const val MODE_PROMPT = "prompt"
        const val MODE_STATE = "state"
        const val MODE_PREEMPT = "preempt"
    }
}
