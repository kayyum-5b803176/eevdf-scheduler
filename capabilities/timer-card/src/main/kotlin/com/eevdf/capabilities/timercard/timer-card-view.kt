package com.eevdf.capabilities.timercard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.eevdf.capabilities.designsystem.R as DesignSystemR
import com.eevdf.kernel.eventbus.CardButton
import com.eevdf.kernel.eventbus.CardKind
import com.eevdf.kernel.eventbus.CardSpec
import com.eevdf.kernel.eventbus.CardTint
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

/**
 * THE card above the task list. One view, three faces — the running timer, an expired alarm, a
 * wake asking to preempt — each looking exactly as it always has (the layout was moved here
 * unchanged, see view_timer_card.xml).
 *
 * It is a pure renderer. [bind] hooks it to the [TimerCardController]; from then on it draws
 * whatever card the controller says is visible, ticks the countdown / count-up by itself once a
 * second (so nothing is published per second), and reports button presses back to the controller.
 * It never decides anything: what a button does is the publisher's business.
 *
 * Like before, it only toggles which face shows, sets the card colour and writes TEXT (and the
 * button tint / enabled state); sizes, fonts and colours of the faces are fixed in XML.
 */
class TimerCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle,
) : MaterialCardView(context, attrs, defStyleAttr) {

    /** The three faces, exposed so the host's display scaling can pad them, as it always has. */
    val timerContent: LinearLayout
    val alarmContent: LinearLayout
    val wakeContent:  LinearLayout

    private val tvTimerName:     TextView
    private val tvTimerPriority: TextView
    private val tvTimerDisplay:  TextView
    private val viewPhaseStatus: LinearLayout
    private val btnNext:         MaterialButton
    private val btnStartPause:   MaterialButton
    private val btnInt:          MaterialButton

    private val tvAlarmName:     TextView
    private val tvAlarmSubtitle: TextView
    private val tvAlarmElapsed:  TextView
    private val btnStop:         MaterialButton

    private val tvWakeName:      TextView
    private val tvWakeSubtitle:  TextView
    private val btnWakeDismiss:  MaterialButton
    private val btnWakePrimary:  MaterialButton

    private var controller: TimerCardController? = null
    private var shown: CardSpec? = null
    private var ticking = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) return
            updateClock()
            postDelayed(this, nextTickDelayMs())
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_timer_card, this, true)
        timerContent    = findViewById(R.id.layoutTimerContent)
        alarmContent    = findViewById(R.id.layoutAlarmContent)
        wakeContent     = findViewById(R.id.layoutWakeContent)
        tvTimerName     = findViewById(R.id.tvCurrentTaskName)
        tvTimerPriority = findViewById(R.id.tvTimerPriority)
        tvTimerDisplay  = findViewById(R.id.tvTimerDisplay)
        viewPhaseStatus = findViewById(R.id.viewPhaseStatus)
        btnNext         = findViewById(R.id.btnScheduleNext)
        btnStartPause   = findViewById(R.id.btnStartPause)
        btnInt          = findViewById(R.id.btnInt)
        tvAlarmName     = findViewById(R.id.tvAlarmTaskName)
        tvAlarmSubtitle = findViewById(R.id.tvAlarmSubtitle)
        tvAlarmElapsed  = findViewById(R.id.tvAlarmElapsed)
        btnStop         = findViewById(R.id.btnStopAlarm)
        tvWakeName      = findViewById(R.id.tvWakeTaskName)
        tvWakeSubtitle  = findViewById(R.id.tvWakeSubtitle)
        btnWakeDismiss  = findViewById(R.id.btnWakeDismiss)
        btnWakePrimary  = findViewById(R.id.btnWakePrimary)

        // Remove the Start/Pause button's state-list animator so changing its tint never triggers a
        // state-driven elevation / colour animation (with jumpDrawablesToCurrentState() below, the
        // Start↔Pause colour change is instant — no light→dark cross-fade).
        btnStartPause.stateListAnimator = null
        visibility = View.GONE
    }

    /** Hooks the card up: from now on it shows [controller]'s visible card while [owner] is started. */
    fun bind(controller: TimerCardController, owner: LifecycleOwner) {
        this.controller = controller
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.visible.collect { render(it) }
            }
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    private fun render(spec: CardSpec?) {
        shown = spec
        stopTicking()
        if (spec == null) {
            stopStripBlinks(viewPhaseStatus)
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        when (spec.kind) {
            CardKind.TIMER -> renderTimer(spec)
            CardKind.ALARM -> renderAlarm(spec)
            CardKind.WAKE  -> renderWake(spec)
        }
        if (spec.kind != CardKind.TIMER) stopStripBlinks(viewPhaseStatus)
        updateClock()
        if (needsTicking(spec)) startTicking()
    }

    private fun renderTimer(spec: CardSpec) {
        setCardBackgroundColor(color(DesignSystemR.color.timerCardBackground))
        alarmContent.visibility = View.GONE
        wakeContent.visibility  = View.GONE
        timerContent.visibility = View.VISIBLE

        tvTimerName.text     = spec.title
        tvTimerPriority.text = spec.subtitle
        renderStrip(viewPhaseStatus, spec.strip)

        bindButton(btnNext, spec, spec.buttons.getOrNull(0))
        val center = spec.buttons.getOrNull(1)
        bindButton(btnStartPause, spec, center)
        center?.tint?.let { btnStartPause.backgroundTintList = ColorStateList.valueOf(color(tintRes(it))) }
        // Snap the new tint instantly: MaterialButton's background otherwise cross-fades between the
        // old and new tint, a visible flash on Start↔Pause.
        btnStartPause.jumpDrawablesToCurrentState()

        val int = spec.buttons.getOrNull(2)
        bindButton(btnInt, spec, int)
        btnInt.setTextColor(
            int?.textColorHex?.takeIf { it.isNotEmpty() }?.let { runCatching { Color.parseColor(it) }.getOrNull() }
                ?: color(DesignSystemR.color.colorPrimary)
        )
        btnInt.jumpDrawablesToCurrentState()
    }

    private fun renderAlarm(spec: CardSpec) {
        setCardBackgroundColor(color(DesignSystemR.color.expiredCardBackground))
        timerContent.visibility = View.GONE
        wakeContent.visibility  = View.GONE
        alarmContent.visibility = View.VISIBLE
        tvAlarmName.text = spec.title
        if (spec.subtitle.isNotEmpty()) tvAlarmSubtitle.text = spec.subtitle
        bindButton(btnStop, spec, spec.buttons.getOrNull(0))
    }

    private fun renderWake(spec: CardSpec) {
        setCardBackgroundColor(color(DesignSystemR.color.expiredCardBackground))
        timerContent.visibility = View.GONE
        alarmContent.visibility = View.GONE
        wakeContent.visibility  = View.VISIBLE
        tvWakeName.text     = spec.title
        tvWakeSubtitle.text = spec.subtitle
        bindButton(btnWakeDismiss, spec, spec.buttons.getOrNull(0))
        bindButton(btnWakePrimary, spec, spec.buttons.getOrNull(1))
    }

    /** Label, enabled state and press / hold wiring of one button; a button the card does not have is hidden. */
    private fun bindButton(view: MaterialButton, spec: CardSpec, button: CardButton?) {
        if (button == null) {
            view.visibility = View.GONE
            view.setOnClickListener(null)
            view.setOnLongClickListener(null)
            return
        }
        view.visibility = View.VISIBLE
        view.text       = button.label
        view.isEnabled  = button.enabled
        view.setOnClickListener { controller?.press(spec.id, button.id) }
        if (button.longPressId != null) {
            view.setOnLongClickListener { controller?.press(spec.id, button.id, long = true); true }
        } else {
            view.setOnLongClickListener(null)
        }
    }

    // ── The card's own clock ──────────────────────────────────────────────────

    private fun needsTicking(spec: CardSpec): Boolean = when (spec.kind) {
        CardKind.TIMER -> spec.clockEpochMs > 0L
        CardKind.ALARM -> spec.clockEpochMs > 0L
        CardKind.WAKE  -> false
    }

    private fun updateClock() {
        val spec = shown ?: return
        val now = System.currentTimeMillis()
        when (spec.kind) {
            CardKind.TIMER -> tvTimerDisplay.text =
                if (spec.clockEpochMs > 0L) CardClock.format(CardClock.remainingSeconds(spec.clockEpochMs, now)) else spec.clockText
            CardKind.ALARM -> tvAlarmElapsed.text =
                CardClock.formatElapsed(if (spec.clockEpochMs > 0L) CardClock.elapsedSeconds(spec.clockEpochMs, now) else 0L)
            CardKind.WAKE -> Unit
        }
    }

    /** Milliseconds until the displayed second next changes, so the text flips on the second, not up to a second late. */
    private fun nextTickDelayMs(): Long {
        val spec = shown ?: return 1000L
        val now = System.currentTimeMillis()
        val into = when (spec.kind) {
            CardKind.TIMER -> (spec.clockEpochMs - now).let { if (it <= 0L) return 1000L else it % 1000L }
            else           -> 1000L - ((now - spec.clockEpochMs) % 1000L).coerceAtLeast(0L)
        }
        return (if (into <= 0L) 1000L else into) + 5L
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        postDelayed(ticker, nextTickDelayMs())
    }

    private fun stopTicking() {
        ticking = false
        removeCallbacks(ticker)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val spec = shown
        if (spec != null && needsTicking(spec)) { updateClock(); startTicking() }
    }

    override fun onDetachedFromWindow() {
        stopTicking()                       // an endless callback must not outlive the view's time on screen
        stopStripBlinks(viewPhaseStatus)
        super.onDetachedFromWindow()
    }

    private fun color(res: Int): Int = ContextCompat.getColor(context, res)

    private fun tintRes(tint: CardTint): Int = when (tint) {
        CardTint.GREEN  -> DesignSystemR.color.timerGreen
        CardTint.YELLOW -> DesignSystemR.color.timerYellow
        CardTint.RED    -> DesignSystemR.color.timerRed
    }
}
