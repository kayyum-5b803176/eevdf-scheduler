package com.eevdf.scheduler.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import com.eevdf.scheduler.ui.AutoSwitchPrefs
import com.eevdf.scheduler.ui.CallEvents
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.scheduler.R
import com.eevdf.scheduler.adapter.TaskAdapter
import com.eevdf.scheduler.model.NoticePhase
import com.eevdf.scheduler.model.Task
import com.eevdf.scheduler.model.TimerCardAction
import com.eevdf.scheduler.model.IntButtonState
import com.eevdf.scheduler.model.NextButtonState
import com.eevdf.scheduler.viewmodel.TaskViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    private val viewModel: TaskViewModel by viewModels()

    private lateinit var recyclerView:        RecyclerView
    private lateinit var activeAdapter:       TaskAdapter
    private lateinit var scheduleAdapter:     TaskAdapter
    private lateinit var completedAdapter:    TaskAdapter
    private lateinit var tabLayout:           TabLayout
    private lateinit var fabAdd:              FloatingActionButton
    private lateinit var cardTimer:           CardView
    private lateinit var tvCurrentTaskName:   TextView
    private lateinit var tvTimerDisplay:      TextView
    private lateinit var tvTimerPriority:     TextView
    private lateinit var btnStartPause:       MaterialButton
    private lateinit var btnInt:              MaterialButton
    private lateinit var btnScheduleNext:     MaterialButton
    private lateinit var tvStats:             TextView
    private lateinit var tvFairness:          TextView
    private lateinit var tvScheduleRank:      TextView
    private lateinit var emptyView:           LinearLayout
    private lateinit var cardAlarmBanner:     CardView
    private lateinit var tvAlarmTaskName:     TextView
    private lateinit var tvAlarmElapsed:      TextView
    private lateinit var tvAlarmSubtitle:     TextView
    private lateinit var btnStopAlarm:        MaterialButton
    private lateinit var viewPhaseStatus:     View

    private var currentTab = 0
    private val prefs by lazy { getSharedPreferences("eevdf_prefs", MODE_PRIVATE) }

    // ── Quota real-time ticker ─────────────────────────────────────────────────
    // Fires every second while the activity is resumed. Sends a lightweight
    // PAYLOAD_QUOTA_TICK to visible items only — no full rebind, no flicker.
    private val quotaTickHandler  = android.os.Handler(android.os.Looper.getMainLooper())
    private val quotaTickRunnable = object : Runnable {
        override fun run() {
            tickQuotaOnVisibleItems()
            quotaTickHandler.postDelayed(this, 1_000L)
        }
    }

    /** Convenience: fire haptic feedback on [v] if enabled in prefs. */
    private fun haptic(v: View) {
        if (!prefs.getBoolean(VibrationManager.KEY_HAPTIC, VibrationManager.DEFAULT_HAPTIC)) return
        v.performHapticFeedback(
            android.view.HapticFeedbackConstants.VIRTUAL_KEY,
            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }
    private var groupsMenuItem:       MenuItem? = null
    private var globalRotateMenuItem: MenuItem? = null
    private var allowEditMenuItem:    MenuItem? = null
    private var autoScrollMenuItem:   MenuItem? = null

    private val alarmStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            viewModel.stopAlarmSound()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupToolbar()
        setupViews()
        setupAdapters()
        setupRecyclerView()
        setupTabs()
        setupObservers()
        // Restore last active tab using a one-shot observer so the tab is
        // selected only AFTER the target adapter has received its first data
        // from Room (which is async — the value is never ready synchronously).
        val savedTab = viewModel.getSavedTab()
        if (savedTab == 0) {
            // Queue is default — nothing to do
        } else {
            // Switch to the saved tab only after DiffUtil has actually painted
            // the items into scheduleAdapter. Using AdapterDataObserver is the
            // only reliable hook that fires AFTER ListAdapter's async DiffUtil
            // completes and items are visible — a LiveData observer fires too
            // early (before DiffUtil finishes).
            scheduleAdapter.registerAdapterDataObserver(
                object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                        if (itemCount > 0) {
                            tabLayout.getTabAt(savedTab)?.select()
                            scheduleAdapter.unregisterAdapterDataObserver(this)
                        }
                    }
                }
            )
        }
        setupTimerCard()
        setupAlarmBanner()

        ContextCompat.registerReceiver(
            this, alarmStopReceiver,
            IntentFilter(AlarmStopReceiver.ACTION_STOP_ALARM),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        viewModel.refreshSchedule()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(alarmStopReceiver)
    }

    override fun onResume() {
        super.onResume()
        quotaTickHandler.post(quotaTickRunnable)
    }
    override fun onPause()  {
        super.onPause()
        quotaTickHandler.removeCallbacks(quotaTickRunnable)
    }

    /**
     * Sends PAYLOAD_QUOTA_TICK to every currently visible item in the active
     * RecyclerView. Only tasks with quota enabled need a redraw — the adapter's
     * partial-bind handler skips all other views untouched.
     */
    private fun tickQuotaOnVisibleItems() {
        val lm      = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val adapter = when (currentTab) {
            0    -> activeAdapter
            1    -> scheduleAdapter
            else -> completedAdapter
        }
        val first = lm.findFirstVisibleItemPosition()
        val last  = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last < first) return
        for (i in first..last) {
            adapter.notifyItemChanged(i, TaskAdapter.PAYLOAD_QUOTA_TICK)
        }
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "EEVDF Task Scheduler"
    }

    private fun setupViews() {
        recyclerView      = findViewById(R.id.recyclerView)
        tabLayout         = findViewById(R.id.tabLayout)
        fabAdd            = findViewById(R.id.fabAdd)
        cardTimer         = findViewById(R.id.cardTimer)
        tvCurrentTaskName = findViewById(R.id.tvCurrentTaskName)
        tvTimerDisplay    = findViewById(R.id.tvTimerDisplay)
        tvTimerPriority   = findViewById(R.id.tvTimerPriority)
        btnStartPause     = findViewById(R.id.btnStartPause)
        btnInt            = findViewById(R.id.btnInt)
        btnScheduleNext   = findViewById(R.id.btnScheduleNext)
        tvStats           = findViewById(R.id.tvStats)
        tvFairness        = findViewById(R.id.tvFairness)
        tvScheduleRank    = findViewById(R.id.tvScheduleRank)
        emptyView         = findViewById(R.id.emptyView)
        cardAlarmBanner   = findViewById(R.id.cardAlarmBanner)
        tvAlarmTaskName   = findViewById(R.id.tvAlarmTaskName)
        tvAlarmElapsed    = findViewById(R.id.tvAlarmElapsed)
        tvAlarmSubtitle   = findViewById(R.id.tvAlarmSubtitle)
        btnStopAlarm      = findViewById(R.id.btnStopAlarm)
        viewPhaseStatus   = findViewById(R.id.viewPhaseStatus)

        fabAdd.setOnClickListener {
            haptic(it)
            startActivity(Intent(this, AddTaskActivity::class.java))
        }
    }

    private fun setupAlarmBanner() {
        btnStopAlarm.setOnClickListener { viewModel.stopAlarmSound() }
    }

    private fun makeAdapter(showRank: Boolean = false, scheduleTab: Boolean = false) = TaskAdapter(
        onTaskClick          = { /* tap does nothing — use long-press to edit */ },
        onTaskLongClick      = { if (viewModel.allowEditEnabled.value == true) showTaskDetail(it)
        else Toast.makeText(this, "Enable \"Allow Edit\" from the menu", Toast.LENGTH_SHORT).show() },
        onDeleteClick        = { confirmDelete(it) },
        onCompleteClick      = { viewModel.markCompleted(it) },
        onRunClick           = { viewModel.setCurrentTask(it) },
        onGroupToggle        = { if (scheduleTab) viewModel.toggleScheduleGroupExpanded(it)
                                 else viewModel.toggleQueueGroupExpanded(it) },
        onGroupToggleDeep    = { if (scheduleTab) viewModel.deepToggleScheduleGroupExpanded(it)
                                 else viewModel.deepToggleQueueGroupExpanded(it) },
        onResetSliceClick    = { viewModel.resetSlice(it) },
        showScheduleRank     = showRank,
        expandStateProvider  = { id -> if (scheduleTab) viewModel.getScheduleExpanded(id)
                                        else viewModel.getQueueExpanded(id) }
    )

    private fun setupAdapters() {
        activeAdapter   = makeAdapter()
        scheduleAdapter = makeAdapter(showRank = true, scheduleTab = true)
        completedAdapter = TaskAdapter(
            onTaskClick      = { /* tap does nothing */ },
            onTaskLongClick  = { if (viewModel.allowEditEnabled.value == true) showTaskDetail(it)
            else Toast.makeText(this, "Enable \"Allow Edit\" from the menu", Toast.LENGTH_SHORT).show() },
            onDeleteClick    = { confirmDelete(it) },
            onCompleteClick  = {},
            onRunClick       = {},
            onGroupToggle    = {},
            onRevertClick    = { viewModel.revertTask(it) },
            isCompletedTab   = true
        )
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = activeAdapter
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("Queue"))
        tabLayout.addTab(tabLayout.newTab().setText("Schedule"))
        tabLayout.addTab(tabLayout.newTab().setText("Completed"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                viewModel.activeTab = tab.position
                viewModel.saveTab(tab.position)
                recyclerView.adapter = when (tab.position) {
                    0    -> activeAdapter
                    1    -> scheduleAdapter
                    else -> completedAdapter
                }
                updateEmptyView()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupTimerCard() {
        // ── Start / Pause / Cancel ────────────────────────────────────────────
        // CRITICAL: dispatch from timerCardAction — the pre-derived, already-settled
        // value — NEVER from viewModel.timerRunning.value or viewModel.noticePhase.value
        // read at click time.  Reading two separate LiveData at tap time is the root
        // cause of "button stuck at Start" and "button dispatches the wrong action":
        // if the tap lands between two LiveData dispatches, one value is stale.
        btnStartPause.setOnClickListener {
            haptic(it)
            viewModel.stopAlarmSound()
            when (viewModel.timerCardAction.value) {
                TimerCardAction.Start       -> viewModel.startTimer()
                TimerCardAction.Pause       -> viewModel.pauseTimer()
                TimerCardAction.Cancel      -> viewModel.cancelNotice()
                TimerCardAction.Unavailable -> Unit   // disabled — no-op
                null                        -> Unit   // not yet derived — no-op
            }
        }

        // ── INT button ────────────────────────────────────────────────────────
        btnInt.setOnClickListener      { haptic(it); viewModel.jumpToInterrupt() }
        btnInt.setOnLongClickListener  { haptic(it); viewModel.toggleInterruptSlot(); true }

        // ── Next / Auto button ────────────────────────────────────────────────
        btnScheduleNext.setOnClickListener    { haptic(it); viewModel.nextSibling(onQueueTab = currentTab == 0) }
        btnScheduleNext.setOnLongClickListener { haptic(it); viewModel.toggleAutoMode(); true }
    }

    private fun setupObservers() {
        // ── Auto Switch — Call Detection ──────────────────────────────────────
        CallEvents.event.observe(this) { type ->
            if (type == null) return@observe
            val callTaskId = AutoSwitchPrefs.getCallTaskId(this) ?: return@observe
            when (type) {
                CallEvents.Type.CALL_STARTED -> viewModel.handleCallStarted(callTaskId)
                CallEvents.Type.CALL_ENDED   -> viewModel.handleCallEnded()
            }
            CallEvents.event.value = null   // consume
        }

        // Queue tab — flat group-aware list
        viewModel.flatActiveTasks.observe(this) { items ->
            activeAdapter.submitList(items)
            activeAdapter.setRunningTask(viewModel.currentTask.value?.id)
            updateEmptyView()
        }

        // Schedule tab — flat group-aware list
        viewModel.flatScheduleOrder.observe(this) { items ->
            scheduleAdapter.submitList(items)
            scheduleAdapter.setRunningTask(viewModel.currentTask.value?.id)
            updateScheduleRankBadge()
        }

        // Completed tab — flat (no group hierarchy for completed)
        viewModel.completedTasks.observe(this) { tasks ->
            completedAdapter.submitList(tasks.map {
                com.eevdf.scheduler.model.TaskDisplayItem(it, 0)
            })
            updateEmptyView()
        }

        viewModel.currentTask.observe(this) { task ->
            if (task != null) {
                cardTimer.visibility = View.VISIBLE
                tvCurrentTaskName.text = task.name
                tvTimerPriority.text = "Priority ${task.priority} · ${task.category}"
                tvTimerDisplay.text = task.remainingDisplay
                activeAdapter.setRunningTask(task.id)
                scheduleAdapter.setRunningTask(task.id)
                if (viewModel.autoScrollEnabled.value == true) scrollToTask(task.id)
                // Auto mode: onTimerFinished queued the next task — start it immediately
                if (viewModel.consumePendingAutoStart()) viewModel.startTimer()
            } else {
                cardTimer.visibility = View.GONE
                activeAdapter.setRunningTask(null)
                scheduleAdapter.setRunningTask(null)
                tvTimerDisplay.text = "00:00"
            }
            updateScheduleRankBadge()
        }

        viewModel.timerSeconds.observe(this) { seconds ->
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            tvTimerDisplay.text = if (h > 0)
                String.format("%d:%02d:%02d", h, m, s)
            else
                String.format("%02d:%02d", m, s)
        }

        // ── Start / Pause / Cancel button ─────────────────────────────────────
        //
        // Single observer on timerCardAction. The ViewModel has already combined
        // noticePhase + timerRunning + currentTask into one atomic value, so this
        // fires ONCE per logical state change — not once per individual LiveData
        // update.  The click handler (setupTimerCard) dispatches from the same
        // object, so UI and behaviour are guaranteed to match.
        viewModel.timerCardAction.observe(this) { action ->
            btnStartPause.text    = action.label
            btnStartPause.icon    = null
            btnStartPause.isEnabled = action.enabled
            btnStartPause.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, action.colorRes)
                )
            btnStartPause.jumpDrawablesToCurrentState()
        }

        // Phase-status bar — depends on NoticePhase subtype detail (remainingSecs)
        // that TimerCardAction intentionally omits, so driven separately here.
        viewModel.noticePhase.observe(this) { phase ->
            when (phase) {
                is NoticePhase.Delay -> {
                    viewPhaseStatus.setBackgroundColor(android.graphics.Color.parseColor("#FFB300"))
                    viewPhaseStatus.visibility = View.VISIBLE
                    val m = phase.remainingSecs / 60; val s = phase.remainingSecs % 60
                    tvTimerDisplay.text = "%02d:%02d".format(m, s)
                }
                is NoticePhase.Wait -> {
                    viewPhaseStatus.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                    viewPhaseStatus.visibility = View.VISIBLE
                    val m = phase.remainingSecs / 60; val s = phase.remainingSecs % 60
                    tvTimerDisplay.text = "%02d:%02d".format(m, s)
                }
                else -> viewPhaseStatus.visibility = View.GONE
            }
        }

        viewModel.stats.observe(this) { stats ->
            tvStats.text    = "Active: ${stats.activeTasks}  |  Done: ${stats.completedTasks}  |  Weight: ${"%.1f".format(stats.totalWeight)}"
            tvFairness.text = "Fairness: ${"%.0f".format(stats.fairnessScore * 100)}%  |  Avg VRT: ${"%.2f".format(stats.averageVruntime)}"
        }

        viewModel.toastMessage.observe(this) { msg ->
            if (!msg.isNullOrEmpty()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                viewModel.clearToast()
            }
        }

        viewModel.alarmTaskName.observe(this) { taskName ->
            if (taskName != null) {
                cardAlarmBanner.visibility = View.VISIBLE
                tvAlarmTaskName.text = taskName
                tvAlarmSubtitle.text = "Time slice complete · tap Stop to dismiss"
            } else {
                cardAlarmBanner.visibility = View.GONE
            }
        }

        viewModel.alarmElapsedSeconds.observe(this) { elapsed ->
            tvAlarmElapsed.text = NotificationHelper.formatElapsed(elapsed)
        }

        // Sync groups menu checkmark
        viewModel.groupsEnabled.observe(this) { enabled ->
            groupsMenuItem?.isChecked = enabled
        }
        viewModel.globalRotateEnabled.observe(this) { enabled ->
            globalRotateMenuItem?.isChecked = enabled
        }
        viewModel.allowEditEnabled.observe(this) { enabled ->
            allowEditMenuItem?.isChecked = enabled
            fabAdd.visibility = if (enabled) View.VISIBLE else View.GONE
        }
        viewModel.autoScrollEnabled.observe(this) { enabled ->
            autoScrollMenuItem?.isChecked = enabled
        }

        // ── Next / Auto button ────────────────────────────────────────────────
        viewModel.nextButtonState.observe(this) { state ->
            btnScheduleNext.text = state.label
        }
        // Lock Global Rotate menu item while Auto mode is active
        viewModel.autoMode.observe(this) { auto ->
            globalRotateMenuItem?.isEnabled = !auto
        }

        // ── INT button ────────────────────────────────────────────────────────
        //
        // Previously three separate observers (slot, taskA, taskB) each read
        // the other two LiveData via .value at dispatch time — if dispatches
        // interleaved, the color and label could be set from mismatched values
        // for one frame.  Single observer on intButtonState fixes that.
        viewModel.intButtonState.observe(this) { state ->
            btnInt.text = state.label
            val color = if (state.textColorHex.isNotEmpty())
                android.graphics.Color.parseColor(state.textColorHex)
            else
                ContextCompat.getColor(this, R.color.colorPrimary)
            btnInt.setTextColor(color)
            btnInt.jumpDrawablesToCurrentState()
        }
    }

    /**
     * Scrolls the RecyclerView to the card of [taskId].
     * If the task is not in the currently visible adapter, switches to the Queue tab first.
     */
    private fun scrollToTask(taskId: String) {
        // Only scroll within the currently visible tab — never switch tabs

        val currentAdapter = when (currentTab) {
            0    -> activeAdapter
            1    -> scheduleAdapter
            else -> return
        }
        val position = currentAdapter.currentList.indexOfFirst { it.task.id == taskId }
        if (position >= 0) {
            (recyclerView.layoutManager as? LinearLayoutManager)
                ?.smoothScrollToPosition(recyclerView, null, position)
        }
    }

    private fun updateScheduleRankBadge() {
        val runningId = viewModel.currentTask.value?.id
        val number = if (runningId != null) {
            viewModel.flatScheduleOrder.value
                ?.find { it.task.id == runningId }
                ?.queueNumber
        } else null
        if (!number.isNullOrEmpty()) {
            tvScheduleRank.text       = "#$number"
            tvScheduleRank.visibility = View.VISIBLE
        } else {
            tvScheduleRank.visibility = View.GONE
        }
    }

    private fun updateEmptyView() {
        val isEmpty = when (currentTab) {
            0    -> viewModel.flatActiveTasks.value?.isEmpty() ?: true
            1    -> viewModel.flatScheduleOrder.value?.isEmpty() ?: true
            else -> viewModel.completedTasks.value?.isEmpty() ?: true
        }
        emptyView.visibility    = if (isEmpty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (isEmpty) View.GONE   else View.VISIBLE
    }

    private fun showTaskDetail(task: Task) {
        startActivity(Intent(this, AddTaskActivity::class.java).apply {
            putExtra("task_id", task.id)
        })
    }

    private fun confirmDelete(task: Task) {
        val msg = if (task.isGroup)
            "Delete group \"${task.name}\" and all its tasks?"
        else
            "Delete \"${task.name}\"?"
        AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage(msg)
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteTask(task) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        groupsMenuItem       = menu.findItem(R.id.action_toggle_groups)
        groupsMenuItem?.isChecked = viewModel.groupsEnabled.value ?: false
        globalRotateMenuItem = menu.findItem(R.id.action_toggle_global_rotate)
        globalRotateMenuItem?.isChecked = viewModel.globalRotateEnabled.value ?: false
        allowEditMenuItem    = menu.findItem(R.id.action_allow_edit)
        allowEditMenuItem?.isChecked = viewModel.allowEditEnabled.value ?: false
        autoScrollMenuItem   = menu.findItem(R.id.action_auto_scroll)
        autoScrollMenuItem?.isChecked = viewModel.autoScrollEnabled.value ?: false

        // Action view supports both tap and long-press; a plain MenuItem only fires tap.
        menu.findItem(R.id.action_schedule_next)?.actionView?.let { view ->
            // Tap → jump to first visible leaf task in the current tab
            view.setOnClickListener {
                haptic(view)
                viewModel.jumpToFirst(onQueueTab = currentTab == 0)
            }
            // Hold → if timer card active: pause + dismiss. If no active task: toggle all groups on visible tab.
            view.setOnLongClickListener {
                haptic(view)
                if (cardTimer.visibility == View.VISIBLE) {
                    viewModel.pauseAndDismiss()
                } else {
                    if (currentTab == 0) viewModel.toggleAllQueueGroupsExpanded()
                    else                 viewModel.toggleAllScheduleGroupsExpanded()
                }
                true
            }
        }
        // Attach Stats long-press to the overflow (3-dot) button
        hookOverflowLongPress(findViewById(R.id.toolbar))
        return true
    }

    /**
     * Recursively searches the Toolbar's view tree for the overflow (3-dot) button
     * by matching AppCompat's own content-description string, then attaches a
     * long-click listener that opens StatsActivity.
     *
     * Using contentDescription is the only reliable cross-version identifier —
     * the class name ("OverflowMenuButton") is internal and varies by API level.
     */
    private fun hookOverflowLongPress(toolbar: Toolbar) {
        toolbar.post {
            val desc = getString(androidx.appcompat.R.string.abc_action_menu_overflow_description)
            val btn  = findViewByContentDesc(toolbar, desc)
            btn?.setOnLongClickListener {
                startActivity(Intent(this@MainActivity, StatsActivity::class.java))
                true
            }
        }
    }

    private fun findViewByContentDesc(root: android.view.ViewGroup, desc: String): View? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.contentDescription?.toString() == desc) return child
            if (child is android.view.ViewGroup) {
                val found = findViewByContentDesc(child, desc)
                if (found != null) return found
            }
        }
        return null
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_groups -> {
                viewModel.toggleGroupsEnabled()
                item.isChecked = viewModel.groupsEnabled.value ?: false
                true
            }
            R.id.action_toggle_global_rotate -> {
                if (viewModel.autoMode.value == true) {
                    Toast.makeText(this, "Global Rotate is managed by Auto mode", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.toggleGlobalRotate()
                    item.isChecked = viewModel.globalRotateEnabled.value ?: false
                }
                true
            }
            R.id.action_allow_edit -> {
                viewModel.toggleAllowEdit()
                item.isChecked = viewModel.allowEditEnabled.value ?: false
                true
            }
            R.id.action_auto_scroll -> {
                viewModel.toggleAutoScroll()
                item.isChecked = viewModel.autoScrollEnabled.value ?: false
                true
            }
            R.id.action_clear_completed -> { viewModel.clearCompleted(); true }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
