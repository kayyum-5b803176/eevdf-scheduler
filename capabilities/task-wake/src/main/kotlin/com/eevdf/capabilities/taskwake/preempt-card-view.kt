package com.eevdf.capabilities.taskwake

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * The preempt card: "<task> wants to preempt <current>" with Dismiss and Preempt (or
 * Select, when nothing is current). Same look as the timer card's expired block — a card
 * in the alarm colour with a name, a subtitle and a button row.
 *
 * It only shows text and reports taps. What Preempt / Select / Dismiss DO is decided by the
 * task list's `WakeDelegate`.
 */
class PreemptCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle,
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val title:    TextView
    private val subtitle: TextView
    private val primary:  MaterialButton
    private val dismiss:  MaterialButton

    init {
        LayoutInflater.from(context).inflate(R.layout.view_wake_preempt_card, this, true)
        title    = findViewById(R.id.tvWakeCardTitle)
        subtitle = findViewById(R.id.tvWakeCardSubtitle)
        primary  = findViewById(R.id.btnWakePrimary)
        dismiss  = findViewById(R.id.btnWakeDismiss)
        visibility = View.GONE
    }

    /** Shows the card. [primaryLabel] is "Preempt" or "Select". */
    fun show(titleText: String, subtitleText: String, primaryLabel: String, onPrimary: () -> Unit, onDismiss: () -> Unit) {
        title.text    = titleText
        subtitle.text = subtitleText
        primary.text  = primaryLabel
        primary.setOnClickListener { onPrimary() }
        dismiss.setOnClickListener { onDismiss() }
        visibility = View.VISIBLE
    }

    fun hide() {
        primary.setOnClickListener(null)
        dismiss.setOnClickListener(null)
        visibility = View.GONE
    }
}
