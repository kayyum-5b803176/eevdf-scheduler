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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eevdf.scheduler.R
import com.eevdf.scheduler.adapter.TaskAdapter
import com.eevdf.scheduler.model.Task
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
    private lateinit var emptyView:           LinearLayout
    private lateinit var cardAlarmBanner:     CardView
    private lateinit var tvAlarmTaskName:     TextView
    private lateinit var tvAlarmElapsed:      TextView
    private lateinit var tvAlarmSubtitle:     TextView
    private lateinit var btnStopAlarm:        MaterialButton

    private var currentTab = 0
    private val prefs by lazy { getSharedPreferences("eevdf_prefs", android.content.Context.MODE_PRIVATE) }

    /** Convenience: fire haptic feedback on [v] if enabled in prefs. */
    private fun haptic(v: android.view.View) = com.eevdf.scheduler.ui.VibrationManager.haptic(v, prefs)
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
                object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
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

    override fun onResume() { super.onResume() }
    override fun onPause()  { super.onPause() }

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
        emptyView         = findViewById(R.id.emptyView)
        cardAlarmBanner   = findViewById(R.id.cardAlarmBanner)
        tvAlarmTaskName   = findViewById(R.id.tvAlarmTaskName)
        tvAlarmElapsed    = findViewById(R.id.tvAlarmElapsed)
        tvAlarmSubtitle   = findViewById(R.id.tvAlarmSubtitle)
        btnStopAlarm      = findViewById(R.id.btnStopAlarm)

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
        btnStartPause.setOnClickListener {
            haptic(it)
            viewModel.stopAlarmSound()
            if (viewModel.timerRunning.value == true) viewModel.pauseTimer()
            else viewModel.startTimer()
        }
        btnInt.setOnClickListener { haptic(it); viewModel.jumpToInterrupt() }
        btnScheduleNext.setOnClickListener { haptic(it); viewModel.nextSibling(onQueueTab = currentTab == 0) }
    }

    private fun setupObservers() {
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
            } else {
                cardTimer.visibility = View.GONE
                activeAdapter.setRunningTask(null)
                scheduleAdapter.setRunningTask(null)
                tvTimerDisplay.text = "00:00"
            }
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

        viewModel.timerRunning.observe(this) { running ->
            btnStartPause.text = if (running) "Pause" else "Start"
            btnStartPause.icon = null
            val tintColor = if (running) R.color.timerYellow else R.color.timerGreen
            btnStartPause.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(this, tintColor)
                )
            // Skip MaterialShapeDrawable's internal color-transition animator
            // to prevent the brief light-flash artifact on state change
            btnStartPause.jumpDrawablesToCurrentState()
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
        }
        viewModel.autoScrollEnabled.observe(this) { enabled ->
            autoScrollMenuItem?.isChecked = enabled
        }
        // When interrupt is assigned: red text on white bg.
        // When no interrupt: primary-color text on white bg (default).
        viewModel.interruptTask.observe(this) { interrupt ->
            val textColor = if (interrupt != null)
                android.graphics.Color.parseColor("#F44336")
            else
                androidx.core.content.ContextCompat.getColor(this, R.color.colorPrimary)
            btnInt.setTextColor(textColor)
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
            // Hold → pause timer + close card (state saved to DB)
            view.setOnLongClickListener {
                haptic(view)
                viewModel.pauseAndDismiss()
                true
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_groups -> {
                viewModel.toggleGroupsEnabled()
                item.isChecked = viewModel.groupsEnabled.value ?: false
                true
            }
            R.id.action_toggle_global_rotate -> {
                viewModel.toggleGlobalRotate()
                item.isChecked = viewModel.globalRotateEnabled.value ?: false
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
